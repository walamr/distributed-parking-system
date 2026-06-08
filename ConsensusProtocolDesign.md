# Consensus Protocol Design Document

## 1. Overview
The **Parking Recommender System** is built as a fault-tolerant, high-availability cluster consisting of three independent nodes:
- `Recommender1` (Leader / Coordinator): Port `8091`
- `Recommender2` (Follower): Port `8092`
- `Recommender3` (Follower): Port `8093`

To protect against database discrepancies, single-node failure, and malicious/compromised nodes, the system implements a TLS socket-based **Consensus Protocol** with majority voting. Recommender listeners use `SSLServerSocket`, clients and peers use `SSLSocket`, and TLS 1.2 or newer is required. Server-to-server collection/forwarding requires client certificates from the configured keystore/truststore. Every protocol JSON message is also signed with HMAC-SHA256 and includes a timestamp, nonce, sender node identity, and correlation ID.

---

## 2. Protocol Messaging Sequence

The workflow for recommendation requests and cluster coordination is structured as follows:

```mermaid
sequenceDiagram
    autonumber
    actor Customer as Customer App
    participant F as Follower Node (e.g. Server2)
    participant L as Leader Node (Server1)
    participant F3 as Follower Node (Server3)
    
    Customer->>F: signed TLS CLIENT_QUERY (spaceId: "3")
    Note over F: Calculates local result (normal/malicious)
    F->>L: signed mTLS FORWARD_QUERY (spaceId: "3", localResult)
    Note over L: Calculates local result (normal/malicious)
    L->>F3: signed mTLS COLLECT_REQUEST (spaceId: "3")
    F3-->>L: signed mTLS COLLECT_RESPONSE (localResult)
    Note over L: Executes Majority Vote on all lists
    L-->>F: signed CLIENT_RESPONSE (consensusList or FAILURE)
    F-->>Customer: signed CLIENT_RESPONSE (consensusList or FAILURE)
```

### Protocol Message Details (JSON Schema)

1. **`CLIENT_QUERY`** (Client to Cluster Node)
   - Initiated by the Customer GUI or CLI to request a recommendation.
   - Example:
     ```json
     {
       "type": "CLIENT_QUERY",
       "spaceId": "3",
       "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
       "timestamp": "1780915200",
       "nonce": "0c932f45-d74f-47f1-af5e-8bf2c385da33",
       "nodeId": "customer-ui",
       "hmac": "..."
     }
     ```

2. **`FORWARD_QUERY`** (Follower to Leader)
   - Sent by a follower to forward the client's request along with the follower's own pre-calculated result.
   - Example:
     ```json
     {
       "type": "FORWARD_QUERY",
       "spaceId": "3",
       "localResult": "3;0",
       "nodeId": "recommender2",
       "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
       "timestamp": "1780915200",
       "nonce": "c9b5140e-0945-47c0-821b-6b865e6797e4",
       "hmac": "..."
     }
     ```

3. **`COLLECT_REQUEST`** (Leader to Follower)
   - Sent by the leader to collect local recommendations from other followers.
   - Example:
     ```json
     {
       "type": "COLLECT_REQUEST",
       "spaceId": "3",
       "correlationId": "c0bd7acc-4eee-40cc-80af-2f6e2ae44473",
       "timestamp": "1780915200",
       "nonce": "2d5ed27c-9768-4b5e-bcdb-1f8f83a6b373",
       "nodeId": "recommender1",
       "hmac": "..."
     }
     ```

4. **`COLLECT_RESPONSE`** (Follower to Leader)
   - Sent by a follower in response to a `COLLECT_REQUEST`.
   - Example:
     ```json
     {
       "type": "COLLECT_RESPONSE",
       "nodeId": "recommender3",
       "spaceId": "3",
       "correlationId": "c0bd7acc-4eee-40cc-80af-2f6e2ae44473",
       "timestamp": "1780915200",
       "nonce": "2b3d5f63-6b72-4306-9865-c16dccbf4e7d",
       "localResult": "3;0",
       "hmac": "..."
     }
     ```

5. **`CLIENT_RESPONSE`** (Leader/Follower to Client)
   - The final response containing the consensus calculation result or error details.
   - Example (Success):
     ```json
     {
       "status": "SUCCESS",
       "spaceId": "3",
       "correlationId": "f47ac10b-58cc-4372-a567-0e02b2c3d479",
       "timestamp": "1780915200",
       "nonce": "7898ac8a-87e5-420e-a095-8cb52f15ad75",
       "nodeId": "recommender1",
       "result": "3;0",
       "hmac": "..."
     }
     ```
   - Example (Failure):
     ```json
     {
       "status": "FAILURE",
       "reason": "No majority consensus reached in cluster."
     }
     ```

---

## 3. Consensus & Majority Voting Algorithm

For a recommendation list to be accepted by the cluster, it must achieve a **majority vote**. 
A majority is defined mathematically as:
$$\text{Threshold} = \lfloor \frac{N}{2} \rfloor + 1$$

For $N=3$ cluster nodes, the consensus threshold is **2 nodes**.

### Deterministic Result Serialization
To ensure that list results can be compared as simple strings, each node sorts its recommendation results by space ID numerically before serializing them into a comma-separated format.
For example, if a node recommends spaces `4` (with `2` citations) and `3` (with `1` citation), the serialized list is sorted and formatted deterministically as:
`3;1, Space 4;2`

The leader compares the complete serialized list. It does not accept two votes as matching just because their first recommended space is the same.

### Fault Tolerance & Resiliency
1. **Offline Resiliency**: If one follower node is offline or fails to respond within the `TIMEOUT_MS` window, the leader can still proceed. Since it has its own vote and the querying node's vote (totaling 2 votes), it can successfully reach a majority of 2 and return a valid result.
2. **Malicious Protection**: If one node is compromised and running in **Malicious mode** (injecting falsified recommendations, e.g. `999;999`), its vote will not match the valid votes computed by the other two normal nodes. The leader's majority voter will discard the outlier vote and successfully return the valid consensus recommendation list.
3. **No Consensus Safety**: If more than one node is compromised or offline, or if the cluster is partitioned such that no 2 nodes compute the same result, the leader safety aborts and returns a `FAILURE` status, ensuring incorrect information is never returned to the customer.
4. **Explicit Node Identity**: Cluster nodes are parsed as explicit `nodeId=host:port` entries when provided, or inferred from service names such as `recommender2`. This avoids the old port-derived `recommender-8092` mismatch and prevents the leader from skipping or contacting the wrong node.
5. **Security Rejections**: Missing HMAC fields, invalid HMAC values, replayed nonces, timestamps older than 60 seconds, malformed JSON, unsupported fields, and non-numeric or out-of-range spaces are rejected server-side and logged with source, receiver node identity, timestamp, and reason.
