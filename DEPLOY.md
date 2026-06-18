# Deployment and Verification Guide

## Scope

- Course: Distributed Systems
- Semester / Year: Semester 2, 5786
- Assignment: Assignment 2 - Hardened Distributed Infrastructure
- Documentation owner: Aseel Shaheen (ID: 214228009)

This guide now covers both:

- Task 1 runtime setup for Customer UI, PEO UI, and MO UI
- Task 2 runtime setup for RabbitMQ cluster formation, queue-server startup, smoke test, failover, and recovery

## 1. Prerequisites

- Docker Desktop is running
- Java 21 is installed
- PowerShell is available
- Commands are run from the repository root

If Java 21 is not the default JDK:

```powershell
$env:JAVA_HOME='C:\Program Files\Eclipse Adoptium\jdk-21.0.10.7-hotspot'
$env:Path="$env:JAVA_HOME\bin;$env:Path"
.\gradlew.bat -version
```

## 2. Start Docker Services

```powershell
docker compose down -v
docker compose up -d
docker compose ps
```

Expected result:

- `rabbitmq1`, `rabbitmq2`, and `rabbitmq3` are running
- `mongo1`, `mongo2`, and `mongo3` are running

Docker Compose uses official Docker Hub images for the infrastructure services:

- `rabbitmq:3.13-management`
- `mongo:7.0`

There are no custom application Dockerfiles in the current Stage 2 deployment. This is intentional and documented: the clustered infrastructure runs in Docker, while the Java 21 applications are built and executed on the host through Gradle. Therefore, the red-team package includes `docker-compose.yml`, RabbitMQ/MongoDB config, scripts, certificates, and compiled Java artifacts, but no fake unused Dockerfile.

The compose network `rabbitmq-internal` is marked `internal: true`. Required access from the host is provided only through explicit loopback port bindings such as `127.0.0.1:5671`, `127.0.0.1:15671`, and `127.0.0.1:27017`.

Existing RabbitMQ evidence:

- `docs/evidence/rabbitmq/01a-docker-compose-clean-reset-and-startup.png`
- `docs/evidence/rabbitmq/01b-docker-compose-all-services-running-healthy.png`

## 3. Create the RabbitMQ Cluster Reproducibly

Run the repository setup script after `docker compose up -d`:

```powershell
.\scripts\setup-rabbitmq-cluster.ps1
```

What the script does:

- waits for RabbitMQ containers to respond
- joins `rabbitmq2` to `rabbitmq1`
- joins `rabbitmq3` to `rabbitmq1`
- prints `cluster_status`
- prints queue, user, and permission summaries

Manual equivalent if the script cannot be used:

```powershell
docker exec rabbitmq2 rabbitmqctl stop_app
docker exec rabbitmq2 rabbitmqctl reset
docker exec rabbitmq2 rabbitmqctl join_cluster rabbit@rabbitmq1
docker exec rabbitmq2 rabbitmqctl start_app

docker exec rabbitmq3 rabbitmqctl stop_app
docker exec rabbitmq3 rabbitmqctl reset
docker exec rabbitmq3 rabbitmqctl join_cluster rabbit@rabbitmq1
docker exec rabbitmq3 rabbitmqctl start_app
```

## 4. Initialize the MongoDB Replica Set

```powershell
.\docker\mongodb\init-rs.ps1
```

This creates the `rs0` replica set using the TLS-enabled MongoDB containers.
It also creates the MongoDB RBAC users and imports sample `vehicles`, `zones`, and `spaces` data.

## 5. Shared Runtime Environment

### MongoDB

Host-run applications default to:

```text
mongodb://customer_db_user:db_pwd_rotated_cust@mongo1:27017,mongo2:27018,mongo3:27019/parking_db?replicaSet=rs0&authSource=admin
```

Default MongoDB TLS values in the current code:

- `MONGO_TLS_ENABLED=true`
- `MONGO_TLS_CA_CERT_PATH=docker/mongodb/certs/ca-cert.pem`
- `MONGO_TLS_ALLOW_INVALID_HOSTNAMES=false` (Strict verification enabled)

Override examples:

```powershell
# Note: The system automatically builds the URI with correct RBAC credentials based on the application profile.
$env:MONGO_TLS_ENABLED='true'
$env:MONGO_TLS_CA_CERT_PATH='docker/mongodb/certs/ca-cert.pem'
$env:MONGO_TLS_ALLOW_INVALID_HOSTNAMES='false'
```

If Windows cannot resolve `mongo1`, `mongo2`, `mongo3`, `rabbitmq1`, `rabbitmq2`, or `rabbitmq3`, run PowerShell as Administrator once:

```powershell
.\scripts\add-hosts.ps1
```

### RabbitMQ

RabbitMQ host ports:

- `rabbitmq1`: `5671`
- `rabbitmq2`: `5673`
- `rabbitmq3`: `5674`

RabbitMQ HTTPS management ports:

- `rabbitmq1`: `15671`
- `rabbitmq2`: `15673`
- `rabbitmq3`: `15674`

Shared RabbitMQ values:

```powershell
$env:RABBITMQ_VHOST='/parking'
$env:RABBITMQ_NODES='localhost:5671,localhost:5673,localhost:5674'
$env:RABBITMQ_TLS_ENABLED='true'
$env:RABBITMQ_CONNECTION_TIMEOUT_MS='5000'
$env:RABBITMQ_RECOVERY_INTERVAL_MS='5000'
```

## 6. Verify RabbitMQ Cluster Status

```powershell
docker exec rabbitmq1 rabbitmqctl cluster_status
docker exec rabbitmq1 rabbitmq-diagnostics listeners
docker exec rabbitmq1 rabbitmqctl list_feature_flags
```

Expected result:

- running nodes include `rabbit@rabbitmq1`, `rabbit@rabbitmq2`, and `rabbit@rabbitmq3`
- only TLS AMQP listeners are exposed externally on `5671`, `5673`, and `5674`
- HTTPS management listeners are active on `15671`, `15673`, and `15674`

Existing evidence:

- `docs/evidence/rabbitmq/02a-rabbitmq-cluster-status-3-running-nodes.png`
- `docs/evidence/rabbitmq/02b-rabbitmq-cluster-listeners-and-tls-ports.png`
- `docs/evidence/rabbitmq/02c-rabbitmq-cluster-feature-flags-enabled.png`

## 7. Verify Quorum Queues

```powershell
docker exec rabbitmq1 rabbitmqctl list_queues name type durable arguments leader members
```

Expected result:

- `transactions.queue` exists
- `citations.queue` exists
- both queues have type `quorum`
- both queues include `x-quorum-initial-group-size=3`
- both queues show members across the 3-node cluster

Existing evidence:

- `docs/evidence/rabbitmq/03-quorum-queues-replicated-3-members.png`

## 8. Verify RabbitMQ Users and Permissions

```powershell
docker exec rabbitmq1 rabbitmqctl list_users
docker exec rabbitmq1 rabbitmqctl list_permissions -p /parking
```

Expected result:

- `customer` has only `transactions.queue` publish permissions
- `peo_service` has `transactions.queue` and `citations.queue` access
- `mulligan_admin` has admin permissions for queue-server topology setup

Existing evidence:

- `docs/evidence/rabbitmq/04-rabbitmq-users-and-permissions.png`

## 9. Run the Storage Server

Storage Server defaults to the `peo_service` RabbitMQ account and the TLS-enabled MongoDB replica set.

```powershell
$env:HMAC_SECRET='change-me-for-real-deployments'
.\gradlew.bat :storage-server:runStorageServer
```

Expected result:

- the service connects to the RabbitMQ node list
- the service connects to the MongoDB replica set over TLS
- the service consumes both required queues

## 10. Run the Queue Server

Queue Server defaults to the `mulligan_admin` RabbitMQ account because it declares topology and consumes both queues.

```powershell
$env:HMAC_SECRET='change-me-for-real-deployments'
.\gradlew.bat :queue-server:runQueueServer
```

Expected result:

- the queue server connects to the cluster node list
- the queue server declares `transactions.queue` and `citations.queue`
- the queue server starts long-running consumers with automatic RabbitMQ recovery enabled

Existing evidence:

- `docs/evidence/rabbitmq/05-queue-server-running-consumers.png`

## 11. Run the UIs

### Customer UI

Defaults:

- RabbitMQ account: `customer`
- queues used: `transactions.queue`
- MongoDB access: TLS-enabled replica set

```powershell
.\gradlew.bat :customer-ui:run
```

### PEO UI

Defaults:

- RabbitMQ account: `peo_service`
- queues used: `citations.queue`
- MongoDB access: TLS-enabled replica set

```powershell
.\gradlew.bat :peo-ui:run
```

### MO UI

Defaults:

- MongoDB access only
- no RabbitMQ connection is created in the current MO UI code

```powershell
.\gradlew.bat :mo-ui:run
```

## 12. Verify Task 1 Behavior Manually

### Customer UI

Verify:

- rate and area lookup works through the MongoDB replica set
- start parking publishes successfully
- stop parking publishes successfully
- history loads even when older records still store `payload` as a string

### PEO UI

Verify:

- legality checks return results from the MongoDB replica set
- citation issue publishes successfully to `citations.queue`
- citation flow still works when queue access is provided only through `peo_service`

### MO UI

Verify:

- transaction report loads from MongoDB
- citation report loads from MongoDB
- reports tolerate both legacy string payloads and nested MongoDB payload documents

## 13. Run the Smoke Test

```powershell
$env:RABBITMQ_USERNAME='peo_service'
$env:RABBITMQ_PASSWORD='peo_secure_pass_2026'
$env:HMAC_SECRET='change-me-for-real-deployments'
.\gradlew.bat :queue-server:runQueueSmokeTest
```

Existing evidence:

- `docs/evidence/rabbitmq/06-smoke-test-published-successfully.png`
- `docs/evidence/rabbitmq/07-queues-after-smoke-test-consumers-attached.png`

## 14. Failure and Recovery Test

### Fail one MongoDB node

```powershell
docker stop mongo2
.\scripts\verify-cluster-health.ps1
```

Expected result:

- the replica set remains available with the remaining nodes
- host-run applications can still query the database cluster
- write operations continue through the elected primary

### Recover the stopped MongoDB node

```powershell
docker start mongo2
.\scripts\verify-cluster-health.ps1
```

Expected result:

- `mongo2` rejoins the replica set
- the replica set returns to three members
- the health script reports the recovered cluster state

### Fail one RabbitMQ node

```powershell
docker stop rabbitmq1
docker exec rabbitmq2 rabbitmqctl cluster_status
docker exec rabbitmq2 rabbitmq-diagnostics listeners
docker exec rabbitmq2 rabbitmqctl list_feature_flags
docker exec rabbitmq2 rabbitmqctl list_queues name messages_ready consumers leader members
```

Rerun the smoke test:

```powershell
$env:RABBITMQ_USERNAME='peo_service'
$env:RABBITMQ_PASSWORD='peo_secure_pass_2026'
$env:HMAC_SECRET='change-me-for-real-deployments'
.\gradlew.bat :queue-server:runQueueSmokeTest
```

Expected result:

- cluster continues on the remaining nodes
- smoke test still succeeds
- queue-server consumer connection is expected to recover automatically through the RabbitMQ client recovery mechanism

Existing evidence:

- `docs/evidence/rabbitmq/08a-rabbitmq1-stopped-cluster-running-on-rabbitmq2-rabbitmq3.png`
- `docs/evidence/rabbitmq/08b-rabbitmq-failover-listeners-after-node-failure.png`
- `docs/evidence/rabbitmq/08c-rabbitmq-feature-flags-after-node-failure.png`
- `docs/evidence/rabbitmq/09-queues-after-rabbitmq1-failure.png`
- `docs/evidence/rabbitmq/10-smoke-test-success-after-rabbitmq1-failure.png`

### Recover the stopped node

```powershell
docker start rabbitmq1
docker exec rabbitmq1 rabbitmqctl cluster_status
docker exec rabbitmq1 rabbitmq-diagnostics listeners
docker exec rabbitmq1 rabbitmqctl list_feature_flags
docker exec rabbitmq1 rabbitmqctl list_queues name messages_ready consumers leader members
```

Existing evidence:

- `docs/evidence/rabbitmq/11a-rabbitmq1-recovered-3-nodes-running.png`
- `docs/evidence/rabbitmq/11b-rabbitmq-recovery-listeners-and-no-partitions.png`
- `docs/evidence/rabbitmq/11c-rabbitmq-feature-flags-after-recovery.png`
- `docs/evidence/rabbitmq/12-queues-after-rabbitmq1-recovery.png`

## 15. Gradle Validation

```powershell
.\gradlew.bat clean test build
.\gradlew.bat :queue-server:test
```

Existing evidence:

- `docs/evidence/rabbitmq/13-gradle-clean-test-build-success.png`
- `docs/evidence/rabbitmq/14-queue-server-tests-success.png`

## 16. Evidence Without Screenshots

The submission does not require manual screenshots to prove deployment. Use these commands as text evidence:

```powershell
.\gradlew.bat clean test build
docker compose config
docker compose up -d
.\scripts\setup-rabbitmq-cluster.ps1
.\docker\mongodb\init-rs.ps1
.\scripts\verify-cluster-health.ps1
docker exec rabbitmq1 rabbitmqctl cluster_status
docker exec rabbitmq1 rabbitmq-diagnostics listeners
docker exec rabbitmq1 rabbitmqctl list_queues name type durable arguments leader members
docker exec rabbitmq1 rabbitmqctl list_permissions -p /parking
```

Expected evidence:

- Gradle prints `BUILD SUCCESSFUL`.
- `docker compose config` shows `rabbitmq-internal` as `internal: true`.
- RabbitMQ cluster status lists `rabbit@rabbitmq1`, `rabbit@rabbitmq2`, and `rabbit@rabbitmq3`.
- RabbitMQ listeners include AMQPS `5671` and HTTPS `15671`, with no plaintext AMQP listener on `5672`.
- Queue listing shows `transactions.queue` and `citations.queue` with type `quorum`.
- MongoDB replica-set health reports three configured members when all nodes are running.

## 17. UI Runtime Verification Checklist

| UI | Command | Text evidence to verify |
| :--- | :--- | :--- |
| Customer UI | `.\gradlew.bat :customer-ui:run` | Window starts; start/stop parking actions publish signed messages; history reads from MongoDB. |
| PEO UI | `.\gradlew.bat :peo-ui:run` | Window starts; legality checks read from MongoDB; citation publish succeeds through `peo_service`. |
| MO UI | `.\gradlew.bat :mo-ui:run` | Window starts; transaction and citation reports load from MongoDB; cluster status labels update. |

If Docker Desktop is not running, do not claim live cluster verification. Start Docker Desktop and rerun the commands in this guide.

## 18. Clustered Recommender Server

The Recommender Server cluster runs inside Docker Compose or can be executed locally on the host via Gradle:

### Option A: Running Clustered Recommender inside Docker Compose

Build the shade jar first:
```powershell
.\gradlew.bat :recommender-server:build
```

Start the containers:
```powershell
docker compose up -d recommender1 recommender2 recommender3
```

This will launch:
- `recommender1` (Leader) on port `8091`
- `recommender2` (Follower) on port `8092`
- `recommender3` (Follower) on port `8093`

### Option B: Running Recommender Server locally on Host

To run a node on the host, execute the Gradle task with node parameters:

```powershell
# Node 1 (Leader / Coordinator)
.\gradlew.bat :recommender-server:runRecommenderServer -Dport=8091 -DnodeId=recommender1 -DisLeader=true

# Node 2 (Follower)
.\gradlew.bat :recommender-server:runRecommenderServer -Dport=8092 -DnodeId=recommender2 -DisLeader=false

# Node 3 (Follower)
.\gradlew.bat :recommender-server:runRecommenderServer -Dport=8093 -DnodeId=recommender3 -DisLeader=false
```

Once running, verify using the updated **Customer CLI** (option `[4] Recommend Parking`) or **Customer GUI** (select node and click `💡 Get Recommendation`).
