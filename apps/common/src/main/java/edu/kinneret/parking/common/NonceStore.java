package edu.kinneret.parking.common;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoDatabase;
import com.mongodb.client.model.IndexOptions;
import org.bson.Document;

import java.time.Clock;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Stores nonces to reject message replay attempts.
 * Uses a distributed MongoDB collection with a TTL index if provided, 
 * falling back to an in-memory cache optimized with a background thread.
 */
public final class NonceStore implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(NonceStore.class.getName());
    
    private final long ttlSeconds;
    private final Clock clock;
    
    public static volatile boolean requireDbOnline = true;
    
    // In-memory fallback
    private final Map<String, Long> noncesByValue;
    private ScheduledExecutorService scheduler;
    
    // Distributed storage
    private MongoCollection<Document> nonceCollection;
    private MongoConnectionManager mongoConnectionManager;

    /**
     * Creates a distributed nonce store using MongoDB TTL collection.
     *
     * @param config the application config to connect to MongoDB
     */
    public NonceStore(AppConfig config) {
        this.ttlSeconds = ValidationUtils.requirePositive(config.getNonceTtlSeconds(), "ttlSeconds");
        this.clock = Clock.systemUTC();
        this.noncesByValue = new ConcurrentHashMap<>();
        
        try {
            this.mongoConnectionManager = new MongoConnectionManager(config, "parking_db");
            MongoDatabase database = this.mongoConnectionManager.getDatabase();
            this.nonceCollection = database.getCollection("security_nonces");
            
            // Create a TTL index so MongoDB automatically deletes expired nonces
            this.nonceCollection.createIndex(new Document("createdAt", 1), 
                    new IndexOptions().expireAfter((long) ttlSeconds, TimeUnit.SECONDS));
            logger.info("Distributed NonceStore initialized using MongoDB TTL collection.");
        } catch (Exception e) {
            logger.severe("Could not initialize MongoDB NonceStore. Failing fast to prevent replay attacks! "
                    + SecurityLogger.sanitize(e.getMessage()));
            if (requireDbOnline) {
                throw new IllegalStateException("Distributed NonceStore is required but MongoDB connection failed.", e);
            }
        }
    }

    /**
     * Creates an in-memory nonce store using the system clock and starts the cleanup task.
     *
     * @param ttlSeconds the nonce time-to-live in seconds
     */
    public NonceStore(long ttlSeconds) {
        this(ttlSeconds, Clock.systemUTC());
    }

    /**
     * Creates an in-memory nonce store using a supplied clock and starts the cleanup task.
     *
     * @param ttlSeconds the nonce time-to-live in seconds
     * @param clock the clock used for expiration checks
     */
    public NonceStore(long ttlSeconds, Clock clock) {
        this.ttlSeconds = ValidationUtils.requirePositive(ttlSeconds, "ttlSeconds");
        this.clock = clock;
        this.noncesByValue = new ConcurrentHashMap<>();
        initInMemoryScheduler();
        logger.info("In-Memory NonceStore initialized with " + ttlSeconds + "s TTL.");
    }

    private void initInMemoryScheduler() {
        long cleanupInterval = Math.max(5, Math.min(30, ttlSeconds / 2));
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "nonce-cleaner");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleAtFixedRate(this::purgeExpiredEntries, cleanupInterval, cleanupInterval, TimeUnit.SECONDS);
    }

    /**
     * Adds a nonce when it has not been seen within the configured TTL.
     *
     * @param nonce the nonce to add
     * @return {@code true} when the nonce was accepted, or {@code false} when it was already present
     */
    public boolean addNonce(String nonce) {
        String sanitizedNonce = ValidationUtils.requireNonEmpty(nonce, "nonce");
        
        if (nonceCollection != null) {
            try {
                Document doc = new Document("_id", sanitizedNonce)
                                  .append("createdAt", new java.util.Date(clock.millis()));
                nonceCollection.insertOne(doc);
                return true;
            } catch (com.mongodb.MongoWriteException e) {
                if (e.getError().getCategory() == com.mongodb.ErrorCategory.DUPLICATE_KEY) {
                    return false;
                }
                throw e;
            }
        }
        
        long expiresAt = nowEpochSeconds() + ttlSeconds;
        Long existingExpiry = noncesByValue.putIfAbsent(sanitizedNonce, expiresAt);
        if (existingExpiry == null) {
            return true;
        }
        if (existingExpiry <= nowEpochSeconds()) {
            noncesByValue.put(sanitizedNonce, expiresAt);
            return true;
        }
        return false;
    }

    /**
     * Returns the configured TTL in seconds.
     *
     * @return the TTL value
     */
    public long getTtlSeconds() {
        return ttlSeconds;
    }

    /**
     * Returns the current nonce count.
     *
     * @return the number of stored nonces
     */
    public int size() {
        return nonceCollection != null ? (int) nonceCollection.countDocuments() : noncesByValue.size();
    }

    private long nowEpochSeconds() {
        return clock.instant().getEpochSecond();
    }

    private void purgeExpiredEntries() {
        long currentTime = nowEpochSeconds();
        int before = noncesByValue.size();
        noncesByValue.entrySet().removeIf(entry -> entry.getValue() <= currentTime);
        int removed = before - noncesByValue.size();
        if (removed > 0) {
            logger.fine("Purged " + removed + " expired nonces from memory.");
        }
    }

    /**
     * Stops the background cleanup thread and releases scheduler resources.
     */
    @Override
    public void close() {
        if (scheduler != null) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        if (mongoConnectionManager != null) {
            mongoConnectionManager.close();
        }
    }
}

