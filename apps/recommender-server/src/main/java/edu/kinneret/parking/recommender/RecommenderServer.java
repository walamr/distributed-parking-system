package edu.kinneret.parking.recommender;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import edu.kinneret.parking.common.AppConfig;
import edu.kinneret.parking.common.NonceStore;
import edu.kinneret.parking.common.ParkingRepository;
import edu.kinneret.parking.common.RabbitMqConnectionManager;
import edu.kinneret.parking.common.SecureMessageSigner;
import edu.kinneret.parking.common.SecurityLogger;
import edu.kinneret.parking.common.TlsUtils;
import edu.kinneret.parking.common.ValidationUtils;
import org.bson.Document;

import javax.net.ssl.SSLHandshakeException;
import javax.net.ssl.SSLServerSocket;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Recommender server node that implements secure recommendation requests,
 * local recommendation generation, and leader majority consensus.
 */
public class RecommenderServer implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(RecommenderServer.class.getName());
    private static final int TIMEOUT_MS = 2000;
    private static final int MAX_SPACE_NUMBER = 100;
    private static final long MAX_MESSAGE_AGE_SECONDS = 60;
    private static final String SAFE_CLIENT_FAILURE = "Recommendation service temporarily unavailable";
    private static final Set<String> REQUEST_TYPES = Set.of("CLIENT_QUERY", "FORWARD_QUERY", "COLLECT_REQUEST");
    private static final Set<String> RESPONSE_TYPES = Set.of("CLIENT_RESPONSE", "COLLECT_RESPONSE");
    private static final Set<String> SIGNED_FIELDS = Set.of(
            "type", "spaceId", "correlationId", "timestamp", "nonce", "nodeId", "localResult", "status", "result", "reason");

    private final String nodeId;
    private final int port;
    private final String leaderHost;
    private final int leaderPort;
    private final boolean isLeader;
    private volatile boolean isMalicious;
    private volatile String maliciousPayload;
    private final List<NodeEndpoint> clusterNodes;
    private final AppConfig appConfig;
    private final SecureMessageSigner signer;
    private final NonceStore nonceStore;
    private final javax.net.ssl.SSLContext serverSslContext;
    private final javax.net.ssl.SSLContext clientSslContext;

    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private volatile boolean running = true;

    private final Map<String, List<Long>> ipRequestTimestamps = new ConcurrentHashMap<>();
    private static final int MAX_REQUESTS_PER_MINUTE = 60;

    /**
     * Creates a recommender server node.
     *
     * @param nodeId unique identifier for this node
     * @param port port to listen on
     * @param leaderHost host address of the cluster leader
     * @param leaderPort port of the cluster leader
     * @param isLeader flag indicating if this node is the leader
     * @param isMalicious flag indicating if this node should behave maliciously
     * @param clusterNodes list of all nodes in nodeId=host:port or host:port form
     * @param appConfig application configuration for DB access and TLS/HMAC settings
     */
    public RecommenderServer(String nodeId, int port, String leaderHost, int leaderPort,
                             boolean isLeader, boolean isMalicious, List<String> clusterNodes,
                             AppConfig appConfig) {
        this.nodeId = ValidationUtils.requireValidMessageType(nodeId, "nodeId");
        this.port = ValidationUtils.requirePositive(port, "port");
        this.leaderHost = ValidationUtils.requireNonEmpty(leaderHost, "leaderHost");
        this.leaderPort = ValidationUtils.requirePositive(leaderPort, "leaderPort");
        this.isLeader = isLeader;
        this.isMalicious = isMalicious;
        String suffix = "999;999";
        if (nodeId != null) {
            String digits = nodeId.replaceAll("\\D+", "");
            if (!digits.isEmpty()) {
                suffix = "99" + digits + ";99" + digits;
            }
        }
        this.maliciousPayload = "Space " + suffix;
        this.clusterNodes = parseClusterNodes(clusterNodes);
        this.appConfig = appConfig;
        this.signer = new SecureMessageSigner(appConfig.getHmacSecret());
        this.nonceStore = new NonceStore(appConfig);
        javax.net.ssl.SSLContext cCtx = null;
        javax.net.ssl.SSLContext sCtx = null;
        try {
            cCtx = TlsUtils.createSslContext(
                    appConfig.getTlsTruststorePath(),
                    appConfig.getTlsTruststorePassword(),
                    appConfig.getTlsKeystorePath(),
                    appConfig.getTlsKeystorePassword());
            sCtx = TlsUtils.createSslContext(
                    appConfig.getTlsTruststorePath(),
                    appConfig.getTlsTruststorePassword(),
                    appConfig.getTlsServerKeystorePath(),
                    appConfig.getTlsServerKeystorePassword());
        } catch (Exception e) {
            logger.warning("Recommender TLS/mTLS configuration is invalid. TLS features will be disabled: " + e.getMessage());
        }
        this.clientSslContext = cCtx;
        this.serverSslContext = sCtx;
    }

    /**
     * Starts the TLS listener and background worker pool.
     *
     * @throws IOException if the secure server socket cannot be opened
     */
    public void start() throws IOException {
        if (serverSslContext == null || clientSslContext == null) {
            throw new IllegalStateException("Cannot start RecommenderServer without valid TLS/mTLS configuration.");
        }
        SSLServerSocketFactory factory = serverSslContext.getServerSocketFactory();
        SSLServerSocket tlsServerSocket = (SSLServerSocket) factory.createServerSocket(port);
        tlsServerSocket.setEnabledProtocols(enabledTlsProtocols(tlsServerSocket.getSupportedProtocols()));
        tlsServerSocket.setNeedClientAuth(true);
        serverSocket = tlsServerSocket;

        executorService = Executors.newFixedThreadPool(50, runnable -> {
            Thread t = new Thread(runnable, "recommender-worker-" + nodeId);
            t.setDaemon(true);
            return t;
        });

        try {
            RabbitMqConnectionManager rabbitManager = new RabbitMqConnectionManager(appConfig);
            if (rabbitManager.checkHealth()) {
                logger.info("Recommender node '" + nodeId + "' verified RabbitMQ TLS connectivity.");
            } else {
                logger.warning("Recommender node '" + nodeId + "' could not reach RabbitMQ server.");
            }
        } catch (Exception e) {
            logger.warning("Recommender node '" + nodeId + "' failed RabbitMQ health check: "
                    + SecurityLogger.sanitize(e.getMessage()));
        }

        logger.info("Recommender node '" + nodeId + "' started with TLS/mTLS on port " + port
                + " [Leader: " + isLeader + ", Malicious: " + isMalicious + "]");
        executorService.submit(this::listen);
    }

    /**
     * Accepts incoming TLS connections and dispatches each connection to a worker.
     *
     * @param none no input parameters
     * @return no return value
     */
    private void listen() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                executorService.submit(() -> handleConnection(clientSocket));
            } catch (SSLHandshakeException e) {
                logSecurity("TLS_HANDSHAKE_FAILURE", "unknown", "failed TLS/mTLS handshake: " + e.getMessage());
            } catch (IOException e) {
                if (!running) {
                    break;
                }
                logger.log(Level.WARNING, "Error accepting TLS connection on node " + nodeId, e);
            }
        }
    }

/**

 * Is rate limited.

 * @param ip the ip

 * @return the boolean

 */

    private boolean isRateLimited(String ip) {
        long now = Instant.now().getEpochSecond();
        List<Long> timestamps = ipRequestTimestamps.computeIfAbsent(ip, k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        timestamps.removeIf(ts -> now - ts > 60);
        if (timestamps.size() >= MAX_REQUESTS_PER_MINUTE) {
            return true;
        }
        timestamps.add(now);
        return false;
    }

    /**
     * Reads a single signed JSON request from a TLS socket and routes it by type.
     *
     * @param socket accepted TLS socket from a client or peer recommender
     * @return no return value
     */
    private void handleConnection(Socket socket) {
        String ip = socket.getInetAddress() != null ? socket.getInetAddress().getHostAddress() : "unknown";
        if (isRateLimited(ip)) {
            logSecurity("RATE_LIMIT_EXCEEDED", remoteAddress(socket), "too many requests from IP");
            try {
                socket.close();
            } catch (IOException ignored) {}
            return;
        }

        try (Socket s = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter writer = new PrintWriter(s.getOutputStream(), true)) {

            if (!(s instanceof SSLSocket sslSocket)) {
                logSecurity("PLAINTEXT_REJECTED", remoteAddress(s), "non-TLS socket rejected");
                sendSignedFailure(writer, "CLIENT_RESPONSE", UUID.randomUUID().toString(), SAFE_CLIENT_FAILURE);
                return;
            }
            sslSocket.setEnabledProtocols(enabledTlsProtocols(sslSocket.getSupportedProtocols()));
            sslSocket.startHandshake();

            String line = readBoundedLine(reader, 65536);
            if (line == null || line.isBlank()) {
                logSecurity("INVALID_INPUT", remoteAddress(s), "empty request");
                return;
            }

            ValidationUtils.requireSafePayloadSize(line, 65536);
            JsonObject request = parseAndValidateRequest(line, remoteAddress(s));
            String type = request.get("type").getAsString();
            String spaceId = request.get("spaceId").getAsString();
            String correlationId = request.get("correlationId").getAsString();

            switch (type) {
                case "CLIENT_QUERY" -> handleClientQuery(spaceId, correlationId, writer);
                case "FORWARD_QUERY" -> handleForwardQuery(request, writer);
                case "COLLECT_REQUEST" -> handleCollectRequest(spaceId, correlationId, writer);
                default -> throw new IllegalArgumentException("Unsupported recommender message type.");
            }
        } catch (IllegalArgumentException e) {
            logger.log(Level.WARNING, "Rejected recommender request on node " + nodeId + ": "
                    + SecurityLogger.sanitize(e.getMessage()));
        } catch (SSLHandshakeException e) {
            logSecurity("TLS_HANDSHAKE_FAILURE", remoteAddress(socket), "failed TLS/mTLS handshake: " + e.getMessage());
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception handling recommender connection on node " + nodeId, e);
        }
    }

    /**
     * Handles a customer recommendation request, forwarding to the leader when needed.
     *
     * @param spaceId validated numeric parking space number
     * @param correlationId request correlation identifier
     * @param clientWriter writer used to return the signed client response
     * @return no return value
     */
    private void handleClientQuery(String spaceId, String correlationId, PrintWriter clientWriter) {
        try {
            if (isLeader) {
                sendConsensusResponse(spaceId, correlationId, clientWriter);
                return;
            }

            String localResult = calculateLocalRecommendation(spaceId);
            JsonObject forwardRequest = createSignedMessage("FORWARD_QUERY", spaceId, correlationId, nodeId);
            forwardRequest.addProperty("localResult", localResult);
            signMessage(forwardRequest, signer);

            try (SSLSocket leaderSocket = openTlsSocket(leaderHost, leaderPort)) {
                try (PrintWriter leaderWriter = new PrintWriter(leaderSocket.getOutputStream(), true);
                     BufferedReader leaderReader = new BufferedReader(new InputStreamReader(leaderSocket.getInputStream()))) {
                    leaderWriter.println(forwardRequest);
                    String reply = leaderReader.readLine();
                    if (reply == null) {
                        sendSignedFailure(clientWriter, "CLIENT_RESPONSE", correlationId, SAFE_CLIENT_FAILURE);
                        return;
                    }
                    JsonObject signedReply = parseAndValidateResponse(reply, leaderHost + ":" + leaderPort);
                    clientWriter.println(signedReply);
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Node '" + nodeId + "' failed to reach configured leader.", e);
                logSecurity("CONSENSUS_FALLBACK", leaderHost + ":" + leaderPort, "follower could not reach leader, stepping up");
                logger.info("Leader unreachable. Node '" + nodeId + "' is stepping up to execute consensus locally.");
                sendConsensusResponse(spaceId, correlationId, clientWriter);
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error in handleClientQuery on node " + nodeId, e);
            sendSignedFailure(clientWriter, "CLIENT_RESPONSE", correlationId, SAFE_CLIENT_FAILURE);
        }
    }

    /**
     * Handles a follower-forwarded vote on the leader and returns the majority result.
     *
     * @param forwardRequest signed request containing the follower's local vote
     * @param leaderWriter writer used to return the signed leader response
     * @return no return value
     */
    private void handleForwardQuery(JsonObject forwardRequest, PrintWriter leaderWriter) {
        if (!isLeader) {
            logger.info("Non-leader node '" + nodeId + "' received FORWARD_QUERY. Executing consensus dynamically as fallback leader.");
        }

        String correlationId = forwardRequest.get("correlationId").getAsString();
        try {
            String spaceId = forwardRequest.get("spaceId").getAsString();
            String followerResult = forwardRequest.get("localResult").getAsString();
            String followerId = forwardRequest.get("nodeId").getAsString();

            Map<String, String> explicitVotes = new HashMap<>();
            explicitVotes.put(followerId, followerResult);
            sendConsensusResponse(spaceId, correlationId, leaderWriter, explicitVotes);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error in handleForwardQuery on leader", e);
            sendSignedFailure(leaderWriter, "CLIENT_RESPONSE", correlationId, SAFE_CLIENT_FAILURE);
        }
    }

    /**
     * Handles a leader collection request by returning this node's local recommendation.
     *
     * @param spaceId validated numeric parking space number
     * @param correlationId request correlation identifier
     * @param writer writer used to return the signed collect response
     * @return no return value
     */
    private void handleCollectRequest(String spaceId, String correlationId, PrintWriter writer) {
        try {
            String localResult = calculateLocalRecommendation(spaceId);
            JsonObject response = createSignedMessage("COLLECT_RESPONSE", spaceId, correlationId, nodeId);
            response.addProperty("localResult", localResult);
            signMessage(response, signer);
            writer.println(response);
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error in handleCollectRequest on follower " + nodeId, e);
            JsonObject response = createSignedMessage("COLLECT_RESPONSE", spaceId, correlationId, nodeId);
            response.addProperty("localResult", "");
            signMessage(response, signer);
            writer.println(response);
        }
    }

/**

 * Send consensus response.

 * @param spaceId the spaceId

 * @param correlationId the correlationId

 * @param writer the writer

 */

    private void sendConsensusResponse(String spaceId, String correlationId, PrintWriter writer) {
        sendConsensusResponse(spaceId, correlationId, writer, Collections.emptyMap());
    }

/**

 * Send consensus response.

 * @param spaceId the spaceId

 * @param correlationId the correlationId

 * @param writer the writer

 * @param MapString the MapString

 * @param explicitVotes the explicitVotes

 */

    private void sendConsensusResponse(String spaceId, String correlationId, PrintWriter writer, Map<String, String> explicitVotes) {
        String consensus = executeLeaderConsensus(spaceId, explicitVotes);
        JsonObject response = createSignedMessage("CLIENT_RESPONSE", spaceId, correlationId, nodeId);
        if (consensus != null) {
            response.addProperty("status", "SUCCESS");
            String finalResult;
            if (consensus.startsWith("Request:")) {
                finalResult = consensus;
            } else {
                long requestedCitations = getLocalCitationsCount(spaceId);
                String consensusList = consensus;
                if (!consensusList.startsWith("Space ") && !consensusList.equals("NONE")) {
                    consensusList = "Space " + consensusList;
                }
                finalResult = "Request: Space " + spaceId + " (" + requestedCitations + " Citations)\nResult: " + consensusList;
            }
            response.addProperty("result", finalResult);
        } else {
            response.addProperty("status", "FAILURE");
            response.addProperty("reason", "No majority consensus reached in cluster.");
        }
        signMessage(response, signer);
        writer.println(response);
    }

/**

 * Get local citations count.

 * @param spaceId the spaceId

 * @return the long

 */

    private long getLocalCitationsCount(String spaceId) {
        try (ParkingRepository repository = new ParkingRepository(appConfig)) {
            if (ParkingRepository.isDbOnline && repository.getDatabase() != null) {
                return repository.getDatabase().getCollection("citations")
                        .countDocuments(com.mongodb.client.model.Filters.or(
                                com.mongodb.client.model.Filters.eq("payload.spaceId", spaceId),
                                com.mongodb.client.model.Filters.eq("spaceId", spaceId)
                        ));
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Failed to get local citation count for space " + spaceId, e);
        }
        return 0;
    }

/**

 * Execute leader consensus.

 * @param spaceId the spaceId

 * @param MapString the MapString

 * @param explicitVotes the explicitVotes

 * @return the string

 */

    private String executeLeaderConsensus(String spaceId, Map<String, String> explicitVotes) {
        Map<String, String> votes = new ConcurrentHashMap<>();
        votes.put(nodeId, calculateLocalRecommendation(spaceId));
        votes.putAll(explicitVotes);

        ExecutorService collectExecutor = Executors.newFixedThreadPool(10);
        try {
            List<Future<Void>> futures = new ArrayList<>();

            for (NodeEndpoint endpoint : clusterNodes) {
                if (endpoint.nodeId().equals(nodeId) || votes.containsKey(endpoint.nodeId())) {
                    continue;
                }

                futures.add(collectExecutor.submit(() -> {
                    try (SSLSocket collectSocket = openTlsSocket(endpoint.host(), endpoint.port())) {
                        try (PrintWriter collectWriter = new PrintWriter(collectSocket.getOutputStream(), true);
                             BufferedReader collectReader = new BufferedReader(new InputStreamReader(collectSocket.getInputStream()))) {
                            JsonObject collectRequest = createSignedMessage("COLLECT_REQUEST", spaceId, UUID.randomUUID().toString(), nodeId);
                            signMessage(collectRequest, signer);
                            collectWriter.println(collectRequest);

                            String reply = collectReader.readLine();
                            if (reply != null) {
                                JsonObject response = parseAndValidateResponse(reply, endpoint.host() + ":" + endpoint.port());
                                String val = response.get("localResult").getAsString();
                                String senderId = response.get("nodeId").getAsString();
                                if (!endpoint.nodeId().equals(senderId)) {
                                    logSecurity("FAILED_AUTHENTICATION", endpoint.host() + ":" + endpoint.port(),
                                            "collect response node identity mismatch");
                                    return null;
                                }
                                if (!val.isBlank()) {
                                    votes.put(senderId, val);
                                }
                            }
                        }
                    } catch (Exception e) {
                        logger.warning("Leader node '" + nodeId + "' failed to collect result from "
                                + endpoint.nodeId() + " at " + endpoint.host() + ":" + endpoint.port());
                    }
                    return null;
                }));
            }

            long endTime = System.currentTimeMillis() + TIMEOUT_MS + 200;
            for (Future<Void> fut : futures) {
                try {
                    long remaining = endTime - System.currentTimeMillis();
                    if (remaining > 0) {
                        fut.get(remaining, TimeUnit.MILLISECONDS);
                    } else {
                        fut.cancel(true);
                    }
                } catch (Exception ignored) {
                }
            }
        } finally {
            collectExecutor.shutdown();
            try {
                if (!collectExecutor.awaitTermination(500, TimeUnit.MILLISECONDS)) {
                    collectExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                collectExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Ensure total configured nodes includes the current node if it's not already in the cluster list
        boolean currentInCluster = clusterNodes.isEmpty() || clusterNodes.stream().anyMatch(node -> node.nodeId().equals(nodeId));
        int totalConfiguredNodes = clusterNodes.isEmpty() ? 3 : clusterNodes.size() + (currentInCluster ? 0 : 1);
        int majorityThreshold = (totalConfiguredNodes / 2) + 1;
        String consensus = determineMajority(votes, majorityThreshold);
        logger.info("Consensus votes collected: " + votes + " (Threshold: " + majorityThreshold + ")");
        if (consensus == null) {
            logSecurity("CONSENSUS_FAILURE", "cluster", "no exact-list majority");
        }
        return consensus;
    }

    /**
     * Normalizes a recommendation string to remove formatting variations.
     *
     * @param recommendation the raw recommendation string
     * @return the normalized recommendation string
     */
    public static String normalizeRecommendation(String recommendation) {
        if (recommendation == null) {
            return "";
        }
        String clean = recommendation.trim().toUpperCase();
        if ("NONE".equals(clean)) {
            return "NONE";
        }
        // Remove prefixes like "SPACE" or "RESULT:" and all whitespace for normalization
        clean = clean.replace("SPACE", "").replace("RESULT:", "").replaceAll("\\s+", "");
        return clean;
    }

    /**
     * Determines the exact-list majority value from collected node votes.
     *
     * @param votes map of node identity to complete serialized recommendation list
     * @param majorityThreshold number of equal votes required to accept a result
     * @return the majority result, or null when no exact-list majority exists
     */
    public static String determineMajority(Map<String, String> votes, int majorityThreshold) {
        Map<String, Integer> counts = new HashMap<>();
        Map<String, Map<String, Integer>> originalCounts = new HashMap<>();

        for (String vote : votes.values()) {
            if (vote != null && !vote.isBlank()) {
                String recommendation = extractRecommendationList(vote);
                String cleanRec = recommendation;
                if (cleanRec.startsWith("Space ")) {
                    cleanRec = cleanRec.substring(6).trim();
                }
                if (recommendation.equals("NONE") || cleanRec.matches("\\d+;\\d+(,\\s*(Space\\s*)?\\d+;\\d+)*")) {
                    String norm = normalizeRecommendation(recommendation);
                    counts.put(norm, counts.getOrDefault(norm, 0) + 1);
                    originalCounts.computeIfAbsent(norm, k -> new HashMap<>())
                                  .put(recommendation, originalCounts.get(norm).getOrDefault(recommendation, 0) + 1);
                }
            }
        }
        String consensusNorm = null;
        int maxVotes = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > maxVotes) {
                maxVotes = entry.getValue();
                consensusNorm = entry.getKey();
            }
        }
        if (maxVotes >= majorityThreshold && consensusNorm != null) {
            Map<String, Integer> originals = originalCounts.get(consensusNorm);
            String bestOriginal = null;
            int bestCount = -1;
            for (Map.Entry<String, Integer> origEntry : originals.entrySet()) {
                if (origEntry.getValue() > bestCount) {
                    bestCount = origEntry.getValue();
                    bestOriginal = origEntry.getKey();
                }
            }
            return bestOriginal;
        }
        return null;
    }

    public static String extractRecommendationList(String vote) {
        if (vote == null) {
            return "";
        }
        if (vote.contains("Result:")) {
            int index = vote.indexOf("Result:");
            return vote.substring(index + "Result:".length()).trim();
        }
        return vote.trim();
    }

    /**
     * Calculates the local recommendation.
     *
     * @param desiredSpaceId the requested space number
     * @return the formatted recommendation string
     */
    public String calculateLocalRecommendation(String desiredSpaceId) {
        try (ParkingRepository repository = new ParkingRepository(appConfig)) {
            return calculateLocalRecommendation(desiredSpaceId, repository);
        }
    }

    /**
     * Calculates the local recommendation using a specified repository instance.
     *
     * @param desiredSpaceId the requested space number
     * @param repository the repository instance to use
     * @return the formatted recommendation string
     */
    public String calculateLocalRecommendation(String desiredSpaceId, ParkingRepository repository) {
        if (isMalicious) {
            String payload = maliciousPayload;
            if (payload == null || payload.isBlank()) {
                payload = "Space 999;999";
            }
            if (!payload.startsWith("Space ") && !payload.equals("NONE")) {
                payload = "Space " + payload;
            }
            return "Request: Space " + desiredSpaceId + "\nResult: " + payload;
        }

        String safeSpaceId = requireValidRecommenderSpace(desiredSpaceId);
        try {
            if (!repository.isSpaceRegistered(safeSpaceId)) {
                throw new IllegalArgumentException("Parking space is not registered in the system.");
            }

            String zoneName = repository.getSpaceZone(safeSpaceId);
            if ("Unknown".equalsIgnoreCase(zoneName)) {
                throw new IllegalArgumentException("Zone could not be identified for requested space.");
            }

            long requestedCitations = 0;
            if (ParkingRepository.isDbOnline && repository.getDatabase() != null) {
                requestedCitations = repository.getDatabase().getCollection("citations")
                        .countDocuments(com.mongodb.client.model.Filters.or(
                                com.mongodb.client.model.Filters.eq("payload.spaceId", safeSpaceId),
                                com.mongodb.client.model.Filters.eq("spaceId", safeSpaceId)
                        ));
            }

            List<SpaceCandidate> candidates = loadAvailableCandidates(safeSpaceId, zoneName, repository);
            List<RecommendationResult> results = recommendFromSpaceCandidates(safeSpaceId, candidates);
            String resultPart = results.isEmpty() ? "NONE" : "Space " + serializeResults(results);
            return "Request: Space " + safeSpaceId + " (" + requestedCitations + " Citations)\nResult: " + resultPart;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Database lookup failed for recommendation", e);
            throw new RuntimeException("Recommendation service temporarily unavailable", e);
        }
    }

    /**
     * Chooses the best spaces from already-filtered available candidates.
     *
     * @param desiredSpaceId validated requested space number
     * @param candidates available candidates in the same parking zone
     * @return nearest minimum-citation recommendation results, possibly empty
     */
    public static List<RecommendationResult> recommendFromCandidates(String desiredSpaceId, List<RecommendationResult> candidates) {
        String safeSpaceId = requireValidRecommenderSpace(desiredSpaceId);
        List<SpaceCandidate> internal = new ArrayList<>();
        for (RecommendationResult candidate : candidates) {
            internal.add(new SpaceCandidate(requireValidRecommenderSpace(candidate.spaceId()), candidate.citationCount()));
        }
        return recommendFromSpaceCandidates(safeSpaceId, internal);
    }

    private List<SpaceCandidate> loadAvailableCandidates(String desiredSpaceId, String zoneName, ParkingRepository repository) {
        List<Document> allSpacesInZone = new ArrayList<>();
        if (ParkingRepository.isDbOnline && repository.getDatabase() != null) {
            repository.getDatabase().getCollection("spaces")
                    .find(com.mongodb.client.model.Filters.eq("zoneName", zoneName))
                    .into(allSpacesInZone);
        } else {
            for (int i = 1; i <= MAX_SPACE_NUMBER; i++) {
                String spaceIdStr = String.valueOf(i);
                if (zoneName.equalsIgnoreCase(repository.getSpaceZone(spaceIdStr))) {
                    allSpacesInZone.add(new Document("spaceId", spaceIdStr));
                }
            }
        }

        List<String> spaceIds = new ArrayList<>();
        for (Document spaceDoc : allSpacesInZone) {
            String spaceId = spaceDoc.getString("spaceId");
            if (spaceId != null) {
                spaceIds.add(spaceId);
            }
        }

        Map<String, String> spaceToLastAction = new HashMap<>();
        Map<String, Long> spaceToCitationCount = new HashMap<>();

        if (ParkingRepository.isDbOnline && repository.getDatabase() != null && !spaceIds.isEmpty()) {
            try {
                // Batch query only the latest transactions for spaceIds in the zone using an aggregation pipeline
                List<Document> txDocs = new ArrayList<>();
                List<Document> pipeline = List.of(
                        new Document("$match", new Document("$or", List.of(
                                new Document("payload.spaceId", new Document("$in", spaceIds)),
                                new Document("spaceId", new Document("$in", spaceIds))
                        ))),
                        new Document("$sort", new Document("timestamp", -1).append("storedAt", -1)),
                        new Document("$group", new Document("_id", new Document("$cond", List.of(
                                new Document("$ne", List.of(new Document("$ifNull", List.of("$payload.spaceId", "")), "")),
                                "$payload.spaceId",
                                "$spaceId"
                        )))
                        .append("latestDoc", new Document("$first", "$$ROOT")))
                );
                List<Document> aggResults = new ArrayList<>();
                repository.getDatabase().getCollection("transactions")
                        .aggregate(pipeline)
                        .into(aggResults);
                for (Document res : aggResults) {
                    Document doc = (Document) res.get("latestDoc");
                    if (doc != null) {
                        txDocs.add(doc);
                    }
                }

                for (Document doc : txDocs) {
                    String spaceId = ParkingRepository.readPayloadField(doc, "spaceId");
                    if (spaceId.isBlank()) {
                        spaceId = doc.getString("spaceId");
                    }
                    if (spaceId != null && !spaceId.isBlank() && !spaceToLastAction.containsKey(spaceId)) {
                        spaceToLastAction.put(spaceId, ParkingRepository.readTransactionAction(doc));
                    }
                }

                // Batch query all citations for spaceIds in the zone
                List<Document> citationDocs = new ArrayList<>();
                repository.getDatabase().getCollection("citations")
                        .find(com.mongodb.client.model.Filters.or(
                                com.mongodb.client.model.Filters.in("payload.spaceId", spaceIds),
                                com.mongodb.client.model.Filters.in("spaceId", spaceIds)
                        ))
                        .into(citationDocs);

                for (Document doc : citationDocs) {
                    String spaceId = ParkingRepository.readPayloadField(doc, "spaceId");
                    if (spaceId.isBlank()) {
                        spaceId = doc.getString("spaceId");
                    }
                    if (spaceId != null && !spaceId.isBlank()) {
                        spaceToCitationCount.put(spaceId, spaceToCitationCount.getOrDefault(spaceId, 0L) + 1);
                    }
                }
            } catch (Exception e) {
                logger.log(Level.WARNING, "Failed to load candidates in batch, checking offline repo path", e);
            }
        }

        List<SpaceCandidate> candidates = new ArrayList<>();
        for (Document spaceDoc : allSpacesInZone) {
            String spaceId = spaceDoc.getString("spaceId");
            if (spaceId == null) {
                continue;
            }
            if (!ParkingRepository.isDbOnline || repository.getDatabase() == null) {
                Document lastTx = repository.getLatestTransactionForSpace(spaceId);
                if (lastTx != null && "start".equalsIgnoreCase(ParkingRepository.readTransactionAction(lastTx))) {
                    continue;
                }
                candidates.add(new SpaceCandidate(spaceId, 0L));
            } else {
                String lastAction = spaceToLastAction.get(spaceId);
                if (lastAction != null && "start".equalsIgnoreCase(lastAction)) {
                    continue;
                }
                long citationCount = spaceToCitationCount.getOrDefault(spaceId, 0L);
                candidates.add(new SpaceCandidate(spaceId, citationCount));
            }
        }
        return candidates;
    }

    private static List<RecommendationResult> recommendFromSpaceCandidates(String desiredSpaceId, List<SpaceCandidate> candidates) {
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }
        long minCitations = candidates.stream().mapToLong(c -> c.citationCount).min().orElse(Long.MAX_VALUE);
        int desiredNum = parseSpaceNumber(desiredSpaceId);
        int minDistance = Integer.MAX_VALUE;
        List<RecommendationResult> results = new ArrayList<>();
        for (SpaceCandidate candidate : candidates) {
            if (candidate.citationCount != minCitations) {
                continue;
            }
            int distance = Math.abs(parseSpaceNumber(candidate.spaceId) - desiredNum);
            if (distance < minDistance) {
                minDistance = distance;
                results.clear();
            }
            if (distance == minDistance) {
                results.add(new RecommendationResult(candidate.spaceId, candidate.citationCount));
            }
        }
        results.sort(Comparator.comparingInt(r -> parseSpaceNumber(r.spaceId())));
        return results;
    }

    private JsonObject parseAndValidateRequest(String line, String source) {
        JsonObject request = parseJsonObject(line);
        validateSignedMessage(request, REQUEST_TYPES, true, source);
        return request;
    }

    private JsonObject parseAndValidateResponse(String line, String source) {
        JsonObject response = parseJsonObject(line);
        validateSignedMessage(response, RESPONSE_TYPES, false, source);
        return response;
    }

    private void validateSignedMessage(JsonObject message, Set<String> allowedTypes, boolean storeNonce, String source) {
        Set<String> allowed = new HashSet<>(SIGNED_FIELDS);
        for (String field : message.keySet()) {
            if (!allowed.contains(field) && !"hmac".equals(field)) {
                logSecurity("INVALID_INPUT", source, "unexpected field: " + field);
                throw new IllegalArgumentException("Unexpected field.");
            }
        }
        requireString(message, "type");
        requireString(message, "spaceId");
        requireString(message, "correlationId");
        requireString(message, "timestamp");
        requireString(message, "nonce");
        requireString(message, "nodeId");
        requireString(message, "hmac");
        String type = message.get("type").getAsString();
        if (!allowedTypes.contains(type)) {
            logSecurity("INVALID_INPUT", source, "unsupported type: " + type);
            throw new IllegalArgumentException("Unsupported type.");
        }
        requireValidRecommenderSpace(message.get("spaceId").getAsString());
        ValidationUtils.requireValidUuid(message.get("correlationId").getAsString(), "correlationId");
        ValidationUtils.requireValidUuid(message.get("nonce").getAsString(), "nonce");
        ValidationUtils.requireValidMessageType(message.get("nodeId").getAsString(), "nodeId");
        if (message.has("localResult") && !message.get("localResult").isJsonPrimitive()) {
            throw new IllegalArgumentException("localResult must be a string.");
        }
        validateTimestamp(message.get("timestamp").getAsString(), source);
        if (storeNonce && !nonceStore.addNonce(message.get("nonce").getAsString())) {
            logSecurity("REPLAY_REJECTED", source, "replayed nonce");
            throw new IllegalArgumentException("Replay rejected.");
        }
        if (!verifyMessage(message, signer)) {
            logSecurity("HMAC_REJECTED", source, "invalid HMAC");
            throw new IllegalArgumentException("Invalid HMAC.");
        }
    }

    private void validateTimestamp(String rawTimestamp, String source) {
        try {
            long timestamp = Long.parseLong(rawTimestamp);
            long age = Math.abs(Instant.now().getEpochSecond() - timestamp);
            if (age > MAX_MESSAGE_AGE_SECONDS) {
                logSecurity("TIMESTAMP_REJECTED", source, "timestamp older than 60 seconds");
                throw new IllegalArgumentException("Timestamp rejected.");
            }
        } catch (NumberFormatException e) {
            logSecurity("TIMESTAMP_REJECTED", source, "timestamp not numeric");
            throw new IllegalArgumentException("Timestamp rejected.");
        }
    }

    /**
     * Builds a signed recommender protocol message for clients and peer nodes.
     *
     * @param type recommender message type
     * @param spaceId numeric parking space number
     * @param correlationId request correlation identifier
     * @param nodeId sender node identity
     * @param signer HMAC-SHA256 signer
     * @return a signed JSON message
     */
    public static JsonObject createSignedRequest(String type, String spaceId, String correlationId,
                                                 String nodeId, SecureMessageSigner signer) {
        JsonObject request = createSignedMessage(type, spaceId, correlationId, nodeId);
        signMessage(request, signer);
        return request;
    }

    private static JsonObject createSignedMessage(String type, String spaceId, String correlationId, String nodeId) {
        JsonObject request = new JsonObject();
        request.addProperty("type", ValidationUtils.requireValidMessageType(type, "type"));
        request.addProperty("spaceId", requireValidRecommenderSpace(spaceId));
        request.addProperty("correlationId", ValidationUtils.requireValidUuid(correlationId, "correlationId"));
        request.addProperty("timestamp", String.valueOf(Instant.now().getEpochSecond()));
        request.addProperty("nonce", UUID.randomUUID().toString());
        request.addProperty("nodeId", ValidationUtils.requireValidMessageType(nodeId, "nodeId"));
        return request;
    }

    private static void signMessage(JsonObject message, SecureMessageSigner signer) {
        message.remove("hmac");
        message.addProperty("hmac", signer.sign(canonicalSigningContent(message)));
    }

    private static boolean verifyMessage(JsonObject message, SecureMessageSigner signer) {
        String hmac = message.get("hmac").getAsString();
        return signer.verify(canonicalSigningContent(message), hmac);
    }

    private static String canonicalSigningContent(JsonObject message) {
        Map<String, String> fields = new LinkedHashMap<>();
        SIGNED_FIELDS.stream().sorted().forEach(field -> {
            if (message.has(field)) {
                fields.put(field, message.get(field).getAsString());
            }
        });
        StringBuilder canonical = new StringBuilder();
        fields.forEach((key, value) -> canonical.append(key).append('=').append(value).append('\n'));
        return canonical.toString();
    }

    private SSLSocket openTlsSocket(String host, int targetPort) throws IOException {
        SSLSocketFactory factory = clientSslContext.getSocketFactory();
        SSLSocket socket = (SSLSocket) factory.createSocket();
        socket.setEnabledProtocols(enabledTlsProtocols(socket.getSupportedProtocols()));
        socket.connect(new InetSocketAddress(host, targetPort), TIMEOUT_MS);
        socket.startHandshake();
        return socket;
    }

    /**
     * Serializes a list of recommendation results into a sorted comma-separated string.
     *
     * @param results list of results
     * @return serialized string, for example "3;1, Space 4;2"
     */
    public static String serializeResults(List<RecommendationResult> results) {
        if (results == null || results.isEmpty()) {
            return "";
        }
        List<RecommendationResult> sorted = new ArrayList<>(results);
        sorted.sort(Comparator.comparingInt(r -> parseSpaceNumber(r.spaceId())));
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < sorted.size(); i++) {
            RecommendationResult r = sorted.get(i);
            sb.append(r.spaceId()).append(";").append(r.citationCount());
            if (i < sorted.size() - 1) {
                sb.append(", Space ");
            }
        }
        return sb.toString();
    }

    private void sendSignedFailure(PrintWriter writer, String type, String correlationId, String reason) {
        JsonObject response = createSignedMessage(type, "1", correlationId, nodeId);
        response.addProperty("status", "FAILURE");
        response.addProperty("reason", reason);
        signMessage(response, signer);
        writer.println(response);
    }

    /**
     * Configures the malicious mode at runtime.
     *
     * @param malicious true to enable malicious faked responses
     * @return no return value
     */
    public void setMalicious(boolean malicious) {
        this.isMalicious = malicious;
        logger.info("Node '" + nodeId + "' set malicious=" + malicious);
    }

    /**
     * Checks if the node is working in malicious mode.
     *
     * @return true if malicious mode is active
     */
    public boolean isMalicious() {
        return isMalicious;
    }

    /**
     * Validates if the malicious payload format matches the expected pattern.
     *
     * @param payload the payload to validate
     * @return true if the format is valid, false otherwise
     */
    public static boolean isValidMaliciousPayload(String payload) {
        if (payload == null) {
            return false;
        }
        String clean = payload.trim();
        if (clean.equals("NONE")) {
            return true;
        }
        if (clean.startsWith("Space ")) {
            clean = clean.substring(6).trim();
        }
        return clean.matches("\\d+;\\d+(,\\s*(Space\\s*)?\\d+;\\d+)*");
    }

    /**
     * Sets the custom malicious payload to return.
     *
     * @param payload the fake result payload, e.g. "999;999" or "Space 999;999"
     */
    public void setMaliciousPayload(String payload) {
        if (payload != null && !payload.trim().isEmpty()) {
            String trimmed = payload.trim();
            if (!isValidMaliciousPayload(trimmed)) {
                throw new IllegalArgumentException("Invalid payload format. Must be 'NONE' or match space pattern (e.g. '999;999' or 'Space 999;999').");
            }
            this.maliciousPayload = trimmed;
            logger.info("Node '" + nodeId + "' set maliciousPayload=" + this.maliciousPayload);
        }
    }

    /**
     * Gets the custom malicious payload.
     *
     * @return the current fake result payload
     */
    public String getMaliciousPayload() {
        return maliciousPayload;
    }

    /**
     * Closes the server socket, nonce store, and worker threads.
     *
     * @return no return value
     */
    @Override
    public void close() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {
            }
        }
        nonceStore.close();
        if (executorService != null) {
            executorService.shutdown();
            try {
                if (!executorService.awaitTermination(3, TimeUnit.SECONDS)) {
                    executorService.shutdownNow();
                }
            } catch (InterruptedException e) {
                executorService.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    private static JsonObject parseJsonObject(String line) {
        try {
            if (!JsonParser.parseString(line).isJsonObject()) {
                throw new IllegalArgumentException("Message must be a JSON object.");
            }
            return JsonParser.parseString(line).getAsJsonObject();
        } catch (Exception e) {
            throw new IllegalArgumentException("Malformed recommender message.", e);
        }
    }

    private static void requireString(JsonObject object, String fieldName) {
        if (!object.has(fieldName) || !object.get(fieldName).isJsonPrimitive()
                || !object.get(fieldName).getAsJsonPrimitive().isString()) {
            throw new IllegalArgumentException(fieldName + " is required.");
        }
    }

    private static String requireValidRecommenderSpace(String spaceId) {
        String safe = ValidationUtils.requireNonEmpty(spaceId, "spaceId");
        if (!safe.matches("\\d+")) {
            throw new IllegalArgumentException("Parking space number must be numeric.");
        }
        int parsed = parseSpaceNumber(safe);
        if (parsed < 1 || parsed > MAX_SPACE_NUMBER) {
            throw new IllegalArgumentException("Parking space number is out of range.");
        }
        return String.valueOf(parsed);
    }

    private static int parseSpaceNumber(String spaceId) {
        if (spaceId == null || !spaceId.matches("\\d+")) {
            return -1;
        }
        try {
            return Integer.parseInt(spaceId);
        } catch (Exception e) {
            return -1;
        }
    }

    private static String[] enabledTlsProtocols(String[] supportedProtocols) {
        List<String> enabled = new ArrayList<>();
        for (String protocol : supportedProtocols) {
            if ("TLSv1.3".equals(protocol) || "TLSv1.2".equals(protocol)) {
                enabled.add(protocol);
            }
        }
        if (enabled.isEmpty()) {
            throw new IllegalStateException("TLS 1.2 or newer is required.");
        }
        return enabled.toArray(String[]::new);
    }

    private static List<NodeEndpoint> parseClusterNodes(List<String> rawNodes) {
        if (rawNodes == null || rawNodes.isEmpty()) {
            return Collections.emptyList();
        }
        List<NodeEndpoint> endpoints = new ArrayList<>();
        int index = 1;
        for (String rawNode : rawNodes) {
            String nodeText = ValidationUtils.requireNonEmpty(rawNode, "clusterNode");
            String explicitId = null;
            String address = nodeText;
            if (nodeText.contains("=")) {
                String[] idParts = nodeText.split("=", 2);
                explicitId = ValidationUtils.requireValidMessageType(idParts[0].trim(), "clusterNodeId");
                address = idParts[1].trim();
            }
            String[] addressParts = address.split(":");
            if (addressParts.length != 2) {
                throw new IllegalArgumentException("Invalid recommender node entry: " + rawNode);
            }
            String host = ValidationUtils.requireNonEmpty(addressParts[0], "clusterNodeHost");
            int nodePort = Integer.parseInt(addressParts[1]);
            String resolvedId = explicitId != null ? explicitId : inferNodeId(host, index);
            endpoints.add(new NodeEndpoint(resolvedId, host, nodePort));
            index++;
        }
        return List.copyOf(endpoints);
    }

    private static String inferNodeId(String host, int index) {
        String normalized = host == null ? "" : host.trim();
        if (normalized.matches("recommender\\d+")) {
            return normalized;
        }
        return "recommender" + index;
    }

    private void logSecurity(String event, String source, String reason) {
        SecurityLogger.logSecurityEvent("event=" + event
                + " timestamp=" + Instant.now()
                + " source=" + source
                + " receiver=" + nodeId
                + " reason=" + reason);
    }

    private String readBoundedLine(BufferedReader reader, int maxChars) throws IOException {
        StringBuilder sb = new StringBuilder();
        int ch;
        while ((ch = reader.read()) != -1) {
            if (ch == '\n') {
                break;
            }
            if (ch == '\r') {
                continue;
            }
            sb.append((char) ch);
            if (sb.length() > maxChars) {
                throw new IllegalArgumentException("Payload size limit exceeded to prevent denial of service.");
            }
        }
        return sb.length() == 0 && ch == -1 ? null : sb.toString();
    }

    /**
     * Returns the remote address string for a socket, or {@code "unknown"} when the
     * socket or its address is {@code null}.
     *
     * @param socket the socket whose remote address is needed
     * @return the remote socket address as a string
     */
    private static String remoteAddress(Socket socket) {
        if (socket == null || socket.getRemoteSocketAddress() == null) {
            return "unknown";
        }
        return socket.getRemoteSocketAddress().toString();
    }

    /**
     * Represents a peer recommender node in the cluster, identified by its logical
     * node ID, hostname, and TCP port.
     *
     * @param nodeId the logical node identifier
     * @param host   the hostname or IP address
     * @param port   the TCP port
     */
    private record NodeEndpoint(String nodeId, String host, int port) {
    }

    /**
     * Holds a candidate parking space and its citation count for ranking during
     * the recommendation algorithm.
     */
    private static class SpaceCandidate {
        final String spaceId;
        final long citationCount;

        SpaceCandidate(String spaceId, long citationCount) {
            this.spaceId = spaceId;
            this.citationCount = citationCount;
        }
    }
}
