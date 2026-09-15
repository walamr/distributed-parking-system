# Mulligan — Distributed & Secure Parking System

> A fault-tolerant, security-hardened distributed parking platform built with Java 21 — featuring a 3-node RabbitMQ Quorum Queue cluster, a MongoDB Replica Set, and a custom Byzantine-fault-tolerant Consensus Protocol over mTLS.

---

![Build](https://img.shields.io/badge/build-passing-brightgreen?style=flat-square)
![Java](https://img.shields.io/badge/Java-21-007396?style=flat-square&logo=openjdk)
![Docker](https://img.shields.io/badge/Docker-Compose-2496ED?style=flat-square&logo=docker)
![RabbitMQ](https://img.shields.io/badge/RabbitMQ-3.13-FF6600?style=flat-square&logo=rabbitmq)
![MongoDB](https://img.shields.io/badge/MongoDB-7.0-47A248?style=flat-square&logo=mongodb)
![Security](https://img.shields.io/badge/Transport-mTLS%20%2B%20TLS%201.2-brightgreen?style=flat-square)
![License](https://img.shields.io/badge/License-MIT-blue?style=flat-square)
![Tests](https://img.shields.io/badge/Tests-Unit%20%2B%20Integration-informational?style=flat-square)

---

## Author

**Walaa Mruwat** — Project Lead, Lead Developer & Team Supervisor

I led the full lifecycle of this project — from architecture decisions and task assignments to hands-on implementation and code review.

**Key responsibilities:**
- 🏗️ **Architecture design** — Defined the distributed system topology: RabbitMQ cluster, MongoDB Replica Set, Recommender consensus layer, and end-to-end TLS security model.
- 👩‍💻 **Lead developer** — Implemented the core user interfaces (Customer, PEO, MO) including both GUI (JavaFX) and CLI modes, and contributed to shared components across all modules.
- 🔍 **Technical supervisor** — Reviewed and guided the implementation of the consensus protocol, security hardening (Blue Team), and DevOps pipelines produced by the rest of the team.
- 🔐 **Security oversight** — Coordinated the Blue Team response after a Red Team exercise exposed 8 vulnerabilities; ensured all fixes met the required security standards before final submission.
- 📋 **Project coordination** — Managed team workflow, deadlines, and integration of all modules into a single cohesive Docker Compose stack.

> Academic team project (5 contributors) — Distributed Systems course, Kinneret College, Semester 2, 5786.

---

## Table of Contents

- [Overview](#overview)
- [Key Features](#key-features)
- [Architecture](#architecture)
- [Project Structure](#project-structure)
- [Prerequisites](#prerequisites)
- [Quick Start](#quick-start)
- [Running the Services](#running-the-services)
- [Testing](#testing)
- [Production Notes](#production-notes)
- [Security & Configuration Isolation](#security--configuration-isolation)
- [Skills Demonstrated](#skills-demonstrated)
- [Architecture & Engineering Highlights](#architecture--engineering-highlights)
- [Contributing](#contributing)
- [License](#license)


---

## Overview

**Mulligan** is a distributed cellular parking system inspired by [Pango](https://www.pango.co.il/), built for Stage 3 of the Distributed Systems course at Kinneret College (Semester 2, 5786).

Three actor types use the system:

| Actor | Capabilities |
|-------|-------------|
| **Customer** | Start / stop parking, view history, request a space recommendation |
| **PEO** (Parking Enforcement Officer) | Verify vehicle legality, issue citations |
| **MO** (Municipal Officer) | Generate transaction & citation reports, monitor cluster health |

The project simultaneously tackles three core distributed-systems problems:

- **Fault-tolerant message brokering** — RabbitMQ Quorum Queues survive single-node failure without data loss.
- **Distributed data storage** — MongoDB Replica Set replicates writes and serves reads from any healthy node.
- **Byzantine-aware consensus** — A custom majority-vote protocol over mTLS tolerates one dishonest Recommender node in a 3-node cluster.

Every component is containerised with Docker Compose and communicates exclusively over an isolated private bridge network. All host-facing ports are bound to `127.0.0.1`.

---

## Key Features

### End-to-End Security
| Control | Detail |
|---------|--------|
| TLS 1.2+ | All channels: AMQP, MongoDB wire, Erlang inter-node traffic |
| mTLS | Mandatory for all server-to-server connections |
| HMAC-SHA256 | Every `MessageEnvelope` is signed; 60-second freshness window + UUID nonce |
| Replay protection | Cluster-wide MongoDB TTL nonce store — **fails closed** (`IllegalStateException`) if unreachable |
| Least-privilege AMQP | Separate accounts per service: `customer`, `peo_service`, `queue_service`, `storage_service`, `mulligan_admin` |

### RabbitMQ — 3-Node Quorum Cluster
- Nodes: `rabbitmq1:5671`, `rabbitmq2:5673`, `rabbitmq3:5674`
- `transactions.queue` and `citations.queue` — declared as `quorum` with `x-quorum-initial-group-size=3`
- Erlang distribution encrypted via `inet_tls`
- Automatic connection recovery and topology recovery in the AMQP client

### MongoDB — 3-Node Replica Set (`rs0`)
- `mongo1:27017` (Primary), `mongo2:27018`, `mongo3:27019` (Secondaries)
- `--tlsMode requireTLS` — plaintext connections rejected at the driver level
- RBAC users scoped per service; no shared admin account in the application path
- Real-time health monitor (every 5 s) embedded in the MO UI

### Recommender Cluster — Custom Consensus Protocol
- Three nodes: `recommender1:8091`, `recommender2:8092`, `recommender3:8093`
- Every node is leader-capable — no single point of failure
- Majority threshold: `⌊N/2⌋ + 1 = 2` for N = 3
- Votes compared on the **full deterministically serialised list**, not just the top item
- **Malicious mode** (`RECOMMENDER_MALICIOUS=true`) injects a fake payload for Byzantine testing

### DevOps
- Gradle multi-module build (8 subprojects, single `.\gradlew.bat` entry point)
- Docker Compose full stack in one command
- GitHub Actions CI pipeline
- PowerShell scripts: cluster setup, health verification, Windows hosts-file management

---

## Architecture

### System Overview

```mermaid
graph TD
    subgraph Clients["Client Applications — Host"]
        CUI["Customer UI / CLI\nJavaFX + Gradle"]
        PUI["PEO UI / CLI\nJavaFX + Gradle"]
        MUI["MO UI / CLI\nJavaFX + Gradle"]
    end

    subgraph RMQ["RabbitMQ Quorum Cluster — TLS AMQP"]
        R1["rabbitmq1\n:5671 AMQPS | :15671 HTTPS"]
        R2["rabbitmq2\n:5673 AMQPS"]
        R3["rabbitmq3\n:5674 AMQPS"]
        R1 <-->|"Erlang TLS :25672"| R2
        R2 <-->|"Erlang TLS"| R3
    end

    subgraph Rec["Recommender Cluster — mTLS"]
        RC1["recommender1\n:8091 leader-capable"]
        RC2["recommender2\n:8092 leader-capable"]
        RC3["recommender3\n:8093 leader-capable"]
        RC1 <-->|"mTLS Consensus"| RC2
        RC2 <-->|"mTLS Consensus"| RC3
    end

    subgraph Back["Backend Services"]
        QS["Queue Server\nTopology initialiser"]
        SS["Storage Server\npersist-then-ack consumer"]
    end

    subgraph DB["MongoDB Replica Set rs0 — TLS Wire"]
        M1["mongo1 :27017\nPrimary"]
        M2["mongo2 :27018\nSecondary"]
        M3["mongo3 :27019\nSecondary"]
        M1 <-->|"Replication TLS"| M2
        M2 <-->|"Replication TLS"| M3
    end

    CUI -->|"AMQPS write-only"| RMQ
    PUI -->|"AMQPS write-only"| RMQ
    CUI -->|"MongoDB TLS read"| DB
    PUI -->|"MongoDB TLS read"| DB
    MUI -->|"MongoDB TLS read"| DB
    CUI -->|"TLS CLIENT_QUERY"| Rec
    Rec -->|"MongoDB TLS read-only"| DB
    RMQ -->|"AMQPS consume"| SS
    RMQ -->|"AMQPS declare topology"| QS
    SS -->|"MongoDB TLS write"| DB
    QS -->|"MongoDB TLS nonce store"| DB
```

### Message Flow

| Source | Message | Destination | Purpose |
|--------|---------|-------------|---------|
| Customer UI/CLI | `MessageEnvelope` (HMAC + nonce) | `transactions.queue` | Start / stop parking |
| PEO UI/CLI | `MessageEnvelope` (HMAC + nonce) | `citations.queue` | Issue a citation |
| Storage Server | Consume + validate | MongoDB | Persist after security checks |
| Customer UI/CLI | `CLIENT_QUERY` (TLS) | Any Recommender node | Request space recommendation |
| Recommender (any) | `FORWARD_QUERY` (mTLS) | Leader | Forward query + local vote |
| Leader | `COLLECT_REQUEST` (mTLS) | Remaining nodes | Gather peer votes |
| Leader | `CLIENT_RESPONSE` | Customer | Consensus list or `FAILURE` |

### Consensus Sequence

```mermaid
sequenceDiagram
    autonumber
    actor C  as Customer App
    participant F  as Recommender (any node)
    participant L  as Leader / Coordinator
    participant F3 as Recommender (third node)

    C->>F:   CLIENT_QUERY {spaceId, hmac, nonce, timestamp}
    Note over F: Computes local recommendation
    F->>L:   FORWARD_QUERY {localResult, hmac, nonce}
    Note over L: Computes local recommendation
    L->>F3:  COLLECT_REQUEST {hmac, nonce}
    F3-->>L: COLLECT_RESPONSE {localResult, hmac, nonce}
    Note over L: Majority vote on full serialised lists
    L-->>F:  CLIENT_RESPONSE {consensusList | FAILURE}
    F-->>C:  CLIENT_RESPONSE (final answer)
```

Threshold: `⌊3/2⌋ + 1 = 2`. If no two nodes agree on the same complete serialised list, the cluster returns `FAILURE`.

---

## Project Structure

```
.
├── apps/
│   ├── common/              # Shared: AppConfig, MessageEnvelope, HMAC, NonceStore, Repos
│   ├── customer-ui/         # Customer JavaFX GUI + CLI
│   ├── peo-ui/              # PEO JavaFX GUI + CLI
│   ├── mo-ui/               # MO JavaFX GUI + CLI (live cluster health panel)
│   ├── queue-server/        # Declares RabbitMQ topology; smoke-test task
│   ├── storage-server/      # Consumes queues, validates HMAC/nonce, persists to MongoDB
│   ├── recommender-server/  # Recommendation engine + consensus protocol
│   └── mulligan-app/        # Top-level assembly module
│
├── docker/
│   ├── rabbitmq/
│   │   ├── rabbitmq.conf          # TLS AMQP config — plaintext port disabled
│   │   ├── definitions.json       # Users, vhost /parking, permissions, quorum queues
│   │   ├── inter_node_tls.config  # Erlang distribution TLS settings
│   │   └── certs/                 # CA cert, server cert, keystores (gitignored — see note)
│   └── mongodb/
│       ├── init-replica.sh        # Initialises rs0, creates RBAC users, seeds data
│       ├── seed-data.js           # Sample vehicles, zones, spaces
│       └── certs/                 # CA cert + per-node PEM bundles (gitignored — see note)
│
├── env-configs/             # Per-service env templates (gitignored — fill placeholders)
│   ├── customer.env
│   ├── peo.env
│   ├── mo.env
│   ├── queue-server.env
│   └── storage-server.env
│
├── scripts/
│   ├── setup-rabbitmq-cluster.ps1  # Joins rabbitmq2 & rabbitmq3 into the cluster
│   ├── verify-cluster-health.ps1   # Checks MongoDB rs status + RabbitMQ cluster
│   └── add-hosts.ps1               # Adds container hostnames to Windows hosts file
│

├── docker-compose.yml       # Full stack: 3×RMQ + 3×Mongo + 3×Recommender + queue-server + storage-server
├── .env.example             # Environment variable template — NO real secrets
├── network-ips.env          # Network IP template — NO real IPs (gitignored)
├── settings.gradle          # Subproject declarations (8 modules)
├── build.gradle             # Root Gradle configuration
│
├── ConsensusProtocolDesign.md   # Sequence diagrams, message schema, voting algorithm
├── DatabaseDesign.md            # MongoDB schema, indexes, recommender queries
├── QueueServerDesign.md         # RabbitMQ topology, security model, client failover
├── Defense.md                   # Blue Team report: 8 vulnerabilities found and fixed
├── Testing.md                   # Test plan and evidence
└── DEPLOY.md                    # Full deployment and verification guide
```

> **Note on TLS certs:** The `docker/rabbitmq/certs/` and `docker/mongodb/certs/` directories contain self-signed lab certificates included for local development only. Private keys and keystores are excluded from the public repository via `.gitignore`. Regenerate all certificates before any shared deployment.

---

## Prerequisites

### Software

| Dependency | Version | Purpose |
|-----------|---------|---------|
| Java JDK | **21** | Build and run all Java modules |
| Docker Desktop | **24+** | Run all infrastructure containers |
| Docker Compose | bundled | Orchestrate the full stack |
| Git | any | Clone the repository |
| PowerShell | **5.1+** or PS Core | Cluster setup and health-check scripts |

### System Resources

| Resource | Minimum | Recommended |
|----------|---------|-------------|
| RAM | 8 GB | 16 GB |
| Disk | 4 GB | 8 GB |
| Free ports | — | 5671, 5673, 5674, 15671, 27017–27019, 8091–8093 |

> **Windows only:** If `mongo1`, `rabbitmq1`, etc. do not resolve, run once as Administrator:
> ```powershell
> .\scripts\add-hosts.ps1
> ```

---

## Quick Start

```powershell
# 1. Clone
git clone https://github.com/Kinneret-OSCourse/ds-assignment-3-team-5-1.git
cd ds-assignment-3-team-5-1

# 2. Configure environment (see Running the Services → Environment Setup)
Copy-Item .env.example .env
#    Open .env and fill in RABBITMQ_ERLANG_COOKIE and HMAC_SECRET

# 3. Build all JARs
.\gradlew.bat clean build

# 4. Start the full stack
docker compose up -d

# 5. Form the RabbitMQ cluster (run once after first startup)
.\scripts\setup-rabbitmq-cluster.ps1

# 6. Launch the Customer UI
.\gradlew.bat :customer-ui:run
```

---

## Running the Services

### Environment Setup

> [!CAUTION]
> **Never commit secrets to Git.** `.env`, `env-configs/*.env`, and `network-ips.env` are all listed in `.gitignore`. For shared environments use **HashiCorp Vault**, **AWS Secrets Manager**, or **GitHub Actions Encrypted Secrets** instead of plain files.

Copy `.env.example` to `.env` and fill in the required values:

```env
# ── RabbitMQ ────────────────────────────────────────────────────────────────
# Generate a random cookie (PowerShell):
#   [System.BitConverter]::ToString((1..32 | % {Get-Random -Max 256})) -replace '-'
RABBITMQ_ERLANG_COOKIE=REPLACE_WITH_64_CHAR_RANDOM_COOKIE

RABBITMQ_NODES=localhost:5671,localhost:5673,localhost:5674
RABBITMQ_VHOST=/parking
RABBITMQ_TLS_ENABLED=true
RABBITMQ_TRUSTSTORE_PATH=docker/rabbitmq/certs/truststore.jks
RABBITMQ_TRUSTSTORE_PASSWORD=REPLACE_WITH_TRUSTSTORE_PASSWORD
RABBITMQ_KEYSTORE_PATH=docker/rabbitmq/certs/keystore.jks
RABBITMQ_KEYSTORE_PASSWORD=REPLACE_WITH_KEYSTORE_PASSWORD
RABBITMQ_TLS_ALLOW_INVALID_HOSTNAMES=false

# ── MongoDB ──────────────────────────────────────────────────────────────────
MONGO_URI=mongodb://customer_db_user:PASSWORD@mongo1:27017,mongo2:27018,mongo3:27019/parking_db?replicaSet=rs0&authSource=admin
MONGO_TLS_ENABLED=true
MONGO_TLS_CA_CERT_PATH=docker/mongodb/certs/ca-cert.pem
MONGO_TLS_ALLOW_INVALID_HOSTNAMES=false

# ── Application secrets ──────────────────────────────────────────────────────
# Generate: python3 -c "import secrets; print(secrets.token_hex(32))"
HMAC_SECRET=REPLACE_WITH_MIN_32_CHAR_RANDOM_SECRET
NONCE_TTL_SECONDS=1200
```

### 1. Start all containers

```powershell
docker compose up -d
docker compose ps
```

Expected services: `rabbitmq1/2/3`, `mongo1/2/3`, `mongo-init`, `recommender1/2/3`, `queue-server`, `storage-server`

### 2. Form the RabbitMQ cluster

```powershell
# Automated
.\scripts\setup-rabbitmq-cluster.ps1

# Manual equivalent
docker exec rabbitmq2 rabbitmqctl stop_app
docker exec rabbitmq2 rabbitmqctl reset
docker exec rabbitmq2 rabbitmqctl join_cluster rabbit@rabbitmq1
docker exec rabbitmq2 rabbitmqctl start_app

docker exec rabbitmq3 rabbitmqctl stop_app
docker exec rabbitmq3 rabbitmqctl reset
docker exec rabbitmq3 rabbitmqctl join_cluster rabbit@rabbitmq1
docker exec rabbitmq3 rabbitmqctl start_app
```

### 3. Verify cluster health

```powershell
# RabbitMQ
docker exec rabbitmq1 rabbitmqctl cluster_status
docker exec rabbitmq1 rabbitmqctl list_queues name type durable arguments leader members
docker exec rabbitmq1 rabbitmqctl list_permissions -p /parking

# MongoDB
.\scripts\verify-cluster-health.ps1
```

### 4. Launch the UIs

```powershell
.\gradlew.bat :customer-ui:run      # Customer — GUI
.\gradlew.bat :customer-ui:runCLI   # Customer — CLI
.\gradlew.bat :peo-ui:run           # PEO — GUI
.\gradlew.bat :mo-ui:run            # MO — GUI
```

### 5. Backend servers on the host (optional — Docker Compose already starts these)

```powershell
$env:HMAC_SECRET='<your-secret>'
.\gradlew.bat :queue-server:runQueueServer
.\gradlew.bat :storage-server:runStorageServer
```

### 6. Recommender cluster

**Option A — Docker Compose (recommended)**

```powershell
.\gradlew.bat :recommender-server:build
docker compose up -d recommender1 recommender2 recommender3
```

**Option B — host processes**

```powershell
.\gradlew.bat :recommender-server:runRecommenderServer -Dport=8091 -DnodeId=recommender1 -DisLeader=true
.\gradlew.bat :recommender-server:runRecommenderServer -Dport=8092 -DnodeId=recommender2 -DisLeader=false
.\gradlew.bat :recommender-server:runRecommenderServer -Dport=8093 -DnodeId=recommender3 -DisLeader=false
```

### 7. Space recommendation (Stage 3 feature)

- **GUI:** Click **💡 Recommend Parking** → enter a space number → view ranked results with citation counts.
- **CLI:** Choose `[4] Get Parking Recommendation`.

### 8. Malicious-mode consensus test

```powershell
$env:RECOMMENDER2_MALICIOUS='true'
$env:RECOMMENDER2_PAYLOAD='999;999'
docker compose up -d recommender2
```

The cluster discards the outlier vote and returns a valid result while two honest nodes agree.

### 9. Stop the stack

```powershell
docker compose down          # stop containers
docker compose down -v       # stop + wipe volumes (clean slate)
```

---

## Testing

### Unit and integration tests

```powershell
.\gradlew.bat clean test                # all modules
.\gradlew.bat :queue-server:test        # queue server only
.\gradlew.bat clean test build          # full build + tests
```

### Smoke test

```powershell
$env:RABBITMQ_USERNAME='peo_service'
$env:RABBITMQ_PASSWORD='<peo-service-password>'
$env:HMAC_SECRET='<your-secret>'
.\gradlew.bat :queue-server:runQueueSmokeTest
```

### Node-failure scenarios

**Fail one MongoDB node:**

```powershell
docker stop mongo2
.\scripts\verify-cluster-health.ps1    # replica set still healthy

docker start mongo2
.\scripts\verify-cluster-health.ps1    # all three members restored
```

**Fail one RabbitMQ node:**

```powershell
docker stop rabbitmq1
docker exec rabbitmq2 rabbitmqctl cluster_status
.\gradlew.bat :queue-server:runQueueSmokeTest   # must still pass

docker start rabbitmq1
```

### Consensus test matrix

| # | recommender1 | recommender2 | recommender3 | Expected |
|---|---|---|---|---|
| 1 | Normal | Normal | Normal | ✅ Valid recommendation |
| 2 | Normal | Malicious `999;999` | Normal | ✅ Valid (outlier discarded) |
| 3 | Normal | Malicious | Malicious | ❌ `FAILURE` — no majority |
| 4 | Normal | Stopped | Normal | ✅ Valid (2 votes agree) |
| 5 | Normal | Stopped | Stopped | ❌ `FAILURE` — only 1 vote |
| 6 | `3;1` | `4;1` | `3;1` | ✅ `3;1` wins (2 votes) |
| 7 | `3;1` | `4;1` | `5;1` | ❌ `FAILURE` — 3 distinct lists |

---

## Production Notes

**TLS Certificates**
- Self-signed lab certificates are provided for local development. For production environments, replace with certificates issued by an enterprise PKI or trusted Certificate Authority.
- Enforce strict hostname verification (`ALLOW_INVALID_HOSTNAMES=false`).

**Secrets Management**
- Store production secrets (HMAC keys, passwords, certificates) in external secret management systems such as HashiCorp Vault, AWS Secrets Manager, or Kubernetes Secrets.
- Ensure private TLS keys and cluster cookies are kept isolated from repository code.

**Scaling & High Availability**
- RabbitMQ Quorum Queues rely on Raft consensus and require an odd node count (e.g., 3, 5, 7) to maintain quorum.
- MongoDB Replica Set requires an active Primary node to receive write operations.

**Observability**
- RabbitMQ Management Console: `https://localhost:15671` (HTTPS).
- MongoDB Cluster Health: accessible via `mongosh` or the Municipal Officer (MO) UI live health dashboard.

---

## Security & Configuration Isolation

Sensitive environmental configuration and private key stores are isolated from source code:

| Component / File Pattern | Purpose | Isolation Strategy |
|---|---|---|
| `.env` & `env-configs/*.env` | Service credentials & HMAC secrets | Managed via environment variables; excluded via `.gitignore` |
| `network-ips.env` | Host IP mappings | Template file provided; actual IPs populated locally |
| `docker/rabbitmq/certs/*.pem` | TLS private keys & certificates | Generated locally via script; excluded from public commits |
| `docker/mongodb/certs/mongodb-keyfile` | Inter-node authentication keyfile | Generated per environment deployment |

---

## Skills Demonstrated

| Area | Technical Capabilities |
|------|------------------------|
| Distributed Systems | Multi-tier failure isolation across message broker, database, and consensus layers |
| Message Brokering | RabbitMQ Quorum Queues, TLS AMQP, Erlang cluster encryption, RBAC accounts |
| Database Clustering | MongoDB Replica Set, mandatory TLS (`requireTLS`), RBAC, automatic read failover |
| Consensus / BFT | Custom majority-vote protocol tolerating Byzantine nodes in a 3-node cluster |
| Security Engineering | mTLS, HMAC-SHA256 signatures, fail-closed replay protection, least-privilege AMQP design |
| DevOps & Infrastructure | Docker Compose multi-service topology, GitHub Actions CI, PowerShell automation |
| Java 21 / JavaFX | Multi-module Gradle architecture, GUI and CLI dual-interface implementation |
| Red & Blue Teaming | Formal security audit and full remediation of 8 identified vulnerabilities |

---

## Architecture & Engineering Highlights

### System Overview & Summary

A fault-tolerant, security-hardened distributed parking platform written in Java 21. Built with a 3-node RabbitMQ Quorum Queue cluster, a 3-node MongoDB Replica Set, and a custom mTLS Byzantine-fault-tolerant consensus layer — containerised with Docker Compose and verified under simulated failure scenarios.

### Architectural Decisions

1. **Distributed Consistency & Failure Isolation** — Engineered to maintain data consistency across 9 distributed nodes simultaneously (3 RabbitMQ, 3 MongoDB, 3 Recommender nodes), ensuring unalterable transactions even under single-node hardware failures.

2. **Byzantine Consensus vs. Crash-Fault Protocols** — Standard Raft consensus assumes non-malicious crash-fault behavior. Because distributed recommendation calculations require Byzantine fault tolerance, the consensus layer verifies HMAC-signed responses and compares deterministically serialised recommendation lists, returning `FAILURE` if a quorum cannot reach consensus.

3. **Fail-Closed Security Model** — To prevent replay attacks across cluster nodes, the nonce store is backed by MongoDB TTL collections. If database connectivity is lost, the nonce verification explicitly fails closed (`IllegalStateException`), prioritizing cluster security over degraded execution.

4. **Security Hardening & Vulnerability Remediation** — Evaluated through a Red Team security exercise that identified 8 security vulnerabilities (including credential leakage, mTLS bypasses, and fail-open handlers). All 8 vulnerabilities were remediated in a single Blue Team hardening pass.


---

## Contributing

1. Fork the repository and create a branch: `git checkout -b feature/your-feature`
2. Add JavaDoc to every public and protected method.
3. Ensure all tests pass: `.\gradlew.bat clean test`
4. Open a Pull Request — use the template below.

**Code standards:**
- Java 21; JavaDoc on all public / protected members.
- Never commit `.env`, private TLS keys, keystores, or the Erlang cookie.
- Unit tests for every public method: success path, failure path, and boundary conditions.

**Issue template:**
```
Type:        Bug | Feature | Security
Description: [clear one-line summary]
Reproduce:   [steps, for bugs]
Expected:    [what should happen]
Actual:      [what happens instead]
```

**Pull Request template:**
```
Change:      [short title]
Motivation:  [why is this needed?]
Tests:       [tests added or modified]
Checklist:
  [ ] No .env, keystore, or private key committed
  [ ] All tests pass locally (.\gradlew.bat clean test)
  [ ] JavaDoc updated for changed public/protected methods
```

---

## License

This project is released under the **MIT License** — chosen because it allows employers, researchers, and peers to use, study, and reference the code without legal friction, making it ideal for a public portfolio.

```
MIT License

Copyright (c) 2026 Walaa Mruwat

Permission is hereby granted, free of charge, to any person obtaining a copy of this software
and associated documentation files (the "Software"), to deal in the Software without restriction,
including without limitation the rights to use, copy, modify, merge, publish, distribute,
sublicense, and/or sell copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all copies or
substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING
BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND
NONINFRINGEMENT.
```

---

## Extended Documentation

| Document | Contents |
|----------|---------|
| [DEPLOY.md](DEPLOY.md) | Full cluster setup, verification commands, and service startup guide |
| [ConsensusProtocolDesign.md](ConsensusProtocolDesign.md) | Message schema (JSON), sequence diagrams, voting algorithm, worked examples |
| [QueueServerDesign.md](QueueServerDesign.md) | RabbitMQ topology, security model, client failover design |
| [DatabaseDesign.md](DatabaseDesign.md) | MongoDB layout, collection schemas, Recommender query patterns |
| [Defense.md](Defense.md) | Blue Team report: 8 vulnerabilities, root-cause analysis, fixes applied |
| [Testing.md](Testing.md) | Automated and manual verification evidence |

---

## GitHub Topics

```
distributed-systems  rabbitmq  mongodb  docker  java  javafx  tls  mtls
consensus-protocol   quorum-queues  replica-set  hmac  security  gradle
blue-team  red-team  parking-system  message-broker  fault-tolerance  java21
```

---

*Built at Kinneret College — Distributed Systems, Semester 2, 5786.*