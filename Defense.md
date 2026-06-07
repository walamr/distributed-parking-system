# Task 5 - Blue Teaming and Defense Report

## 1. Executive Summary

This report documents the Blue Team response for the Mulligan Parking System after the previous red-team round identified weaknesses around RabbitMQ exposure, internal network reachability, and inconsistent access control. The red-team evidence showed that an attacker container on the same Docker environment could reach the RabbitMQ host, connect to AMQP port `5672`, connect to the management port `15672`, retrieve the RabbitMQ Management HTML page directly, authenticate with the `mulligan` account in the UI, and probe the management API with `mulligan:mulligan`. The same exercise also demonstrated that a test message could be published from the attacker environment, even though it was not routed to a queue. Together, these findings showed that the original design trusted the internal Docker network too much and did not enforce a strong cryptographic trust boundary for message traffic.

The defensive work focused on eliminating plaintext messaging, reducing management exposure, enforcing strong message authentication, and validating every message before it is processed or stored. RabbitMQ is now configured for TLS-only AMQP, with peer verification and client certificates for broker connections. The non-TLS AMQP listener is disabled, the guest account remains loopback-only, and management HTTP is no longer reachable from peer containers because the HTTP listener is bound to container loopback while HTTPS remains available for controlled administration. The Docker Compose configuration was tightened so published infrastructure ports bind only to `127.0.0.1`, and the infrastructure network is marked internal. In addition, the Java services already present in the repository were aligned with the defense requirements by using HMAC-SHA256 signed `MessageEnvelope` objects, UUID nonces, Unix timestamps, 60-second freshness checks, and server-side nonce caching to reject replays.

Application-side controls were also strengthened to match the assignment requirements more closely. Both the queue consumer and the storage server now use the same shared payload validation path. That validation enforces JSON object parsing, field type checking, amount range checking, no negative amounts, valid parking space format, length limits, and rejection of unsupported characters in free-text reason fields. Rejected messages are not re-queued. Detailed reasons are logged on the server side, while startup and runtime failures avoid printing stack traces to clients. Persistent security logging is enabled through `logs/security.log`, where authentication failures, validation rejections, malformed input, and other security-relevant events can be retained for later review.

Overall, the Blue Team changes address the root causes behind the red-team findings: overexposed RabbitMQ listeners, broad trust in the internal network, permissive credentials, and missing cryptographic message validation. The resulting architecture is not based on trusting Docker-network membership alone. Instead, it uses layered controls: host-loopback port publishing, TLS/mTLS, per-service RabbitMQ permissions, signed envelopes, replay protection, strict input validation, and persistent security logging.

## 2. Vulnerability Inventory

| Vulnerability | CIA Dimension | Severity | Affected Component | Fix Status |
|---|---|---|---|---|
| Plaintext AMQP listener reachable on `5672` from the internal Docker environment | Confidentiality, Integrity | Critical | RabbitMQ broker listener configuration | Fixed |
| RabbitMQ management HTTP/UI reachable on `15672` from the internal Docker environment | Confidentiality, Availability | High | RabbitMQ management plugin | Fixed |
| Broad Docker network reachability enabled service discovery and probing from an attacker container | Confidentiality | High | Docker networking and service exposure | Mitigated |
| Credentials and authorization model were inconsistent (`mulligan` UI access, API `not_authorized`) | Integrity | High | RabbitMQ users, permissions, and account design | Fixed |
| Queue traffic lacked a mandatory cryptographic integrity check | Integrity | Critical | Queue message protocol | Fixed |
| Messages were vulnerable to replay because duplicate nonces and stale timestamps were not enforced at the trust boundary | Integrity, Availability | High | Queue consumer and storage validation flow | Fixed |
| Server-side input validation was too permissive for business payloads | Integrity, Availability | High | Queue consumer and storage server | Fixed |
| Error handling risked exposing internal details through direct exception output | Confidentiality | Medium | Storage server runtime path | Fixed |
| Security event logging was not sufficient as a primary audit trail | Integrity, Availability | Medium | Shared logging utilities | Fixed |

## 3. Root Cause Analysis

### 3.1 Plaintext RabbitMQ listeners

The original environment relied on RabbitMQ defaults and development-oriented network exposure. Enabling TLS alone is not enough in RabbitMQ because non-TLS listeners can remain active unless explicitly disabled. That design decision allowed the red team to test port `5672` successfully.

### 3.2 Exposed management interface

The management interface was treated as an internal convenience feature instead of a protected administrative surface. Because HTTP management was reachable inside the Docker environment, the red team could fetch the RabbitMQ Management HTML directly from `http://mulligan-rabbitmq:15672`.

### 3.3 Over-trusting the internal Docker network

The system implicitly assumed that any container on the same Docker environment was trustworthy. This was the core design mistake behind the service-discovery results. Once the attacker container joined the same environment, it could ping the broker, scan open ports, and probe services freely.

### 3.4 Inconsistent account and permission model

RabbitMQ user design was not aligned with least privilege. The red-team evidence showed an account state where UI access and API authorization did not behave consistently. That usually happens when a shared or overly broad account is used without a clean separation between operational roles and service roles.

### 3.5 Missing cryptographic message boundary

The queue protocol originally accepted the presence of a message on the broker as implicit trust. That meant a container with broker access could attempt to publish crafted JSON directly. Even when a test message was not routed, the underlying weakness remained: the application layer itself was not the trust boundary.

### 3.6 Replay exposure

Without a mandatory nonce cache and strict timestamp freshness window, the same signed or copied message could be replayed. Distributed systems often fail here when replay protection is treated as optional rather than part of the message contract.

### 3.7 Weak payload validation

Payload validation initially focused on basic parsing rather than strict semantic validation. That left room for wrong data types, negative values, malformed parking-space identifiers, and unexpected characters reaching downstream processing logic.

### 3.8 Unsafe failure behavior

Some runtime paths still surfaced raw exception detail directly to stderr, and utility test classes contained direct stack-trace printing. In a security context, detailed failures should stay on the server side and not become part of the client-facing behavior.

### 3.9 Insufficient security logging

Security logging existed only partially and was not yet the main record for authentication failures, malformed messages, and validation rejections. That would make incident review and forensic reconstruction harder after an attack attempt.

## 4. Fix Details

### 4.1 TLS-only RabbitMQ and management hardening

RabbitMQ was hardened in [`docker/rabbitmq/rabbitmq.conf`](/c:/Users/user/assigment%203%20distu/ds-assignment-3-team-5-1/docker/rabbitmq/rabbitmq.conf) by disabling non-TLS AMQP listeners with `listeners.tcp = none`, enforcing TLS versions `1.2` and `1.3`, keeping peer verification enabled, and requiring client certificates for AMQP connections. The same file now binds the management HTTP listener to `127.0.0.1` inside the container so peer containers cannot browse the plaintext management UI, while HTTPS management remains available on port `15671`.

In Stage 3, we further hardened all clustering and internal Erlang links:
- **Erlang TLS Distribution**: We configured Erlang distribution port (`25672`) to run exclusively over TLS 1.2/1.3 using a dedicated [`inter_node_tls.config`](/c:/Users/user/assigment%203%20distu/ds-assignment-3-team-5-1/docker/rabbitmq/inter_node_tls.config) file containing client/server verification certificates, and forced it via the environment variables `RABBITMQ_SERVER_ADDITIONAL_ERL_ARGS` and `RABBITMQ_CTL_ERL_ARGS`. This fully prevents sniffing or injection of plaintext control commands between nodes or from local containers.
- **Erlang Cookie Rotation**: Any default cookie string (`parking-cluster-cookie`) exposed in compose configuration has been rotated to a new secure random value (`mulligan-secure-cookie-rotated-99213`), set in `.env` and docker configuration files to isolate the cluster.

This fix directly addresses the red-team evidence for ports `5672` and `15672`, as well as direct HTML access to the management interface.

### 4.2 RabbitMQ credential and permission hardening

RabbitMQ users and least-privilege permissions are defined in [`docker/rabbitmq/definitions.json`](/c:/Users/user/assigment%203%20distu/ds-assignment-3-team-5-1/docker/rabbitmq/definitions.json). The repository uses per-service users instead of default credentials:

- `customer` can publish only to `transactions.queue`
- `peo_service` can publish and consume only the queues needed by that service path
- `mulligan_admin` is reserved for administration

The RabbitMQ config also keeps the guest account loopback-only via `loopback_users.guest = true`, which is the secure default recommended by RabbitMQ. In the Java configuration layer, [`apps/common/src/main/java/edu/kinneret/parking/common/AppConfig.java`](/c:/Users/user/assigment%203%20distu/ds-assignment-3-team-5-1/apps/common/src/main/java/edu/kinneret/parking/common/AppConfig.java) explicitly rejects `guest` as a runtime RabbitMQ username.

This fixes the weak/inconsistent authorization problem observed in the red-team report.

### 4.3 Docker exposure reduction

Infrastructure exposure was tightened in [`docker-compose.yml`](/c:/Users/user/assigment%203%20distu/ds-assignment-3-team-5-1/docker-compose.yml):

- RabbitMQ and MongoDB published ports are bound to `127.0.0.1`
- the shared infrastructure network is marked `internal: true`

This does not mean Docker itself becomes a security boundary against a host administrator with Docker access, but it does remove broad host exposure and better matches the intended trust model for the assignment.

### 4.4 HMAC-SHA256 signed envelopes

The message protocol is implemented around [`apps/common/src/main/java/edu/kinneret/parking/common/MessageEnvelope.java`](/c:/Users/user/assigment%203%20distu/ds-assignment-3-team-5-1/apps/common/src/main/java/edu/kinneret/parking/common/MessageEnvelope.java) and [`apps/common/src/main/java/edu/kinneret/parking/common/SecureMessageSigner.java`](/c:/Users/user/assigment%203%20distu/ds-assignment-3-team-5-1/apps/common/src/main/java/edu/kinneret/parking/common/SecureMessageSigner.java). Every message includes:

- a UUID `messageId`
- a `correlationId`
- a UUID `nonce`
- a Unix timestamp in seconds
- a `clientIp`
- a message `type`
- the original payload
- an HMAC-SHA256 signature

The signing content is deterministic, and validation uses constant-time comparison through `MessageDigest.isEqual`.

### 4.5 Replay protection and freshness enforcement

Replay protection is enforced in [`apps/common/src/main/java/edu/kinneret/parking/common/QueueMessageSecurityValidator.java`](/c:/Users/user/assigment%203%20distu/ds-assignment-3-team-5-1/apps/common/src/main/java/edu/kinneret/parking/common/QueueMessageSecurityValidator.java) and [`apps/common/src/main/java/edu/kinneret/parking/common/NonceStore.java`](/c:/Users/user/assigment%203%20distu/ds-assignment-3-team-5-1/apps/common/src/main/java/edu/kinneret/parking/common/NonceStore.java):

- timestamps older than 60 seconds are rejected
- timestamps too far in the future are rejected
- invalid HMAC values are rejected
- repeated nonces are rejected
- nonces are cached server-side with TTL-based cleanup

The validator is used by both the queue server and the storage server before business processing proceeds.

### 4.6 Shared server-side input validation

Validation logic was centralized and tightened in [`apps/common/src/main/java/edu/kinneret/parking/common/ValidationUtils.java`](/c:/Users/user/assigment%203%20distu/ds-assignment-3-team-5-1/apps/common/src/main/java/edu/kinneret/parking/common/ValidationUtils.java), and both consumers now call the same validation routine:

- [`apps/queue-server/src/main/java/edu/kinneret/parking/queue/QueueConsumerService.java`](/c:/Users/user/assigment%203%20distu/ds-assignment-3-team-5-1/apps/queue-server/src/main/java/edu/kinneret/parking/queue/QueueConsumerService.java)
- [`apps/storage-server/src/main/java/edu/kinneret/parking/storage/StorageServerApplication.java`](/c:/Users/user/assigment%203%20distu/ds-assignment-3-team-5-1/apps/storage-server/src/main/java/edu/kinneret/parking/storage/StorageServerApplication.java)

The checks now include:

- payload must parse as a JSON object
- `vehicleId`, `spaceId`, and `reason` must be string values when present
- `amount` must be numeric when present
- `amount` must be finite, non-negative, and within a bounded range
- parking spaces must match the expected format such as `P01`
- vehicle identifiers must match the uppercase alphanumeric format
- free-text `reason` values are length-limited and reject unsupported characters

### 4.7 Safe error handling

The storage-server startup path in [`apps/storage-server/src/main/java/edu/kinneret/parking/storage/StorageServerApplication.java`](/c:/Users/user/assigment%203%20distu/ds-assignment-3-team-5-1/apps/storage-server/src/main/java/edu/kinneret/parking/storage/StorageServerApplication.java) was adjusted so the main protected failure path logs the detailed error server-side and emits a generic terminal message rather than dumping a stack trace. For rejected messages, the system logs details internally and rejects the message without re-queueing it.

### 4.8 Persistent security logging

Persistent security logging is handled by [`apps/common/src/main/java/edu/kinneret/parking/common/SecurityLogger.java`](/c:/Users/user/assigment%203%20distu/ds-assignment-3-team-5-1/apps/common/src/main/java/edu/kinneret/parking/common/SecurityLogger.java). The queue and storage services log authentication failures, message rejections, and audit-relevant events to `logs/security.log`. Timestamps are included by the Java logging formatter, and source information is added where the message envelope provides `clientIp`.

### 4.9 Verification-oriented tests

The following tests document the expected defensive behavior:

- [`apps/common/src/test/java/edu/kinneret/parking/common/ValidationUtilsTest.java`](/c:/Users/user/assigment%203%20distu/ds-assignment-3-team-5-1/apps/common/src/test/java/edu/kinneret/parking/common/ValidationUtilsTest.java)
- [`apps/queue-server/src/test/java/edu/kinneret/parking/queue/QueueMessageSecurityValidatorTest.java`](/c:/Users/user/assigment%203%20distu/ds-assignment-3-team-5-1/apps/queue-server/src/test/java/edu/kinneret/parking/queue/QueueMessageSecurityValidatorTest.java)

These cover valid signed envelopes, invalid HMAC rejection, old timestamp rejection, replayed nonce rejection, malformed envelope rejection, and stricter payload-validation rejection paths.

## 5. Updated Security Architecture

```mermaid
flowchart TB
    subgraph Host["Host Machine"]
        Customer["Customer UI"]
        PEO["PEO UI"]
        QueueServer["Queue Server"]
        Storage["Storage Server"]
        Admin["Admin / HTTPS Management"]
    end

    subgraph Docker["Docker Internal Infrastructure Network"]
        subgraph Rabbit["RabbitMQ Cluster"]
            RMQ1["rabbitmq1\nAMQPS 5671\nHTTPS 15671"]
            RMQ2["rabbitmq2\nAMQPS 5671\nHTTPS 15671"]
            RMQ3["rabbitmq3\nAMQPS 5671\nHTTPS 15671"]
        end

        subgraph Mongo["MongoDB Replica Set"]
            M1["mongo1"]
            M2["mongo2"]
            M3["mongo3"]
        end
    end

    Customer -->|"TLS 1.2+/mTLS + HMAC envelope\nuser: customer\nwrite: transactions.queue"| RMQ1
    PEO -->|"TLS 1.2+/mTLS + HMAC envelope\nuser: peo_service\nwrite/read limited queues"| RMQ2
    QueueServer -->|"TLS 1.2+/mTLS\nvalidates HMAC + nonce + timestamp"| RMQ1
    Storage -->|"TLS 1.2+/mTLS\nvalidates HMAC + nonce + timestamp"| RMQ3
    Admin -->|"HTTPS 15671 only\nadmin account required"| RMQ1

    RMQ1 <-->|"cluster sync"| RMQ2
    RMQ2 <-->|"cluster sync"| RMQ3

    Storage -->|"TLS to replica set\nvalidated payload only"| M1
    Storage -->|"replica awareness"| M2
    Storage -->|"replica awareness"| M3

    RMQ1 -.->|"reject invalid HMAC\nreject replayed nonce\nreject stale timestamp"| QueueServer
    RMQ3 -.->|"reject invalid payload\nlog source + timestamp"| Storage
```

### Architecture Notes

- Host-published infrastructure ports are bound to `127.0.0.1`.
- Plain AMQP on `5672` is disabled.
- Management HTTP is not available to peer containers; administration is intended through HTTPS.
- RabbitMQ permissions are separated by service role and virtual-host scope.
- Message trust is enforced at the application layer through HMAC, nonce, and timestamp validation.
- `logs/security.log` serves as the persistent security-event trail.
- **Architectural Deviation Justification (Storage Server vs. UI Consumers):** While the original Logical Architecture diagram (Figure 2) depicted the Municipality UI reading directly from the `Transactions` and `Citations` queues to generate reports, we identified this as an architectural anti-pattern for reliable distributed systems. UIs should not act as volatile message consumers for historical data generation. To improve system resilience and ensure Zero Data Loss, we introduced a dedicated backend Microservice (`StorageServer`) that acts as the sole consumer of RabbitMQ queues and securely persists all validated events to the MongoDB Cluster. Consequently, the Municipality UI (`MOApp`) was updated to query historical reports directly from the database via `TCP/SQL` (MongoDB Driver), connecting to RabbitMQ solely for real-time Health Check monitoring. This deliberate deviation from the original diagram reflects production-grade enterprise design principles.

## 6. Testing Results

This report uses text-based evidence rather than screenshot placeholders. Commands and expected evidence are listed here so another team or grader can reproduce the verification exactly.

### 6.1 Build and Unit Test Verification

Command run during final preparation:

```powershell
.\gradlew.bat clean test build
```

Observed result on May 18, 2026:

```text
BUILD SUCCESSFUL
59 actionable tasks: 57 executed, 2 up-to-date
```

The test suite covers signed envelope acceptance, invalid HMAC rejection, stale timestamp rejection, replayed nonce rejection, malformed envelope rejection, invalid payload rejection, quorum queue arguments, cluster-client failover ordering, and MongoDB payload compatibility.

### 6.1.1 Deployment and Resilience Evidence

![Docker Startup](docs/evidence/rabbitmq/01a-docker-compose-clean-reset-and-startup.png)
*Clean Docker reset and startup of the RabbitMQ and MongoDB cluster services.*

![Docker Status](docs/evidence/rabbitmq/01b-docker-compose-all-services-running-healthy.png)
*Docker Compose status showing all MongoDB nodes running and all RabbitMQ nodes healthy.*

![RabbitMQ Cluster Status](docs/evidence/rabbitmq/02a-rabbitmq-cluster-status-3-running-nodes.png)
*RabbitMQ cluster status showing three disk nodes and three running nodes.*

![RabbitMQ Listeners](docs/evidence/rabbitmq/02b-rabbitmq-cluster-listeners-and-tls-ports.png)
*RabbitMQ cluster listeners showing HTTPS management and AMQP over TLS ports.*

![RabbitMQ Feature Flags](docs/evidence/rabbitmq/02c-rabbitmq-cluster-feature-flags-enabled.png)
*RabbitMQ feature flags showing quorum queue support enabled.*

![Quorum Queues](docs/evidence/rabbitmq/03-quorum-queues-replicated-3-members.png)
*RabbitMQ quorum queues for transactions and citations replicated across all three cluster nodes.*

![Users and Permissions](docs/evidence/rabbitmq/04-rabbitmq-users-and-permissions.png)
*RabbitMQ users and least-privilege permissions for the /parking virtual host.*

![Queue Server Consumers](docs/evidence/rabbitmq/05-queue-server-running-consumers.png)
*Queue server started successfully, initialized RabbitMQ topology, and attached consumers to transactions.queue and citations.queue.*

![Smoke Test Success](docs/evidence/rabbitmq/06-smoke-test-published-successfully.png)
*Queue smoke test successfully initialized RabbitMQ topology and published secured test messages to transactions.queue and citations.queue.*

![Queues After Smoke Test](docs/evidence/rabbitmq/07-queues-after-smoke-test-consumers-attached.png)
*Queue state after smoke test showing no pending messages, active consumers, and quorum replication across all three RabbitMQ nodes.*

![Gradle Build Success](docs/evidence/rabbitmq/13-gradle-clean-test-build-success.png)
*Full Gradle clean, test, and build completed successfully for the project.*

![Queue Server Tests](docs/evidence/rabbitmq/14-queue-server-tests-success.png)
*Queue server test suite completed successfully.*

### 6.1.2 Cluster Failover and Recovery Evidence

![Failover Node 1 Stopped](docs/evidence/rabbitmq/08a-rabbitmq1-stopped-cluster-running-on-rabbitmq2-rabbitmq3.png)
*RabbitMQ failover test after stopping rabbitmq1. The cluster still runs on rabbitmq2 and rabbitmq3 with no alarms.*

![Failover Listeners](docs/evidence/rabbitmq/08b-rabbitmq-failover-listeners-after-node-failure.png)
*RabbitMQ listeners remain active on rabbitmq2 and rabbitmq3 after rabbitmq1 failure.*

![Failover Feature Flags](docs/evidence/rabbitmq/08c-rabbitmq-feature-flags-after-node-failure.png)
*RabbitMQ feature flags remain enabled after rabbitmq1 failure.*

![Queues After Failure](docs/evidence/rabbitmq/09-queues-after-rabbitmq1-failure.png)
*Quorum queues remain available after rabbitmq1 failure, with active consumers and replicated members.*

![Smoke Test Success After Failure](docs/evidence/rabbitmq/10-smoke-test-success-after-rabbitmq1-failure.png)
*Smoke test succeeded after rabbitmq1 failure, publishing messages through the remaining RabbitMQ nodes.*

![Node 1 Recovered](docs/evidence/rabbitmq/11a-rabbitmq1-recovered-3-nodes-running.png)
*rabbitmq1 recovered successfully and rejoined the RabbitMQ cluster. All three nodes are running again.*

![Recovery Listeners](docs/evidence/rabbitmq/11b-rabbitmq-recovery-listeners-and-no-partitions.png)
*After recovery, the RabbitMQ cluster has no alarms or network partitions and TLS listeners are active.*

![Recovery Feature Flags](docs/evidence/rabbitmq/11c-rabbitmq-feature-flags-after-recovery.png)
*RabbitMQ feature flags remain enabled after node recovery.*

![Queues After Recovery](docs/evidence/rabbitmq/12-queues-after-rabbitmq1-recovery.png)
*Quorum queues after rabbitmq1 recovery showing active consumers and replication across all three RabbitMQ nodes.*

### 6.2 Docker and Network Configuration Evidence

Command:

```powershell
docker compose config
```

Expected evidence:

```text
networks:
  rabbitmq-internal:
    internal: true
```

The compose file also binds infrastructure ports to loopback only:

| Service | Host binding | Purpose |
| :--- | :--- | :--- |
| `rabbitmq1` | `127.0.0.1:5671` | AMQP over TLS |
| `rabbitmq2` | `127.0.0.1:5673` | AMQP over TLS |
| `rabbitmq3` | `127.0.0.1:5674` | AMQP over TLS |
| `rabbitmq1` | `127.0.0.1:15671` | HTTPS management |
| `mongo1` | `127.0.0.1:27017` | MongoDB TLS |
| `mongo2` | `127.0.0.1:27018` | MongoDB TLS |
| `mongo3` | `127.0.0.1:27019` | MongoDB TLS |

### 6.3 RabbitMQ Cluster and Listener Evidence

Commands:

```powershell
docker compose up -d
.\scripts\setup-rabbitmq-cluster.ps1
docker exec rabbitmq1 rabbitmqctl cluster_status
docker exec rabbitmq1 rabbitmq-diagnostics listeners
docker exec rabbitmq1 rabbitmqctl list_queues name type durable arguments leader members
docker exec rabbitmq1 rabbitmqctl list_permissions -p /parking
```

Expected evidence:

- `cluster_status` lists `rabbit@rabbitmq1`, `rabbit@rabbitmq2`, and `rabbit@rabbitmq3`.
- listener output includes AMQP over TLS on `5671` and HTTPS management on `15671`.
- there is no plaintext AMQP listener on `5672`.
- `transactions.queue` and `citations.queue` have type `quorum`.
- queue arguments include `x-quorum-initial-group-size=3`.
- `customer`, `peo_service`, and `mulligan_admin` permissions are restricted to the `/parking` virtual host.

### 6.4 MongoDB Replica Set Evidence

Commands:

```powershell
docker compose up -d
.\docker\mongodb\init-rs.ps1
.\scripts\verify-cluster-health.ps1
docker exec mongo1 mongosh "mongodb://mulligan_db_admin:db_pass_admin_99@mongo1:27017/parking_db?authSource=admin&tls=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo1.pem" --eval "rs.status().members.map(m => m.name + ':' + m.stateStr)"
docker exec mongo1 mongosh "mongodb://mulligan_db_admin:db_pass_admin_99@mongo1:27017/parking_db?authSource=admin&tls=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo1.pem" --eval "db.vehicles.countDocuments(); db.zones.countDocuments(); db.spaces.countDocuments();"
```

Expected evidence:

- replica-set output contains all three nodes: `mongo1:27017`, `mongo2:27018`, and `mongo3:27019`
- one node is `PRIMARY` and healthy peers are `SECONDARY`
- `vehicles`, `zones`, and `spaces` contain sample records after `seed-data.js` runs

### 6.5 Security-Rejection Test Evidence

Commands:

```powershell
.\gradlew.bat :queue-server:test --tests edu.kinneret.parking.queue.QueueMessageSecurityValidatorTest.shouldRejectInvalidHmac
.\gradlew.bat :queue-server:test --tests edu.kinneret.parking.queue.QueueMessageSecurityValidatorTest.shouldRejectReplayedNonce
.\gradlew.bat :queue-server:test --tests edu.kinneret.parking.queue.QueueMessageSecurityValidatorTest.shouldRejectOldTimestamp
.\gradlew.bat :common:test --tests edu.kinneret.parking.common.ValidationUtilsTest.shouldRejectParkingPayloadWithWrongAmountType
.\gradlew.bat :common:test --tests edu.kinneret.parking.common.ValidationUtilsTest.shouldRejectParkingPayloadWithUnexpectedCharacters
```

Expected evidence:

```text
BUILD SUCCESSFUL
```

These tests prove invalid HMACs, replayed nonces, stale timestamps, wrong JSON types, and unsupported characters are rejected by implementation, not only by documentation.

### 6.6 UI Runtime Evidence Without Screenshots

| UI | Command | Required evidence |
| :--- | :--- | :--- |
| Customer UI | `.\gradlew.bat :customer-ui:run` | JavaFX window starts; start/stop parking publishes signed messages; history reads from MongoDB. |
| PEO UI | `.\gradlew.bat :peo-ui:run` | JavaFX window starts; legality checks read MongoDB; citation publishing uses `peo_service`. |
| MO UI | `.\gradlew.bat :mo-ui:run` | JavaFX window starts; transaction/citation reports read MongoDB; health labels update from cluster checks. |

### 6.7 Persistent Security Log Evidence

Command:

```powershell
Get-Content logs\storage-security.log.0 -Tail 20
```

Observed repository evidence includes timestamped audit entries such as:

```text
INFO: [AUDIT] [TRACE: ...] Stored transaction.start message | ID: ... | Source: 10.0.0.8
INFO: [AUDIT] [TRACE: ...] Stored transaction.stop message | ID: ... | Source: 10.0.0.8
WARNING: [SECURITY] CRITICAL ERROR: java.io.IOException | Host: 10.0.0.8
```

### 6.8 Previous Red-Team Evidence Linkage

The original red-team findings were successful reachability or probing of plaintext AMQP `5672`, management HTTP `15672`, weak management credentials, and direct message-publish attempts. The current implementation mitigates these by:

- disabling RabbitMQ plaintext AMQP with `listeners.tcp = none`
- not publishing `5672` or `15672` from the current compose file
- using loopback-only host bindings for published infrastructure ports
- marking the Docker infrastructure network `internal: true`
- requiring AMQP TLS with client certificates
- using per-service RabbitMQ users and permissions
- enforcing HMAC, nonce, and timestamp validation at the Java trust boundary

## 7. Lessons Learned

The main lesson from this round is that an internal Docker network is not a security control by itself. Administrative services and message brokers must be treated as hostile-network surfaces even when they are meant only for development or internal communication. Transport encryption, client authentication, least-privilege broker accounts, replay protection, and strict input validation should have been part of the original protocol and deployment design rather than added after an attack report. Security logging also needs to be designed early, because it is much harder to reconstruct incidents after the fact if rejection reasons and authentication failures were never recorded consistently.
