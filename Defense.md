# Defense Report

## 1. Executive Summary
During the Round 2 Red Teaming exercise, multiple critical vulnerabilities were discovered in the Mulligan parking system's architecture. The red teams successfully exploited misconfigurations in RabbitMQ, MongoDB, and application components to compromise the Confidentiality, Integrity, and Availability (CIA) of the system. This report details the implemented fixes that address these findings, bringing the system into compliance with industry standard security best practices. All default secrets have been rotated, mTLS enforcement is strict across the cluster, and robust replay prevention has been implemented.

## 2. Vulnerability Inventory
The red teams reported the following vulnerabilities:
*   **T5-I-01: Shared Secrets & Insecure Access (RabbitMQ):** Default, hardcoded credentials (`change-me-for-real-deployments`, `mulligan-secure-cookie-rotated-99213`, and default passwords) were used in production environments, exposing the RabbitMQ management API to unauthenticated actors.
*   **T5-C-02 & T5-C-03: Lack of Mutual TLS (mTLS):** MongoDB cluster communication was configured with `--tlsAllowConnectionsWithoutCertificates`, effectively allowing plaintext access despite the presence of certificates.
*   **T5-I-02: Message Replay (Split-Brain Nonce Store):** The anti-replay validation (NonceStore) fell back to an in-memory cache when it couldn't connect to MongoDB, meaning that nonces were not shared across distributed consumers, allowing cross-node replay attacks.
*   **T5-A-01: Drain Attack & Missing Authorization:** Users like `peo_service` possessed excessive read permissions, allowing them to drain messages from the `transactions.queue` intended for the backend server.
*   **T5-I-03: Information Leakage:** System errors and database stack traces were being propagated to the UI, exposing internal cluster topology and details.

## 3. Root Cause Analysis
The root causes for the discovered vulnerabilities were predominantly related to "security through obscurity" and improper default configurations:
*   **Hardcoded Fallbacks:** `AppConfig.java` checked for an incorrect default HMAC string, meaning the actual default (`change-me-for-real-deployments`) was accepted as valid in production. 
*   **Silent Failures:** The `NonceStore` was built with a "fail-open" or resilient mindset where, if MongoDB was unavailable, it fell back to local memory to keep the system running. However, for a security control, this resulted in a silent bypass of distributed validation.
*   **Permissive Defaults:** MongoDB was launched with permissive flags (`--tlsAllowConnectionsWithoutCertificates`) to ease local testing, but this flag was carried over to the production `docker-compose.yml`.
*   **Broad Permissions:** RabbitMQ profiles in `definitions.json` utilized a broad, non-least-privilege model. Profiles used for merely publishing messages also had read/consume access to those queues.

## 4. Fix Details
The following mitigation strategies were implemented:
1.  **Strict mTLS Enforcement:** Removed the `--tlsAllowConnectionsWithoutCertificates` flag from all MongoDB instances in `docker-compose.yml`. Changed `ALLOW_INVALID_HOSTNAMES` to `false` in `.env` to enforce strict certificate validation on all cluster links.
2.  **Secret Rotation & Removal:** Rotated the `HMAC_SECRET` and `RABBITMQ_ERLANG_COOKIE` in `.env` to new cryptographic random values. Updated the RabbitMQ passwords and removed the leaked `mulligan_admin` default password.
3.  **Least-Privilege Profiles:** Created dedicated `queue_service` and `storage_service` users in `definitions.json` and `AppConfig.java`. Restricted the `customer` and `peo_service` users to `write`-only permissions on their respective queues.
4.  **Fail-Fast Security Validation:** Modified `NonceStore.java` to throw a fatal `IllegalStateException` instead of falling back to an in-memory cache. This ensures the cluster fails closed if distributed replay validation cannot be guaranteed.
5.  **Input Validation & Error Handling:** Modified `CustomerController.java` to catch all exceptions during transaction submission and map them to generic user-friendly messages (e.g., "Unable to process request"), explicitly preventing the leak of stack traces.
6.  **Persistent Security Logging:** Validated that `SecurityLogger` successfully initializes a persistent rotating file handler (`logs/security.log`) for all major backend services.

## 5. Updated Security Architecture

```mermaid
flowchart TD
    subgraph UI Clients
        C[Customer UI\n(Role: customer)]
        P[PEO UI\n(Role: peo_service)]
        M[MO UI\n(Role: mulligan_admin)]
    end

    subgraph Message Broker
        RMQ[(RabbitMQ Cluster\nQuorum Queues)]
    end

    subgraph Microservices
        QS[Queue Server\n(Role: queue_service)]
        SS[Storage Server\n(Role: storage_service)]
        RS[Recommender Server]
    end

    subgraph Data Tier
        DB[(MongoDB Replica Set\nStrict mTLS)]
    end

    C -- "AMQP (TLS)\nWrite-Only" --> RMQ
    P -- "AMQP (TLS)\nWrite-Only" --> RMQ
    M -- "AMQP (TLS)\nAdmin" --> RMQ

    RMQ -- "AMQP (TLS)\nRead/Consume" --> QS
    RMQ -- "AMQP (TLS)\nRead/Consume" --> SS

    QS -- "MongoDB Wire Protocol (TLS)\nShared Nonce Store" --> DB
    SS -- "MongoDB Wire Protocol (TLS)" --> DB
    RS -- "MongoDB Wire Protocol (TLS)" --> DB
```

## 6. Testing Results
*   **Authentication & Authorization:** Attempting to publish messages using old credentials now results in an authentication failure. Attempting to consume messages as a `customer` or `peo_service` results in a channel exception.
*   **Replay Prevention:** Resubmitting an identical signed message (same timestamp and nonce) to multiple nodes sequentially results in a rejection from the `NonceStore` backed by MongoDB. Stopping MongoDB explicitly crashes the consumers instead of processing messages insecurely.
*   **mTLS Enforcement:** Connecting to the MongoDB cluster via `mongosh` without providing the client TLS certificate results in a connection rejection.

## 7. Lessons Learned
*   **Fail-Closed Principle:** Security mechanisms like anti-replay caches must fail closed. Falling back to local memory breaks distributed security guarantees and is worse than crashing.
*   **Configuration Management:** Default secrets must be actively detected and rejected at startup. The codebase must have logic to explicitly prevent deployment if default/placeholder secrets are found in the environment.
*   **Least Privilege:** Message brokers require careful routing and permission design. Publishers should rarely, if ever, have consume permissions on the queues they publish to.
