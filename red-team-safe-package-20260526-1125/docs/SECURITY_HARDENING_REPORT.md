# Security Hardening Report

Date: 2026-05-26  
Repository: `ds-assignment-2-team-5-1`

## Executive Status

| Area | Status | Evidence |
|---|---|---|
| Full Java build and tests | Pass | `.\gradlew.bat --no-daemon clean test build` returned `BUILD SUCCESSFUL` |
| RabbitMQ TLS and least privilege | Pass | Live `rabbitmqctl` checks: `/parking`, no `guest`, TLS listener on `5671`, no plaintext `5672`, quorum queues replicated on 3 members |
| HMAC, nonce, timestamp, replay tests | Pass | Added/ran queue-server and common tests for valid HMAC, invalid/missing HMAC, missing nonce, replay, stale timestamp, malformed JSON |
| Server-side input validation | Fixed | `ValidationUtils` now enforces type-specific payload schemas, required fields, length limits, safe characters, no negative amount/cost |
| CLI safety | Fixed | `runCLI` tasks now use Java 21 toolchain, validated payload builders reject bad input, CLIs exit without JavaFX windows or stack traces |
| Secure logging | Fixed | `SecurityLogger.sanitize` redacts secrets/URIs; third-party Mongo DEBUG logs suppressed by `logback.xml`; logs checked for known secrets |
| MongoDB live cluster validation | Remaining Risk | `mongo1` cannot start because local Windows MongoDB owns `127.0.0.1:27017`; `verify-cluster-health.ps1` reports MongoDB `0/3` in this environment |
| Red Team package | Remaining Risk | Package excludes source and `.git`; includes local demo TLS private keys because Docker TLS cannot run without them |

## Findings And Fixes

| Requirement | Status | Files Checked | Fix Applied | Test Command | Evidence / Result | Notes |
|---|---|---|---|---|---|---|
| TLS 1.2+ for RabbitMQ | Pass | `docker-compose.yml`, `docker/rabbitmq/rabbitmq.conf` | Existing TLS config verified live | `docker exec rabbitmq1 rabbitmq-diagnostics listeners` | Listener includes `5671 amqp/ssl`; `Test-NetConnection 127.0.0.1 -Port 5672` false, `5671` true | Management HTTPS on `15671`; HTTP management bound to container loopback |
| mTLS / client certs | Pass | `docker/rabbitmq/certs`, `AppConfig`, `RabbitMqConnectionManager` | Verified Java smoke publisher with keystore/truststore | `:queue-server:runQueueSmokeTest` with TLS env vars | Published confirmed messages to both queues | Demo cert private keys are local-only secrets |
| HMAC-SHA256 required | Fixed | `SecureMessageSigner`, `MessageEnvelope`, `QueueMessageSecurityValidator`, tests | Removed implicit compiled-in HMAC default; missing `HMAC_SECRET` now fails closed | `.\gradlew.bat --no-daemon clean test build` | Tests pass | `.env` still contains local demo secret for lab use |
| UUID nonce required | Pass | `MessageEnvelope`, `ValidationUtils`, queue tests | Added missing nonce test | `:queue-server:test` | Missing nonce rejected | Nonce format validated as UUID |
| Timestamp freshness <= 60s | Pass | `QueueMessageSecurityValidator`, tests | Existing logic verified | `:queue-server:test` | Old timestamp rejected | Uses configured TTL |
| Replay protection | Pass | `NonceStore`, `QueueMessageSecurityValidator`, tests | Existing logic verified | `:queue-server:test` | Duplicate nonce rejected | Mongo nonce store fallback logs sanitized warning |
| Malformed message rejection | Pass | `QueueConsumerService`, tests | Added malformed/missing field tests | `:queue-server:test` | Malformed envelopes rejected | Rejection is logged server-side |
| Server-side input validation | Fixed | `ValidationUtils`, `QueueConsumerService`, `StorageServerApplication` | Added strict known fields, required fields by message type, max lengths, no bad chars, no negative amount/cost | `:common:test :queue-server:test` | Negative amount, bad space, oversized and injection-like payloads rejected | Applies before queue storage and storage-server persistence |
| Customer CLI validation | Fixed | `CustomerCLI`, `CustomerCLITest` | Uses JSON builder, validates VIN/space/action; stop now asks for space | `:customer-ui:test`, `:customer-ui:runCLI` | Bad space/action rejected; CLI exits cleanly | No JavaFX window opened |
| PEO CLI validation | Fixed | `PEOCLI`, `PEOCLITest` | Uses JSON builder, validates VIN/space/amount/reason | `:peo-ui:test`, `:peo-ui:runCLI` | Negative amount/injection-like reason rejected | Generic user error on publish failure |
| MO CLI safety | Fixed | `MOCLI`, `mo-ui/build.gradle` | Sanitized logged errors; Java 21 launcher added | `:mo-ui:runCLI` | CLI exits cleanly | No GUI launched |
| No stack traces to CLI users | Fixed | CLI classes, common logging paths, `WelcomeController` | Removed/avoided user-facing stack traces in touched paths | CLI run commands | No stack traces shown in exit smoke tests | Some older JavaFX code remains noisy internally but user-facing failures are generic |
| Secure logs | Fixed | `SecurityLogger`, `MongoConnectionManager`, `AppConfig`, logs | Added redaction for passwords, secrets, keys, Mongo/Rabbit URI credentials | `Select-String logs...` | No known passwords/HMAC/private keys/full credential URIs found in checked logs | Existing logs contain auth/rejection events |
| RabbitMQ default credentials | Pass | `scripts/setup-rabbitmq-cluster.ps1`, live broker | Setup deletes `guest`; Java rejects `guest` username | `docker exec rabbitmq1 rabbitmqctl authenticate_user guest guest` | Failed authentication for `guest` | `customer`, `peo_service`, `mulligan_admin` exist |
| RabbitMQ least privilege | Pass | `setup-rabbitmq-cluster.ps1`, live permissions | Verified permissions | `rabbitmqctl list_permissions -p /parking` | `customer` write-only to `transactions.queue`; `peo_service` read/write required queues; admin full | Admin used for setup/smoke, not normal customer client |
| Quorum queues RF=3 | Pass | `RabbitMqTopologyInitializer`, live queues | Verified after smoke publisher | `rabbitmqctl list_queues -p /parking name type durable arguments leader members` | `transactions.queue` and `citations.queue` are `quorum`, durable, members include all 3 nodes | DLQs are quorum too |
| RabbitMQ one-node failure | Pass | Live Docker cluster | Stopped `rabbitmq3`, ran smoke publisher, restarted `rabbitmq3` | `docker stop rabbitmq3`; `:queue-server:runQueueSmokeTest`; `docker start rabbitmq3` | Smoke publish succeeded with one node down; cluster restored to 3 running nodes | Temporary safe failure test only |
| MongoDB auth/TLS | Remaining Risk | `docker-compose.yml`, `docker/mongodb/init-rs.ps1`, `init-users.js`, `seed-data.js` | Config inspection shows `--tlsMode requireTLS`, keyFile, RBAC users | `docker compose config`; `verify-cluster-health.ps1` | Config is hardened, but live replica set cannot be validated here | Port conflict blocks `mongo1` |
| MongoDB unauthenticated access fails | Not Fully Tested | `docker/mongodb/*` | No code change | Attempted live Mongo checks | Inconclusive because `mongo1` is down and replica set is not healthy | Retest after stopping local Windows MongoDB service |
| Source excluded from Red Team package | Pass | Package folder | Created runtime-only package | Package file listing | `.git`, `apps/*/src`, build temp, logs excluded | See package validation doc |

## Live Commands Run

```powershell
.\gradlew.bat --no-daemon clean test build
docker compose up -d
.\scripts\setup-rabbitmq-cluster.ps1
.\scripts\verify-cluster-health.ps1
docker exec rabbitmq1 rabbitmqctl list_vhosts
docker exec rabbitmq1 rabbitmqctl list_users
docker exec rabbitmq1 rabbitmqctl list_permissions -p /parking
docker exec rabbitmq1 rabbitmq-diagnostics listeners
docker exec rabbitmq1 rabbitmqctl authenticate_user guest guest
docker exec rabbitmq1 rabbitmqctl list_queues -p /parking name type durable arguments leader members
Test-NetConnection 127.0.0.1 -Port 5672
Test-NetConnection 127.0.0.1 -Port 5671
.\gradlew.bat --no-daemon :queue-server:runQueueSmokeTest
.\gradlew.bat --no-daemon :customer-ui:runCLI
.\gradlew.bat --no-daemon :peo-ui:runCLI
.\gradlew.bat --no-daemon :mo-ui:runCLI
docker stop rabbitmq3
.\gradlew.bat --no-daemon :queue-server:runQueueSmokeTest
docker start rabbitmq3
```

## Remaining Risks

1. `mongo1` could not start locally because `127.0.0.1:27017` is already used by a Windows MongoDB service. Run PowerShell as Administrator and stop that service, then rerun `docker compose up -d`, `.\docker\mongodb\init-rs.ps1`, and `.\scripts\verify-cluster-health.ps1`.
2. The Red Team package includes demo/local TLS private keys for RabbitMQ and MongoDB because local TLS/mTLS cannot start without them. These must be treated as disposable lab certificates only.
3. The repository still contains older `delivery`, `mulligan-red-team`, and `red-team-delivery` folders with stale insecure examples. They are not included in the new safe package.
4. Demo credentials remain in environment/config scripts for the assignment lab. They are not production-safe and must be rotated for any non-demo deployment.
