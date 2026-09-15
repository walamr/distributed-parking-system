# Database Design Document - Mulligan Parking System

## Team Members
| Name | Student ID | Task Performed | Hours Worked |
| :--- | :--- | :--- | :--- |
| **Walaa Mruwat** | 325224194 | Task 1: User Interfaces | 22 Hours |
| **Hanan Taha** | 212277438 | Task 2: Recommender Server | 20 Hours |
| **Aseel Shaheen** | 214228009 | Task 4: Documentation & DevOps | 21 Hours |
| **Hala Assadi** | 324830967 | Task 3: Consensus Protocol | 19 Hours |
| **Taqwa Mrowat** | 212804017 | Task 5: Security Hardening (Blue Teaming) | 18 Hours |

## 1. MongoDB Cluster Layout

- MongoDB version: `7.0`
- Replica set: `rs0`
- Nodes:
  - `mongo1`
  - `mongo2`
  - `mongo3`

Host-run Java applications default to:

```text
mongodb://customer_db_user:db_pwd_rotated_cust@mongo1:27017,mongo2:27018,mongo3:27019/parking_db?replicaSet=rs0&authSource=admin
```

### Collections Diagram

The `parking_db` database holds five collections. Reference data (`vehicles`, `zones`, `spaces`) is seeded at init; event data (`transactions`, `citations`) is written only by the storage server and linked to reference data through `spaceId` / `vehicleId`.

```mermaid
erDiagram
    ZONES ||--o{ SPACES : "contains (zoneName)"
    SPACES ||--o{ TRANSACTIONS : "spaceId"
    SPACES ||--o{ CITATIONS : "spaceId"
    VEHICLES ||--o{ TRANSACTIONS : "vehicleId"
    VEHICLES ||--o{ CITATIONS : "vehicleId"

    ZONES {
        string zoneId
        string zoneName
        double hourlyRate
    }
    SPACES {
        string spaceId
        string zoneId
        string zoneName
        double hourlyRate
    }
    VEHICLES {
        string vehicleId
        string owner
        string accountType
    }
    TRANSACTIONS {
        string correlationId
        string messageId
        long timestamp
        object payload
    }
    CITATIONS {
        string correlationId
        string messageId
        long timestamp
        object payload
    }
```

## 2. MongoDB TLS for Host-Run Apps

The Docker MongoDB containers require TLS. The shared Java configuration now defaults to:

- `MONGO_TLS_ENABLED=true`
- `MONGO_TLS_CA_CERT_PATH=docker/mongodb/certs/ca-cert.pem`
- `MONGO_TLS_ALLOW_INVALID_HOSTNAMES=false`

This allows the host-run UIs and storage server to connect to the local Docker replica set without requiring manual TLS toggles.

## 3. Data Storage Shape

Messages consumed by the storage server are persisted into:

- `transactions`
- `citations`

Current storage behavior:

- when `payload` is valid JSON object text, it is stored as a nested MongoDB `Document`
- when older or unexpected data provides a raw string payload, the raw string is preserved

Repository reads are backward-compatible with both shapes so that:

- Customer UI history keeps working
- PEO legality checks keep working
- MO transaction and citation reports keep working

## 4. Query Behavior Used by Task 1

The shared repository provides:

- parking-space rate lookup
- parking-space zone lookup
- vehicle legality checks
- customer history lookup
- municipality transaction reports
- municipality citation reports

Compatibility note:

- new records can be queried through nested payload fields
- legacy string payloads are parsed during repository reads when possible

## 5. Initialization

The replica set is initialized with:

```powershell
.\docker\mongodb\init-rs.ps1
```

The initialization flow now performs all three setup steps required for runtime use:

- create the `rs0` replica set
- create the MongoDB RBAC users
- import sample `vehicles`, `zones`, and `spaces` data through [`docker/mongodb/seed-data.js`](docker/mongodb/seed-data.js)

## 6. Real-Time Health Monitoring

The Municipality UI (`mo-ui`) includes a real-time health monitor that periodically (every 5s) pings the cluster nodes:

- **MongoDB Status:** Checks `rs.status()` via the Java driver to count healthy members.
- **RabbitMQ Status:** Attempts a failover-aware connection to verify broker availability.

## 7. Automated Verification

A reproducible health check script is available to verify the cluster state from the command line:

```powershell
.\scripts\verify-cluster-health.ps1
```

## 8. Cluster Health Verification

The database cluster status can be verified by running the following commands:


```powershell
docker compose up -d
.\docker\mongodb\init-rs.ps1
.\scripts\verify-cluster-health.ps1
docker exec mongo1 mongosh "mongodb://mulligan_db_admin:db_pwd_rotated_admin@mongo1:27017/parking_db?authSource=admin&tls=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo1.pem" --eval "rs.status().members.map(m => m.name + ':' + m.stateStr)"
docker exec mongo1 mongosh "mongodb://mulligan_db_admin:db_pwd_rotated_admin@mongo1:27017/parking_db?authSource=admin&tls=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo1.pem" --eval "db.vehicles.countDocuments(); db.zones.countDocuments(); db.spaces.countDocuments();"
```

Expected output:

- three replica-set members: `mongo1`, `mongo2`, and `mongo3`
- one primary and two secondaries when all containers are healthy
- non-zero sample data counts for vehicles, zones, and spaces

## 9. Schemas and Important Queries

### Database Schemas (NoSQL Collections)

**1. `vehicles` Collection**
```json
{
  "vehicleId": "String (e.g., '604-95-839')",
  "owner": "String (e.g., 'Jose Morris')",
  "accountType": "String (e.g., 'customer')"
}
```

**2. `zones` Collection**
```json
{
  "zoneId": "String (e.g., '1')",
  "zoneName": "String (e.g., 'Magnolia Way')",
  "hourlyRate": "Double (e.g., 1.77)"
}
```

**3. `spaces` Collection**
```json
{
  "spaceId": "String (e.g., '1')",
  "zoneId": "String (e.g., '1')",
  "zoneName": "String (e.g., 'Magnolia Way')",
  "hourlyRate": "Double (e.g., 1.77)"
}
```

**4. `transactions` & `citations` Collections**
```json
{
  "_id": "ObjectId",
  "correlationId": "String (UUID)",
  "messageId": "String (UUID)",
  "timestamp": "Long (Epoch Time)",
  "payload": {
    "vehicleId": "String",
    "spaceId": "String",
    "reason": "String (for citations)",
    "cost": "String/Double",
    "startTime": "Long",
    "stopTime": "Long"
  }
}
```

### Important Queries

**Find Active Parking Session for a Vehicle:**
```javascript
db.transactions.find({
    "payload.vehicleId": "604-95-839",
    "payload.stopTime": { $exists: false }
}).sort({ timestamp: -1 }).limit(1)
```

**Find Citations by Zone:**
```javascript
db.citations.find({
    "payload.areaName": "Magnolia Way"
})
```

**Check Vehicle Legality (PEO Check):**
```javascript
db.transactions.find({
    "payload.vehicleId": "604-95-839",
    "payload.startTime": { $lte: CURRENT_TIME },
    $or: [
        { "payload.stopTime": { $exists: false } },
        { "payload.stopTime": { $gt: CURRENT_TIME } }
    ]
})
```

## 10. Recommender Query Access Patterns (Assignment 3)

Each recommender node computes its local recommendation by reading from the same replica set over TLS (read preference `primaryPreferred`). The recommender never writes to the database; it only reads, so it requires read-only access to `spaces`, `transactions`, and `citations`. The computation for a requested space proceeds in three batched steps inside `loadAvailableCandidates`:

**1. All spaces in the requested zone** (the zone is resolved from the requested space):
```javascript
db.spaces.find({ "zoneName": "Magnolia Way" })
```

**2. Latest transaction per space** — a single aggregation returns the most recent transaction for every space in the zone, so a space whose latest action is `start` is treated as occupied and excluded:
```javascript
db.transactions.aggregate([
  { $match: { $or: [ { "payload.spaceId": { $in: spaceIds } },
                     { "spaceId": { $in: spaceIds } } ] } },
  { $sort:  { timestamp: -1, storedAt: -1 } },
  { $group: { _id: { $cond: [ { $ne: [ { $ifNull: ["$payload.spaceId", ""] }, "" ] },
                              "$payload.spaceId", "$spaceId" ] },
              latestDoc: { $first: "$$ROOT" } } }
])
```

**3. Citation counts per space** — citations for all candidate spaces are fetched in one query and tallied per space:
```javascript
db.citations.find({ $or: [ { "payload.spaceId": { $in: spaceIds } },
                           { "spaceId": { $in: spaceIds } } ] })
```

The node then applies the Use-Case-8 ranking in memory: keep only available spaces, select those with the **minimum citation count**, then among those choose the **smallest absolute distance** from the requested space number, breaking ties by returning all equally-close spaces sorted numerically. Both the nested-payload and legacy flat field shapes are matched (`payload.spaceId` and `spaceId`) so the recommender works against records written by either assignment stage. When the database is offline the same logic runs against the repository's offline path, returning a deterministic result for testing.

The identical recommendation is computed independently on every node; the consensus layer (see [ConsensusProtocolDesign.md](ConsensusProtocolDesign.md)) then reconciles the per-node lists by majority vote.
