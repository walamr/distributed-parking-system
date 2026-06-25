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

---

### Worked Examples (Assignment Section 4.1)

The cluster is 3 nodes, so the majority threshold is 2. The table below maps every example from the assignment to the algorithm's decision. These are exactly the cases covered by the automated tests in `RecommenderServerTest` (see [Testing.md](Testing.md)).

| # | Server1 | Server2 | Server3 | Decision | Why |
| :- | :- | :- | :- | :- | :- |
| 4.1.1 All agree | `3;1` | `3;1` | `3;1` | **`3;1`** | 3 identical lists ≥ 2 |
| 4.1.2 Majority agree | `3;1` | `4;1` | `3;1` | **`3;1`** | `3;1` has 2 votes ≥ 2 |
| 4.1.3 No agreement | `3;1` | `4;1` | `5;1` | **FAILURE** | every list distinct, max 1 vote |
| 4.1.4 No agreement (lists) | `3;1, 4;1` | `4;1` | `4;1, 5;1` | **FAILURE** | full lists compared; no list reaches 2 |
| 4.1.5 No agreement (lists) | `3;1` | `3;1, 5;1` | `5;1` | **FAILURE** | three distinct full lists |
| 4.1.6 Missing response | `3;1` | (no response) | `3;1` | **`3;1`** | 2 votes agree despite 1 missing |
| 4.1.7 Missing responses | `3;1` | (no response) | (no response) | **FAILURE** | only 1 vote, below threshold |
| 4.1.8 Missing + disagree | `3;1` | `4;1` | (no response) | **FAILURE** | two present votes differ, no majority |

The key rule demonstrated by 4.1.4 and 4.1.5 is that the leader compares the **complete serialized list**, not just the first recommended space — so `3;1, 4;1` and `3;1` are different votes.

## 4. Design Rationale: Custom TLS-Socket Protocol vs. Apache Ratis/Raft

While Apache Ratis and the Raft consensus protocol are excellent for state-machine replication (ensuring consistent state updates across a replicated log), the requirements of the Recommender Cluster in this assignment led to the design of a custom, lightweight, TLS-socket-based majority voting consensus protocol.

The key design considerations for this choice were:
1. **Nature of the Workload (Read-only vs. State Replication)**: 
   The recommender nodes perform stateless computations (generating recommendations by querying the database) rather than maintaining and replicating a transactional log of state changes. Raft is heavily optimized for committing state transitions to a shared log. Since recommendation generation is a querying process, a lightweight majority-vote consensus protocol on query results is more direct, avoiding the overhead of maintaining a replicated log.
2. **Strict Byzantine/Malicious Fault Model**:
   Standard Raft assumes a crash-fault-tolerant (CFT) model, where cluster nodes might crash or delay messages but are not *malicious* (they do not actively lie, forge messages, or return falsified payloads). However, Section 4.2 of the assignment requires the cluster to handle and isolate *malicious/compromised* recommender nodes. Our custom protocol validates signatures using HMAC-SHA256, enforces strict mutual TLS (mTLS) identities, and performs majority validation on the returned recommendation lists. Standard Raft/Ratis does not natively protect against Byzantine nodes voting with corrupted payloads; a custom voting scheme is required at the query interface.
3. **Complexity & Footprint**:
   Integrating Apache Ratis requires setting up complex state machines, Raft groups, log directories, and raft-specific port-forwarding, which introduces substantial operational complexity. A custom TLS-socket-based protocol utilizes the existing secure transport layer (mTLS) and is lightweight, clear, and perfectly suited for academic and containerized deployment scopes.
