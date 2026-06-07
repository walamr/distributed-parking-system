# RabbitMQ Testing Plan

## Document Owner

- Aseel Shaheen
- ID: 214228009
- Scope: Task 4 documentation support for RabbitMQ testing evidence

## Automated Verification

Run the local Java tests first:

```powershell
.\gradlew.bat clean test build
```

The automated test suite covers:

- HMAC signing and verification
- queue-message envelope parsing
- nonce TTL behavior
- UUID nonce validation
- round-robin node selection
- app-profile RabbitMQ credential defaults
- MongoDB TLS default parsing
- repository compatibility with nested and legacy payload storage
- RabbitMQ connection recovery factory configuration
- quorum queue argument declaration
- valid message acceptance
- invalid HMAC rejection
- expired timestamp rejection
- replayed nonce rejection

## Manual RabbitMQ Cluster Verification

### 1. Start the Containers

```powershell
docker compose up -d
docker compose ps
```

### 2. Join the 3-Node Cluster

```powershell
.\scripts\setup-rabbitmq-cluster.ps1
```

### 3. Verify Cluster Status

```powershell
docker exec rabbitmq1 rabbitmqctl cluster_status
```

Expected result:

- three running nodes
- `rabbit@rabbitmq1`, `rabbit@rabbitmq2`, and `rabbit@rabbitmq3` are members of the same cluster

### 4. Verify Quorum Queues

```powershell
docker exec rabbitmq1 rabbitmqctl list_queues name type durable arguments leader members
```

Expected result:

- `transactions.queue` reports `quorum`
- `citations.queue` reports `quorum`
- both queues report `true` for durability

### 5. Verify Users and Permissions

```powershell
docker exec rabbitmq1 rabbitmqctl list_users
docker exec rabbitmq1 rabbitmqctl list_permissions -p /parking
```

Expected result:

- queue users exist
- admin user exists
- permissions are limited to the required queues instead of `.*`

### 6. Start the Queue Server

```powershell
$env:RABBITMQ_USERNAME='mulligan_admin'
$env:RABBITMQ_PASSWORD='admin_ultra_secure_99'
$env:RABBITMQ_VHOST='/parking'
$env:RABBITMQ_NODES='localhost:5671,localhost:5673,localhost:5674'
$env:RABBITMQ_TLS_ENABLED='true'
$env:RABBITMQ_CONNECTION_TIMEOUT_MS='5000'
$env:HMAC_SECRET='change-me-for-real-deployments'
.\gradlew.bat :queue-server:runQueueServer
```

Expected result:

- startup succeeds
- topology is initialized (mTLS and TLS ports verified)
- consumers begin listening on both required queues
- automatic recovery is enabled for long-running consumer connections

### 7. Publish Valid Signed Messages

Open a second terminal and run:

```powershell
$env:RABBITMQ_USERNAME='peo_service'
$env:RABBITMQ_PASSWORD='peo_secure_pass_2026'
$env:RABBITMQ_VHOST='/parking'
$env:RABBITMQ_NODES='localhost:5671,localhost:5673,localhost:5674'
$env:RABBITMQ_TLS_ENABLED='true'
$env:RABBITMQ_CONNECTION_TIMEOUT_MS='5000'
$env:HMAC_SECRET='change-me-for-real-deployments'
.\gradlew.bat :queue-server:runQueueSmokeTest
```

Expected result:

- messages are published to both queues
- the queue server logs accepted messages

### 8. Validate RabbitMQ Node Failure and Recovery

#### A. Failover Testing
1. **Stop the node**: Shut down one active RabbitMQ node (e.g. `rabbitmq1`):
   ```powershell
   docker stop rabbitmq1
   ```
2. **Verify cluster availability**: Check that the cluster remains operational through the other active nodes:
   ```powershell
   docker exec rabbitmq2 rabbitmqctl cluster_status
   ```
3. **Verify quorum queue replication status**: Query the quorum queues to check that their metadata remains healthy, that a new queue leader has been elected among the remaining nodes, and that no messages are lost:
   ```powershell
   docker exec rabbitmq2 rabbitmqctl list_queues name type messages_ready leader members
   ```
4. **Publish testing**: Execute the smoke test publisher to confirm that it transparently reconnects to the remaining nodes and successfully publishes messages:
   ```powershell
   $env:RABBITMQ_USERNAME='peo_service'
   $env:RABBITMQ_PASSWORD='peo_secure_pass_2026'
   $env:HMAC_SECRET='change-me-for-real-deployments'
   .\gradlew.bat :queue-server:runQueueSmokeTest
   ```

#### B. Recovery Testing
1. **Start the node**: Turn the stopped node back on:
   ```powershell
   docker start rabbitmq1
   ```
2. **Verify rejoin status**: Verify that the node has successfully rejoined the cluster without partitions:
   ```powershell
   docker exec rabbitmq1 rabbitmqctl cluster_status
   ```
3. **Verify replication restoration**: Confirm that the restarted node is automatically added back as an active replica member in the quorum groups:
   ```powershell
   docker exec rabbitmq1 rabbitmqctl list_queues name type leader members
   ```

---

### 9. Validate MongoDB Database Node Failure and Recovery

#### A. Secondary Node Failure and Recovery
1. **Stop a secondary database node**: Stop one of the secondary database nodes (e.g., `mongo2`):
   ```powershell
   docker stop mongo2
   ```
2. **Verify read/write capability**: Execute the cluster verification script to verify database connectivity, writes, and reads remain healthy:
   ```powershell
   .\scripts\verify-cluster-health.ps1
   ```
3. **Restart the secondary node**: Start the container again:
   ```powershell
   docker start mongo2
   ```
4. **Verify replication catches up**: Wait 5 seconds and verify the node transitions back to a healthy `SECONDARY` state and replicates data:
   ```powershell
   .\scripts\verify-cluster-health.ps1
   ```

#### B. Primary Node Failure and Auto-Election
1. **Stop the primary database node**: Identify and stop the current primary replica set node (e.g., `mongo1`):
   ```powershell
   docker stop mongo1
   ```
2. **Verify election of new primary**: Wait 5 seconds, then check the status of the replica set nodes on `mongo2` (port 27018) to verify that either `mongo2` or `mongo3` was elected as the new `PRIMARY`:
   ```powershell
   docker exec mongo2 mongosh "mongodb://mulligan_db_admin:db_pass_admin_99@localhost:27018/parking_db?authSource=admin&tls=true&tlsAllowInvalidHostnames=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo2.pem" --eval "rs.status().members.map(m => m.name + ' is ' + m.stateStr)"
   ```
3. **Verify application failover**: Interact with the UI application or test queries to verify that connections failover automatically to the new primary database node.
4. **Restart the original primary node**: Re-enable the container:
   ```powershell
   docker start mongo1
   ```
5. **Verify restoration**: Check cluster health to ensure all 3 nodes are active again, with `mongo1` successfully rejoining as a `SECONDARY`:
   ```powershell
   .\scripts\verify-cluster-health.ps1
   ```

## TLS Note

TLS and mTLS are fully enabled and verified in this deployment. The RabbitMQ cluster uses the documented host TLS ports `5671`, `5673`, and `5674` for secure communication, and the Java client is configured to use mTLS with the provided certificates. HTTPS management is also enabled.

## Verified Evidence

Final manual verification was completed on 2026-05-14 with these results:

- **Gradle Build**: `BUILD SUCCESSFUL` for both project and tests.
- **Docker Infrastructure**: 3 RabbitMQ nodes and MongoDB replica set are running and healthy.
- **Cluster Status**: 3 disk nodes running (`rabbitmq1`, `rabbitmq2`, `rabbitmq3`) with no alarms.
- **TLS Listeners**: AMQP over TLS and HTTPS management ports are active.
- **Feature Flags**: Quorum queue support is enabled across all nodes.
- **Quorum Queues**: `transactions.queue` and `citations.queue` are replicated across all 3 nodes.
- **User Permissions**: Least-privilege access for `customer` and `peo_service`.
- **Failover**: Cluster remained available and smoke test succeeded after stopping `rabbitmq1`.
- **Recovery**: `rabbitmq1` rejoined the cluster successfully with no partitions.
- **Database Evidence**: MongoDB evidence is not included here and must be provided by the Database/Storage owner.

Evidence files:

- [01a Docker Reset and Startup](evidence/rabbitmq/01a-docker-compose-clean-reset-and-startup.png)
- [01b Docker Services Healthy](evidence/rabbitmq/01b-docker-compose-all-services-running-healthy.png)
- [02a RabbitMQ 3 Running Nodes](evidence/rabbitmq/02a-rabbitmq-cluster-status-3-running-nodes.png)
- [02b TLS and HTTPS Listeners](evidence/rabbitmq/02b-rabbitmq-cluster-listeners-and-tls-ports.png)
- [02c Feature Flags Enabled](evidence/rabbitmq/02c-rabbitmq-cluster-feature-flags-enabled.png)
- [03 Quorum Queues Replicated](evidence/rabbitmq/03-quorum-queues-replicated-3-members.png)
- [04 Users and Permissions](evidence/rabbitmq/04-rabbitmq-users-and-permissions.png)
- [05 Queue Server Running Consumers](evidence/rabbitmq/05-queue-server-running-consumers.png)
- [06 Smoke Test Published Successfully](evidence/rabbitmq/06-smoke-test-published-successfully.png)
- [07 Queues After Smoke Test](evidence/rabbitmq/07-queues-after-smoke-test-consumers-attached.png)
- [08a Failover Rabbitmq1 Stopped](evidence/rabbitmq/08a-rabbitmq1-stopped-cluster-running-on-rabbitmq2-rabbitmq3.png)
- [08b Failover Listeners Active](evidence/rabbitmq/08b-rabbitmq-failover-listeners-after-node-failure.png)
- [08c Failover Feature Flags Enabled](evidence/rabbitmq/08c-rabbitmq-feature-flags-after-node-failure.png)
- [09 Queues After Failure](evidence/rabbitmq/09-queues-after-rabbitmq1-failure.png)
- [10 Smoke Test Success After Failure](evidence/rabbitmq/10-smoke-test-success-after-rabbitmq1-failure.png)
- [11a Rabbitmq1 Recovered](evidence/rabbitmq/11a-rabbitmq1-recovered-3-nodes-running.png)
- [11b Recovery Listeners and No Partitions](evidence/rabbitmq/11b-rabbitmq-recovery-listeners-and-no-partitions.png)
- [11c Recovery Feature Flags Enabled](evidence/rabbitmq/11c-rabbitmq-feature-flags-after-recovery.png)
- [12 Queues After Recovery](evidence/rabbitmq/12-queues-after-rabbitmq1-recovery.png)
- [13 Gradle Build Success](evidence/rabbitmq/13-gradle-clean-test-build-success.png)
- [14 Queue Server Tests Success](evidence/rabbitmq/14-queue-server-tests-success.png)
