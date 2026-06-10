# Testing Evidence and Verification

## Scope

This document summarizes:

- automated tests now present in the repository
- manual verification steps for Task 1
- manual failure/recovery checks for the MongoDB cluster
- existing RabbitMQ evidence for Task 2
- text-based evidence commands that replace manual screenshot requirements

## 1. Automated Test Coverage

The current Gradle test/build run covers:

- shared validation and security helpers
- cluster node failover ordering
- app configuration defaults and overrides
- repository compatibility with nested MongoDB payload documents
- repository compatibility with legacy string payloads
- storage-server payload conversion before MongoDB persistence
- queue-server security validation
- quorum queue declaration arguments
- RabbitMQ connection factory recovery configuration

Relevant test files:

- `apps/common/src/test/java/edu/kinneret/parking/common/AppConfigTest.java`
- `apps/common/src/test/java/edu/kinneret/parking/common/ClusterClientSelectorTest.java`
- `apps/common/src/test/java/edu/kinneret/parking/common/ParkingRepositoryCompatibilityTest.java`
- `apps/common/src/test/java/edu/kinneret/parking/common/RabbitMqConnectionManagerTest.java`
- `apps/common/src/test/java/edu/kinneret/parking/common/MessageEnvelopeTest.java`
- `apps/common/src/test/java/edu/kinneret/parking/common/NonceStoreTest.java`
- `apps/common/src/test/java/edu/kinneret/parking/common/SecureMessageSignerTest.java`
- `apps/common/src/test/java/edu/kinneret/parking/common/ValidationUtilsTest.java`
- `apps/storage-server/src/test/java/edu/kinneret/parking/storage/MongoStorageServiceTest.java`
- `apps/queue-server/src/test/java/edu/kinneret/parking/queue/QueueMessageSecurityValidatorTest.java`
- `apps/queue-server/src/test/java/edu/kinneret/parking/queue/RabbitMqTopologyInitializerTest.java`

## 2. Task 1 Manual Verification

### Customer UI

Run:

```powershell
.\gradlew.bat :customer-ui:run
```

Verify:

- parking rate and area lookups work through the MongoDB replica set
- start parking publishes to `transactions.queue`
- stop parking publishes to `transactions.queue`
- history loads records even if `payload` is stored as a nested MongoDB document or as a legacy string

### PEO UI

Run:

```powershell
.\gradlew.bat :peo-ui:run
```

Verify:

- legality checks read data from the MongoDB replica set
- citations publish to `citations.queue`
- the default RabbitMQ account is `peo_service`

### MO UI

Run:

```powershell
.\gradlew.bat :mo-ui:run
```

Verify:

- transaction reports load from MongoDB
- citation reports load from MongoDB
- report tables tolerate both nested and legacy payload storage shapes

## 3. Task 1 Text Evidence Checklist

Screenshots are not required for this submission package. The UI checks are verified by launching each role application and confirming the observable runtime behavior below.

| UI | Command | Required text/runtime evidence |
| :--- | :--- | :--- |
| Customer UI | `.\gradlew.bat :customer-ui:run` | JavaFX window starts; start/stop parking actions publish signed messages; history query reads from the MongoDB replica set. |
| PEO UI | `.\gradlew.bat :peo-ui:run` | JavaFX window starts; legality query reads from MongoDB; citation publish uses `peo_service` and `citations.queue`. |
| MO UI | `.\gradlew.bat :mo-ui:run` | JavaFX window starts; transaction and citation reports load from MongoDB; health labels reflect cluster checks. |

For CLI verification (pure terminal console-based, completely independent of JavaFX):

First, compile the shadow fat JARs:
```powershell
.\gradlew.bat clean shadowJar
```

Then run the Customer CLI:
```powershell
java -cp apps/customer-ui/build/libs/customer-ui-1.0.jar edu.kinneret.parking.customer.cli.CustomerCLI
```

And PEO CLI:
```powershell
java -cp apps/peo-ui/build/libs/peo-ui-1.0.jar edu.kinneret.parking.peo.cli.PEOCLI
```

## 4. Task 2 Evidence Checklist

| Requirement | Evidence | Notes |
| :--- | :--- | :--- |
| Clean Docker startup | `01a-docker-compose-clean-reset-and-startup.png` | Existing RabbitMQ evidence file. |
| Docker services running healthy | `01b-docker-compose-all-services-running-healthy.png` | Existing RabbitMQ evidence file. |
| RabbitMQ 3-node cluster | `02a-rabbitmq-cluster-status-3-running-nodes.png` | Existing RabbitMQ evidence file. |
| RabbitMQ listeners and TLS ports | `02b-rabbitmq-cluster-listeners-and-tls-ports.png` | Existing RabbitMQ evidence file. |
| RabbitMQ feature flags | `02c-rabbitmq-cluster-feature-flags-enabled.png` | Existing RabbitMQ evidence file. |
| Quorum queues replicated across 3 nodes | `03-quorum-queues-replicated-3-members.png` | Existing RabbitMQ evidence file. |
| Users and permissions | `04-rabbitmq-users-and-permissions.png` | Existing RabbitMQ evidence file. |
| Queue server startup | `05-queue-server-running-consumers.png` | Existing RabbitMQ evidence file. |
| Smoke test success | `06-smoke-test-published-successfully.png` | Existing RabbitMQ evidence file. |
| Queues after smoke test | `07-queues-after-smoke-test-consumers-attached.png` | Existing RabbitMQ evidence file. |
| RabbitMQ node failure | `08a-rabbitmq1-stopped-cluster-running-on-rabbitmq2-rabbitmq3.png` | Existing RabbitMQ evidence file. |
| RabbitMQ failover listeners | `08b-rabbitmq-failover-listeners-after-node-failure.png` | Existing RabbitMQ evidence file. |
| RabbitMQ failover feature flags | `08c-rabbitmq-feature-flags-after-node-failure.png` | Existing RabbitMQ evidence file. |
| Queues after node failure | `09-queues-after-rabbitmq1-failure.png` | Existing RabbitMQ evidence file. |
| Smoke test after node failure | `10-smoke-test-success-after-rabbitmq1-failure.png` | Existing RabbitMQ evidence file. |
| RabbitMQ node recovery | `11a-rabbitmq1-recovered-3-nodes-running.png` | Existing RabbitMQ evidence file. |
| RabbitMQ recovery listeners | `11b-rabbitmq-recovery-listeners-and-no-partitions.png` | Existing RabbitMQ evidence file. |
| RabbitMQ recovery feature flags | `11c-rabbitmq-feature-flags-after-recovery.png` | Existing RabbitMQ evidence file. |
| Queues after recovery | `12-queues-after-rabbitmq1-recovery.png` | Existing RabbitMQ evidence file. |
| Gradle clean test build success | `13-gradle-clean-test-build-success.png` | Existing RabbitMQ evidence file. |
| Queue-server tests success | `14-queue-server-tests-success.png` | Existing RabbitMQ evidence file. |


## 5. Failure and Recovery Test Plans

### 5.1 MongoDB Replica Set Failure and Recovery Test Case

This test verifies the high availability, auto-failover, and data replication catch-up of the 3-node MongoDB Replica Set (`mongo1`, `mongo2`, `mongo3`).

#### A. Initial State Verification
1. Ensure all database containers are running:
   ```powershell
   docker compose up -d
   ```
2. Verify that the replica set is initiated and healthy:
   ```powershell
   .\scripts\verify-cluster-health.ps1
   ```
3. Check the status of the replica set nodes. Identify which node is the `PRIMARY` (usually `mongo1`) and which ones are `SECONDARY` (`mongo2`, `mongo3`):
   ```powershell
   docker exec mongo1 mongosh "mongodb://mulligan_db_admin:db_pwd_rotated_admin@localhost:27017/parking_db?authSource=admin&tls=true&tlsAllowInvalidHostnames=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo1.pem" --eval "rs.status().members.map(m => m.name + ' is ' + m.stateStr)"
   ```

#### B. Secondary Node Failure and Recovery Test
1. **Simulate secondary node failure**: Stop one of the secondary database nodes (e.g., `mongo2`):
   ```powershell
   docker stop mongo2
   ```
2. **Verify high availability**: Run the cluster health verification script to ensure the replica set detects the offline node and remains functional (reads and writes still succeed on the remaining nodes):
   ```powershell
   .\scripts\verify-cluster-health.ps1
   ```
3. Check status from the primary container (`mongo1`), verifying `mongo2` is marked as `(not reachable/healthy)` while the other two are healthy:
   ```powershell
   docker exec mongo1 mongosh "mongodb://mulligan_db_admin:db_pwd_rotated_admin@localhost:27017/parking_db?authSource=admin&tls=true&tlsAllowInvalidHostnames=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo1.pem" --eval "rs.status().members.map(m => m.name + ': ' + (m.health == 1 ? 'UP' : 'DOWN'))"
   ```
4. **Simulate secondary node recovery**: Restart the stopped secondary node:
   ```powershell
   docker start mongo2
   ```
5. **Verify data replication catch-up**: Wait a few seconds, then verify the replica set returns to a fully healthy state. `mongo2` will transition back to `SECONDARY`:
   ```powershell
   .\scripts\verify-cluster-health.ps1
   ```

#### C. Primary Node Failure and Auto-Election Test
1. **Simulate primary node failure**: Stop the current primary database node (e.g., `mongo1`):
   ```powershell
   docker stop mongo1
   ```
2. **Verify automatic primary reelection**: Wait a few seconds for the replica set to elect a new primary among the remaining online secondary nodes (`mongo2`/`mongo3`). Check replica set status on `mongo2` (which is now port 27018) to verify a new primary has been chosen:
   ```powershell
   docker exec mongo2 mongosh "mongodb://mulligan_db_admin:db_pwd_rotated_admin@localhost:27018/parking_db?authSource=admin&tls=true&tlsAllowInvalidHostnames=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo2.pem" --eval "rs.status().members.map(m => m.name + ' is ' + m.stateStr)"
   ```
3. **Verify application connectivity**: Launch the UI Gateway or run CLI queries to verify that applications automatically switch their connection to the newly elected primary and that database writes/reads remain fully functional.
4. **Simulate primary node recovery**: Restart the original primary node:
   ```powershell
   docker start mongo1
   ```
5. **Verify replication recovery**: Verify that `mongo1` rejoins the replica set, synchronizes any missed operations from the new primary, and transitions to `SECONDARY` status, restoring the replica set to 3 healthy nodes:
   ```powershell
   .\scripts\verify-cluster-health.ps1
   ```

---

### 5.2 RabbitMQ Node Failure and Recovery Test Case

This test verifies the high availability, failover of quorum queues, and client connection auto-recovery across the 3-node RabbitMQ cluster (`rabbitmq1`, `rabbitmq2`, `rabbitmq3`).

#### A. Initial State Verification
1. Verify all three RabbitMQ nodes are running and clustered:
   ```powershell
   docker exec rabbitmq1 rabbitmqctl cluster_status
   ```
2. Check that the queues are declared as quorum queues and replicated across all 3 nodes (leaders and members lists):
   ```powershell
   docker exec rabbitmq1 rabbitmqctl list_queues name type durable leader members
   ```

#### B. Single RabbitMQ Node Failure Test
1. **Simulate node failure**: Stop the first RabbitMQ node (`rabbitmq1`):
   ```powershell
   docker stop rabbitmq1
   ```
2. **Verify cluster state**: Query the status from `rabbitmq2` to verify that the cluster remains operational with the remaining two nodes (`rabbitmq2`, `rabbitmq3`):
   ```powershell
   docker exec rabbitmq2 rabbitmqctl cluster_status
   ```
3. **Verify quorum queue availability**: Confirm that `transactions.queue` and `citations.queue` remain available, that a new leader was automatically elected on one of the surviving nodes, and that the members list shows `rabbitmq1` as offline/down:
   ```powershell
   docker exec rabbitmq2 rabbitmqctl list_queues name type messages_ready leader members
   ```
4. **Verify client connection recovery**: Confirm that the Java queue server and UI applications automatically catch the connection loss and reconnect to another active node in the configured list (`localhost:5671,localhost:5673,localhost:5674`) without dropping messages or throwing fatal crashes. Run the smoke test to verify message publication:
   ```powershell
   $env:RABBITMQ_USERNAME='peo_service'
   $env:RABBITMQ_PASSWORD='peo_secure_pass_2026'
   $env:HMAC_SECRET='change-me-for-real-deployments'
   .\gradlew.bat :queue-server:runQueueSmokeTest
   ```

#### C. RabbitMQ Node Recovery Test
1. **Simulate node recovery**: Restart the stopped RabbitMQ node:
   ```powershell
   docker start rabbitmq1
   ```
2. **Verify cluster rejoining**: Verify that `rabbitmq1` successfully rejoins the cluster and synchronizes state:
   ```powershell
   docker exec rabbitmq1 rabbitmqctl cluster_status
   ```
3. **Verify queue member replication**: Confirm that `rabbitmq1` is once again listed as an active member in the quorum queue replication groups:
   ```powershell
   docker exec rabbitmq1 rabbitmqctl list_queues name type leader members
   ```

## 6. Build Validation Commands

```powershell
.\gradlew.bat clean test build
.\gradlew.bat :queue-server:test
```

## 7. MongoDB Text Evidence

Use these commands to collect database evidence without screenshots:

```powershell
docker compose up -d
.\docker\mongodb\init-rs.ps1
.\scripts\verify-cluster-health.ps1
docker exec mongo1 mongosh "mongodb://mulligan_db_admin:db_pwd_rotated_admin@mongo1:27017/parking_db?authSource=admin&tls=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo1.pem" --eval "rs.status().members.map(m => m.name + ':' + m.stateStr)"
docker exec mongo1 mongosh "mongodb://mulligan_db_admin:db_pwd_rotated_admin@mongo1:27017/parking_db?authSource=admin&tls=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo1.pem" --eval "db.vehicles.countDocuments(); db.zones.countDocuments(); db.spaces.countDocuments();"
```

Expected evidence:

- replica-set output includes `mongo1:27017`, `mongo2:27018`, and `mongo3:27019`
- one member is `PRIMARY` and the other available members are `SECONDARY`
- sample data counts are non-zero for `vehicles`, `zones`, and `spaces`

## 8. RabbitMQ Text Evidence

Use these commands to collect queue evidence without screenshots:

```powershell
.\scripts\setup-rabbitmq-cluster.ps1
docker exec rabbitmq1 rabbitmqctl cluster_status
docker exec rabbitmq1 rabbitmq-diagnostics listeners
docker exec rabbitmq1 rabbitmqctl list_queues name type durable arguments leader members
docker exec rabbitmq1 rabbitmqctl list_permissions -p /parking
```

Expected evidence:

- cluster status lists all three RabbitMQ nodes
- listener output includes TLS AMQP and HTTPS management
- no plaintext AMQP listener is active on port `5672`
- `transactions.queue` and `citations.queue` are quorum queues with group size 3
- `customer`, `peo_service`, and `mulligan_admin` have role-specific permissions

## 9. Assignment 3 Recommender and Consensus Evidence

Automated evidence collected on 2026-06-08:

```powershell
.\gradlew.bat :recommender-server:test
.\gradlew.bat :customer-ui:test
```

Both commands passed. The recommender test suite covers:

- recommendation algorithm examples from `Assignment3-Images.pdf`
- requested space available with minimum citations
- requested space available but not minimum
- no available spaces returning an empty list
- multiple equal minimum-citation spaces
- equal-distance ties returning both closest spaces
- busy requested space choosing the best available alternative
- invalid non-numeric and out-of-range parking space input rejection
- consensus all 3 agree -> success
- consensus 2 of 3 agree -> success
- consensus 3 different results -> failure
- consensus 2 different results + 1 missing -> failure
- consensus only leader responds -> failure
- consensus 1 malicious node + 2 honest nodes -> honest majority wins
- consensus 2 malicious/different nodes causing no majority -> failure
- consensus missing node but remaining 2 agree -> success
- exact list equality, not only same first item

Manual/security evidence to collect after Docker startup:

```powershell
.\gradlew.bat build
docker compose up -d
docker compose ps
docker exec rabbitmq1 rabbitmq-diagnostics listeners
docker exec rabbitmq1 rabbitmqctl list_users
docker exec rabbitmq1 rabbitmqctl list_permissions -p /parking
docker exec mongo1 mongosh --tls --tlsCAFile /etc/mongo/certs/ca-cert.pem --eval "rs.status().members.map(m => m.name + ':' + m.stateStr)"
```

Expected recommender security evidence:

- plaintext socket attempts to ports `8091`, `8092`, and `8093` fail because listeners are TLS sockets
- signed TLS `CLIENT_QUERY` messages succeed when HMAC, timestamp, nonce, node identity, and numeric `spaceId` are valid
- missing or invalid HMAC is rejected and logged in the receiving node's persistent recommender log volume
- a reused nonce is rejected as replay and logged
- timestamps older than 60 seconds are rejected and logged
- invalid input, malformed JSON, unsupported fields, and out-of-range spaces are rejected without stack traces or internal exception text reaching the GUI/CLI
- failed TLS/mTLS handshakes are logged with source, receiver node identity, timestamp, and reason where the JVM exposes the source address
- consensus failure/no majority is logged
- recommender logs survive `docker compose down` through the `recommender1-logs`, `recommender2-logs`, and `recommender3-logs` Docker volumes

Expected GUI/CLI evidence:

- Customer GUI shows a visible `Recommend Parking` button
- the customer enters a parking space number and retries invalid input without crashing
- recommendation output displays recommended spaces with citation counts
- Customer CLI option `[5] Recommend Parking` rejects blank, non-numeric, and out-of-range spaces before sending

RabbitMQ/Mongo hardening evidence to include in the final submission:

- RabbitMQ definitions load automatically from `docker/rabbitmq/definitions.json`
- the `guest` account is absent or disabled
- management listeners are restricted to loopback host publishing
- RabbitMQ client and inter-node listeners use TLS
- MongoDB replica-set status shows one primary and secondaries
- direct writes to a Mongo secondary are rejected or documented as rejected by replica-set role/RBAC

## 10. Assignment 3 Docker Runtime Verification

Runtime verification was performed on 2026-06-08 with Docker Desktop running.

### Stack Startup

Command:

```powershell
docker compose up -d --build
docker compose ps
```

Result:

- `docker compose up -d --build` passed after removing stopped name-conflicting containers from the previous Assignment 2 compose project.
- Compose reported `No services to build` because the services use prebuilt images and mounted Gradle jars.
- `docker compose ps` showed `mongo1`, `mongo2`, `mongo3`, `rabbitmq1`, `rabbitmq2`, `rabbitmq3`, `recommender1`, `recommender2`, and `recommender3` running.
- RabbitMQ containers became healthy; recommender containers were running on ports `8091`, `8092`, and `8093`.

### RabbitMQ Hardening

Commands:

```powershell
docker exec rabbitmq1 rabbitmq-diagnostics listeners
docker exec rabbitmq1 rabbitmqctl list_users
docker exec rabbitmq1 rabbitmqctl list_permissions -p /parking
```

Observed result:

```text
Interface: [::], port: 5671, protocol: amqp/ssl
Interface: [::], port: 15671, protocol: https
Interface: 127.0.0.1, port: 15672, protocol: http
```

```text
user             tags
customer         []
mulligan_admin   [administrator]
peo_service      []
```

```text
peo_service      ^(amq\.default|transactions\.queue|citations\.queue)$  ...
customer         ^(amq\.default|transactions\.queue)$                  ...  ^$
mulligan_admin   .*                                                     .*   .*
```

Pass:

- AMQP TLS listener is active on `5671`.
- Plaintext AMQP `5672` is not listed.
- `guest` is not present after definitions are loaded.
- `/parking` exists.
- Service users exist with limited permissions.
- HTTPS management is active on `15671`; HTTP management is bound to container loopback and is not published by compose.

Configuration fix made during verification:

- `docker/rabbitmq/rabbitmq.conf` now enables `management.load_definitions = /etc/rabbitmq/definitions.json`.
- `docker-compose.yml` now mounts `docker/rabbitmq/definitions.json` into each RabbitMQ node.

### MongoDB Replica Set and TLS

Initial bootstrap command:

```powershell
.\docker\mongodb\init-rs.ps1
```

Observed result:

```text
mongo1:27017: PRIMARY
mongo2:27018: SECONDARY
mongo3:27019: SECONDARY
```

After restart, authenticated TLS status command:

```powershell
docker exec mongo1 mongosh "mongodb://mulligan_db_admin:db_pwd_rotated_admin@mongo1:27017/admin?tls=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo1.pem" --eval "rs.status().members.map(m => m.name + ':' + m.stateStr)"
```

Observed result after restart:

```text
mongo1:27017:SECONDARY
mongo2:27018:SECONDARY
mongo3:27019:PRIMARY
```

The exact unauthenticated command from the assignment prompt did not pass after RBAC was enabled:

```powershell
docker exec mongo1 mongosh --tls --tlsCAFile /etc/mongo/certs/ca-cert.pem --eval "rs.status().members.map(m => m.name + ':' + m.stateStr)"
```

Observed failures:

- Without an explicit host, `mongosh` used `127.0.0.1`, which failed strict certificate hostname validation because the certificate does not include `127.0.0.1`.
- With `--host mongo1`, the command then required authentication because RBAC was enabled.

### Mongo Secondary Write Rejection

Command:

```powershell
docker exec mongo2 mongosh "mongodb://mulligan_db_admin:db_pwd_rotated_admin@localhost:27018/parking_db?authSource=admin&tls=true&tlsAllowInvalidHostnames=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo2.pem&directConnection=true" --eval "db.secondary_write_test.insertOne({proof:'secondary-write-rejection', ts:new Date()})"
```

Observed result:

```text
MongoServerError: not primary
```

Pass: direct write to a secondary node was rejected.

### Recommender Startup and Persistent Logs

Commands:

```powershell
docker logs recommender1
docker logs recommender2
docker logs recommender3
docker exec recommender1 sh -c "ls -l /var/log/mulligan"
```

Observed result:

- Each recommender started with the expected node identity and port.
- Each recommender logged `Distributed NonceStore initialized using MongoDB TTL collection`.
- Each recommender logged `verified RabbitMQ TLS connectivity`.
- Each recommender logged `started with TLS/mTLS`.
- Persistent log file exists as Java `FileHandler` output: `/var/log/mulligan/recommender-security.log.0`.

Configuration/code fixes made during verification:

- Recommender runtime profile changed to `QUEUE_SERVER` so it can create the distributed nonce TTL index.
- Recommender TLS was split by role: server listeners present the server certificate, while outgoing mTLS clients present the client certificate.
- Recommender Docker environment now uses `RABBITMQ_NODES=rabbitmq1:5671,rabbitmq2:5671,rabbitmq3:5671` instead of container-local localhost.

### Recommender Runtime Protocol Tests

Signed TLS test client:

- Loaded `docker/rabbitmq/certs/client.p12`.
- Used TLS 1.2.
- Signed JSON messages with HMAC-SHA256 using `.env` `HMAC_SECRET`.

Cases tested:

```text
all normal:
status SUCCESS, result "Request: Space 3\nResult: Space 3;0"

one recommender malicious:
recommender2 started with malicious=true
status SUCCESS, honest result "Request: Space 3\nResult: Space 3;0"

one recommender stopped:
docker compose stop recommender3
status SUCCESS, honest result "Request: Space 3\nResult: Space 3;0"

two recommenders stopped:
docker compose stop recommender2
status FAILURE, reason "No majority consensus reached in cluster."

one malicious plus one missing:
recommender2 malicious=true and recommender3 stopped
status FAILURE, reason "No majority consensus reached in cluster."

invalid parking space:
spaceId=0 was rejected server-side; no stack trace or internal exception text was returned

old timestamp:
timestamp older than 60 seconds was rejected and logged as TIMESTAMP_REJECTED

invalid HMAC:
bad HMAC was rejected and logged as HMAC_REJECTED

replayed nonce:
reused nonce was rejected and logged as REPLAY_REJECTED
```

Security log evidence:

```text
event=HMAC_REJECTED ... reason=invalid HMAC
event=TIMESTAMP_REJECTED ... reason=timestamp older than 60 seconds
event=REPLAY_REJECTED ... reason=replayed nonce
event=CONSENSUS_FAILURE ... reason=no exact-list majority
```

Persistent log check:

```powershell
docker compose down
docker compose up -d
docker exec recommender1 sh -c "ls -l /var/log/mulligan"
```

Observed result:

```text
recommender-security.log.0
recommender-security.log.0.lck
```

Pass: recommender security logs persisted across `docker compose down` and restart through named Docker volumes.

Remaining limitation:

- Runtime malicious mode currently has one fixed malicious result (`Space 999;999`). This allowed runtime verification of one malicious node and malicious-plus-missing no-majority behavior. The specific "two different malicious results" scenario is covered by automated unit tests through direct majority-vote inputs, but the Docker runtime does not currently expose a per-node custom malicious payload selector.
