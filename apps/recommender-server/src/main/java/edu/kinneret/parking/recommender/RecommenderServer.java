package edu.kinneret.parking.recommender;

import edu.kinneret.parking.common.AppConfig;
import edu.kinneret.parking.common.ParkingRepository;
import edu.kinneret.parking.common.ValidationUtils;
import edu.kinneret.parking.common.RabbitMqConnectionManager;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.bson.Document;

import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Recommender server node that implements the consensus protocol and generates
 * recommended parking space lists.
 */
public class RecommenderServer implements AutoCloseable {
    private static final Logger logger = Logger.getLogger(RecommenderServer.class.getName());
    private static final int TIMEOUT_MS = 2000;

    private final String nodeId;
    private final int port;
    private final String leaderHost;
    private final int leaderPort;
    private final boolean isLeader;
    private volatile boolean isMalicious;
    private final List<String> clusterNodes; // e.g. ["localhost:8091", "localhost:8092", "localhost:8093"]
    private final AppConfig appConfig;

    private ServerSocket serverSocket;
    private ExecutorService executorService;
    private volatile boolean running = true;

    /**
     * Creates a recommender server node.
     *
     * @param nodeId       unique identifier for this node
     * @param port         port to listen on
     * @param leaderHost   host address of the cluster leader
     * @param leaderPort   port of the cluster leader
     * @param isLeader     flag indicating if this node is the leader
     * @param isMalicious  flag indicating if this node should behave maliciously
     * @param clusterNodes list of all nodes in the cluster
     * @param appConfig    application configuration for DB access
     */
    public RecommenderServer(String nodeId, int port, String leaderHost, int leaderPort,
                             boolean isLeader, boolean isMalicious, List<String> clusterNodes,
                             AppConfig appConfig) {
        this.nodeId = nodeId;
        this.port = port;
        this.leaderHost = leaderHost;
        this.leaderPort = leaderPort;
        this.isLeader = isLeader;
        this.isMalicious = isMalicious;
        this.clusterNodes = new ArrayList<>(clusterNodes);
        this.appConfig = appConfig;
    }

    /**
     * Starts the socket server and listens for incoming connections.
     *
     * @throws IOException if the server socket cannot be opened
     */
    public void start() throws IOException {
        serverSocket = new ServerSocket(port);
        executorService = Executors.newCachedThreadPool(runnable -> {
            Thread t = new Thread(runnable, "recommender-worker-" + nodeId);
            t.setDaemon(true);
            return t;
        });

        // Verify access to RabbitMQ server (TCP/AMQP connection as per physical architecture)
        try {
            RabbitMqConnectionManager rabbitManager = new RabbitMqConnectionManager(appConfig);
            if (rabbitManager.checkHealth()) {
                logger.info("Recommender node '" + nodeId + "' successfully verified connection to RabbitMQ server.");
            } else {
                logger.warning("Recommender node '" + nodeId + "' could not reach RabbitMQ server.");
            }
        } catch (Exception e) {
            logger.warning("Recommender node '" + nodeId + "' failed to verify RabbitMQ connection: " + e.getMessage());
        }

        logger.info("Recommender node '" + nodeId + "' started on port " + port 
                + " [Leader: " + isLeader + ", Malicious: " + isMalicious + "]");

        executorService.submit(this::listen);
    }

    private void listen() {
        while (running) {
            try {
                Socket clientSocket = serverSocket.accept();
                executorService.submit(() -> handleConnection(clientSocket));
            } catch (IOException e) {
                if (!running) break;
                logger.log(Level.WARNING, "Error accepting connection on node " + nodeId, e);
            }
        }
    }

    private void handleConnection(Socket socket) {
        try (Socket s = socket;
             BufferedReader reader = new BufferedReader(new InputStreamReader(s.getInputStream()));
             PrintWriter writer = new PrintWriter(s.getOutputStream(), true)) {

            String line = reader.readLine();
            if (line == null || line.isBlank()) return;

            JsonObject request = JsonParser.parseString(line).getAsJsonObject();
            String type = request.get("type").getAsString();
            String spaceId = request.get("spaceId").getAsString();
            String correlationId = request.has("correlationId") ? request.get("correlationId").getAsString() : UUID.randomUUID().toString();

            logger.fine("[" + nodeId + "] Received " + type + " for space " + spaceId);

            switch (type) {
                case "CLIENT_QUERY":
                    handleClientQuery(spaceId, correlationId, writer);
                    break;
                case "FORWARD_QUERY":
                    handleForwardQuery(request, writer);
                    break;
                case "COLLECT_REQUEST":
                    handleCollectRequest(spaceId, writer);
                    break;
                default:
                    logger.warning("Unknown message type: " + type);
            }
        } catch (Exception e) {
            logger.log(Level.SEVERE, "Exception handling connection on node " + nodeId, e);
        }
    }

    private void handleClientQuery(String spaceId, String correlationId, PrintWriter clientWriter) {
        try {
            if (isLeader) {
                // Leader consensus directly
                String consensus = executeLeaderConsensus(spaceId);
                JsonObject response = new JsonObject();
                if (consensus != null) {
                    response.addProperty("status", "SUCCESS");
                    response.addProperty("result", consensus);
                } else {
                    response.addProperty("status", "FAILURE");
                    response.addProperty("reason", "No majority consensus reached in cluster.");
                }
                clientWriter.println(response.toString());
            } else {
                // Follower: calculate local result, then forward to leader
                String localResult = calculateLocalRecommendation(spaceId);
                JsonObject forwardRequest = new JsonObject();
                forwardRequest.addProperty("type", "FORWARD_QUERY");
                forwardRequest.addProperty("spaceId", spaceId);
                forwardRequest.addProperty("localResult", localResult);
                forwardRequest.addProperty("nodeId", nodeId);
                forwardRequest.addProperty("correlationId", correlationId);

                try (Socket leaderSocket = new Socket()) {
                    leaderSocket.connect(new InetSocketAddress(leaderHost, leaderPort), TIMEOUT_MS);
                    try (PrintWriter leaderWriter = new PrintWriter(leaderSocket.getOutputStream(), true);
                         BufferedReader leaderReader = new BufferedReader(new InputStreamReader(leaderSocket.getInputStream()))) {

                        leaderWriter.println(forwardRequest.toString());
                        String reply = leaderReader.readLine();
                        if (reply != null) {
                            clientWriter.println(reply);
                        } else {
                            sendClientFailure(clientWriter, "No response from leader.");
                        }
                    }
                } catch (Exception e) {
                    logger.warning("Node '" + nodeId + "' failed to reach leader at " + leaderHost + ":" + leaderPort);
                    sendClientFailure(clientWriter, "Failed to reach cluster leader.");
                }
            }
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error in handleClientQuery on node " + nodeId, e);
            sendClientFailure(clientWriter, "Internal recommender error: " + e.getMessage());
        }
    }

    private void handleForwardQuery(JsonObject forwardRequest, PrintWriter leaderWriter) {
        if (!isLeader) {
            sendClientFailure(leaderWriter, "Non-leader node cannot handle FORWARD_QUERY.");
            return;
        }

        try {
            String spaceId = forwardRequest.get("spaceId").getAsString();
            String followerResult = forwardRequest.get("localResult").getAsString();
            String followerId = forwardRequest.get("nodeId").getAsString();

            // Run consensus, incorporating the follower's pre-calculated vote!
            Map<String, String> explicitVotes = new HashMap<>();
            explicitVotes.put(followerId, followerResult);

            String consensus = executeLeaderConsensus(spaceId, explicitVotes);
            JsonObject response = new JsonObject();
            if (consensus != null) {
                response.addProperty("status", "SUCCESS");
                response.addProperty("result", consensus);
            } else {
                response.addProperty("status", "FAILURE");
                response.addProperty("reason", "No majority consensus reached in cluster.");
            }
            leaderWriter.println(response.toString());
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error in handleForwardQuery on leader", e);
            sendClientFailure(leaderWriter, "Leader internal error: " + e.getMessage());
        }
    }

    private void handleCollectRequest(String spaceId, PrintWriter writer) {
        try {
            String localResult = calculateLocalRecommendation(spaceId);
            JsonObject response = new JsonObject();
            response.addProperty("type", "COLLECT_RESPONSE");
            response.addProperty("nodeId", nodeId);
            response.addProperty("localResult", localResult);
            writer.println(response.toString());
        } catch (Exception e) {
            logger.log(Level.WARNING, "Error in handleCollectRequest on follower " + nodeId, e);
            JsonObject response = new JsonObject();
            response.addProperty("type", "COLLECT_RESPONSE");
            response.addProperty("nodeId", nodeId);
            response.addProperty("localResult", ""); // empty indicates failure/error
            writer.println(response.toString());
        }
    }

    private String executeLeaderConsensus(String spaceId) {
        return executeLeaderConsensus(spaceId, Collections.emptyMap());
    }

    private String executeLeaderConsensus(String spaceId, Map<String, String> explicitVotes) {
        Map<String, String> votes = new ConcurrentHashMap<>();
        
        // 1. Calculate leader's own local result
        String leaderResult = calculateLocalRecommendation(spaceId);
        votes.put(nodeId, leaderResult);

        // 2. Put any explicit votes already received (e.g. from forwarding follower)
        votes.putAll(explicitVotes);

        // 3. For all other cluster nodes that we haven't got votes from, query them via COLLECT_REQUEST
        List<Future<Void>> futures = new ArrayList<>();
        ExecutorService collectExecutor = Executors.newCachedThreadPool();

        for (String nodeAddr : clusterNodes) {
            String[] parts = nodeAddr.split(":");
            String host = parts[0];
            int p = Integer.parseInt(parts[1]);

            // Skip contacting ourself
            if (host.equalsIgnoreCase("localhost") || host.equalsIgnoreCase("127.0.0.1")) {
                if (p == this.port) continue;
            }

            // Find node ID corresponding to address (or infer from port/list)
            String targetNodeId = "recommender-" + p;
            if (votes.containsKey(targetNodeId)) {
                continue; // Already have a vote (e.g., forwarded query)
            }

            futures.add(collectExecutor.submit(() -> {
                try (Socket collectSocket = new Socket()) {
                    collectSocket.connect(new InetSocketAddress(host, p), TIMEOUT_MS);
                    try (PrintWriter collectWriter = new PrintWriter(collectSocket.getOutputStream(), true);
                         BufferedReader collectReader = new BufferedReader(new InputStreamReader(collectSocket.getInputStream()))) {

                        JsonObject collectRequest = new JsonObject();
                        collectRequest.addProperty("type", "COLLECT_REQUEST");
                        collectRequest.addProperty("spaceId", spaceId);

                        collectWriter.println(collectRequest.toString());
                        String reply = collectReader.readLine();
                        if (reply != null) {
                            JsonObject response = JsonParser.parseString(reply).getAsJsonObject();
                            String val = response.get("localResult").getAsString();
                            String senderId = response.get("nodeId").getAsString();
                            if (!val.isBlank()) {
                                votes.put(senderId, val);
                            }
                        }
                    }
                } catch (Exception e) {
                    logger.warning("Leader node '" + nodeId + "' failed to collect result from " + host + ":" + p);
                }
                return null;
            }));
        }

        // Wait for collect tasks to complete or timeout
        for (Future<Void> fut : futures) {
            try {
                fut.get(TIMEOUT_MS + 200, TimeUnit.MILLISECONDS);
            } catch (Exception e) {
                // Ignore timeouts and keep going with collected votes
            }
        }
        collectExecutor.shutdown();

        // 4. Perform majority voting
        int totalConfiguredNodes = clusterNodes.isEmpty() ? 3 : clusterNodes.size();
        int majorityThreshold = (totalConfiguredNodes / 2) + 1;

        logger.info("Consensus votes collected: " + votes + " (Threshold: " + majorityThreshold + ")");

        Map<String, Integer> counts = new HashMap<>();
        for (String vote : votes.values()) {
            if (vote != null && !vote.isBlank()) {
                counts.put(vote, counts.getOrDefault(vote, 0) + 1);
            }
        }

        String consensus = null;
        int maxVotes = 0;
        for (Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > maxVotes) {
                maxVotes = entry.getValue();
                consensus = entry.getKey();
            }
        }

        if (maxVotes >= majorityThreshold) {
            logger.info("Consensus REACHED: '" + consensus + "' with " + maxVotes + " votes.");
            return consensus;
        } else {
            logger.warning("Consensus FAILED. Max agreement: " + maxVotes + " votes. Consensus required: " + majorityThreshold);
            return null;
        }
    }

    /**
     * Calculates the local recommendation.
     *
     * @param desiredSpaceId the requested space number/ID
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
     * @param desiredSpaceId the requested space number/ID
     * @param repository     the repository instance to use
     * @return the formatted recommendation string
     */
    public String calculateLocalRecommendation(String desiredSpaceId, ParkingRepository repository) {
        if (isMalicious) {
            // Malicious mode: return faked space and high citation count
            return "Request: Space " + desiredSpaceId + "\nResult: Space 999;999";
        }

        // Validate space ID format
        try {
            ValidationUtils.requireValidSpaceId(desiredSpaceId);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid parking space format.");
        }

        try {
            // Check if space is registered
            if (!repository.isSpaceRegistered(desiredSpaceId)) {
                throw new IllegalArgumentException("Parking space is not registered in the system.");
            }

            // Find the zone name for the desired space
            String zoneName = repository.getSpaceZone(desiredSpaceId);
            if ("Unknown".equalsIgnoreCase(zoneName)) {
                throw new IllegalArgumentException("Zone could not be identified for space " + desiredSpaceId);
            }

            // Calculate citations for the desired space regardless of whether it is occupied
            long desiredSpaceCitations = 0;
            if (ParkingRepository.isDbOnline && repository.getDatabase() != null) {
                desiredSpaceCitations = repository.getDatabase().getCollection("citations")
                        .countDocuments(com.mongodb.client.model.Filters.or(
                                com.mongodb.client.model.Filters.eq("payload.spaceId", desiredSpaceId),
                                com.mongodb.client.model.Filters.eq("spaceId", desiredSpaceId)
                        ));
            }
            String requestedPart = "Request: Space " + desiredSpaceId;

            // Fetch spaces in the same zone from DB (SUC 8: limit candidates to target parking zone)
            List<Document> allSpacesInZone = new ArrayList<>();
            if (ParkingRepository.isDbOnline && repository.getDatabase() != null) {
                repository.getDatabase().getCollection("spaces")
                        .find(com.mongodb.client.model.Filters.eq("zoneName", zoneName))
                        .into(allSpacesInZone);
            } else {
                // Testing fallback: if database client is not initialized, generate all 100 spaces in-memory
                for (int i = 1; i <= 100; i++) {
                    allSpacesInZone.add(new Document("spaceId", String.valueOf(i)));
                }
            }

            // Evaluate availability and count citations for each space candidate
            List<SpaceCandidate> candidates = new ArrayList<>();
            for (Document spaceDoc : allSpacesInZone) {
                String spaceId = spaceDoc.getString("spaceId");

                // In-memory zone filtering when DB is offline (fallback)
                if (!ParkingRepository.isDbOnline || repository.getDatabase() == null) {
                    String candidateZone = repository.getSpaceZone(spaceId);
                    if (!zoneName.equalsIgnoreCase(candidateZone)) {
                        continue;
                    }
                }

                // Availability check (latest transaction action is not "start")
                boolean isAvailable = true;
                Document lastTx = repository.getLatestTransactionForSpace(spaceId);
                if (lastTx != null) {
                    String action = ParkingRepository.readTransactionAction(lastTx);
                    if ("start".equalsIgnoreCase(action)) {
                        isAvailable = false;
                    }
                }

                if (!isAvailable) {
                    continue; // Skip occupied space
                }

                // Citation count
                long citationCount = 0;
                if (ParkingRepository.isDbOnline && repository.getDatabase() != null) {
                    citationCount = repository.getDatabase().getCollection("citations")
                            .countDocuments(com.mongodb.client.model.Filters.or(
                                    com.mongodb.client.model.Filters.eq("payload.spaceId", spaceId),
                                    com.mongodb.client.model.Filters.eq("spaceId", spaceId)
                            ));
                }

                candidates.add(new SpaceCandidate(spaceId, citationCount));
            }

            // Branch C: No spaces available
            if (candidates.isEmpty()) {
                return requestedPart + "\nResult: NONE";
            }

            // Find the minimum citation count among all available spaces
            long minCitations = Long.MAX_VALUE;
            for (SpaceCandidate c : candidates) {
                if (c.citationCount < minCitations) {
                    minCitations = c.citationCount;
                }
            }

            // Filter down to only spaces that have the minimum citations
            List<SpaceCandidate> minCitationCandidates = new ArrayList<>();
            for (SpaceCandidate c : candidates) {
                if (c.citationCount == minCitations) {
                    minCitationCandidates.add(c);
                }
            }

            int desiredNum = parseSpaceNumber(desiredSpaceId);
            List<RecommendationResult> results = new ArrayList<>();

            if (desiredNum != -1) {
                int minDistance = Integer.MAX_VALUE;
                List<SpaceCandidate> closestCandidates = new ArrayList<>();

                for (SpaceCandidate c : minCitationCandidates) {
                    int candidateNum = parseSpaceNumber(c.spaceId);
                    if (candidateNum != -1) {
                        int dist = Math.abs(candidateNum - desiredNum);
                        if (dist < minDistance) {
                            minDistance = dist;
                            closestCandidates.clear();
                            closestCandidates.add(c);
                        } else if (dist == minDistance) {
                            closestCandidates.add(c);
                        }
                    }
                }

                for (SpaceCandidate c : closestCandidates) {
                    results.add(new RecommendationResult(c.spaceId, c.citationCount));
                }
            } else {
                for (SpaceCandidate c : minCitationCandidates) {
                    results.add(new RecommendationResult(c.spaceId, c.citationCount));
                }
            }
            String recommendedPart = "Space " + serializeResults(results);

            return requestedPart + "\nResult: " + recommendedPart;
        } catch (Exception e) {
            logger.log(Level.WARNING, "Database lookup failed for recommendation", e);
            throw new RuntimeException("Recommender DB error: " + e.getMessage(), e);
        }
    }

    private int parseSpaceNumber(String spaceId) {
        if (spaceId == null) return -1;
        String digits = spaceId.replaceAll("[^\\d]", "");
        if (digits.isEmpty()) return -1;
        try {
            return Integer.parseInt(digits);
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Serializes a list of recommendation results into a sorted comma-separated string.
     * Sorts by space ID numerically to ensure deterministic comparison.
     *
     * @param results list of results
     * @return serialized string (e.g. "3;1, 4;2")
     */
    public static String serializeResults(List<RecommendationResult> results) {
        if (results == null || results.isEmpty()) return "";

        List<RecommendationResult> sorted = new ArrayList<>(results);
        sorted.sort((r1, r2) -> {
            try {
                int n1 = Integer.parseInt(r1.spaceId().replaceAll("[^\\d]", ""));
                int n2 = Integer.parseInt(r2.spaceId().replaceAll("[^\\d]", ""));
                return Integer.compare(n1, n2);
            } catch (Exception e) {
                return r1.spaceId().compareTo(r2.spaceId());
            }
        });

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

    private void sendClientFailure(PrintWriter writer, String reason) {
        JsonObject response = new JsonObject();
        response.addProperty("status", "FAILURE");
        response.addProperty("reason", reason);
        writer.println(response.toString());
    }

    /**
     * Configures the malicious mode at runtime.
     *
     * @param malicious true to enable malicious faked responses
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
     * Closes the server socket and shuts down worker threads.
     */
    @Override
    public void close() {
        running = false;
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
        }
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

    private static class SpaceCandidate {
        final String spaceId;
        final long citationCount;

        SpaceCandidate(String spaceId, long citationCount) {
            this.spaceId = spaceId;
            this.citationCount = citationCount;
        }
    }
}
