# Database Design Document - Mulligan Parking System

## Team Members
| Name | Student ID | Task Performed | Hours Worked |
| :--- | :--- | :--- | :--- |
| **Walaa Mruwat** | 325224194 | Task 1: User Interfaces | 20 Hours |
| **Hanan Taha** | 212277438 | Task 2: Queue Server | 20 Hours |
| **Aseel Shaheen** | 214228009 | Task 4: Documentation | 20 Hours |
| **Hala Assadi** | 324830967 | Task 3: Database and Storage | 20 Hours |
| **Taqwa Mrowat** | 212804017 | Task 5: Security Hardening | 20 Hours |

## 1. MongoDB Cluster Layout

- MongoDB version: `7.0`
- Replica set: `rs0`
- Nodes:
  - `mongo1`
  - `mongo2`
  - `mongo3`

Host-run Java applications default to:

```text
mongodb://customer_db_user:db_pass_cust_2026@mongo1:27017,mongo2:27018,mongo3:27019/parking_db?replicaSet=rs0&authSource=admin
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
- import sample `vehicles`, `zones`, and `spaces` data through [`docker/mongodb/seed-data.js`](/C:/Users/user/OneDrive%20-%20Kinneret%20Academic%20College/%D7%A9%D7%95%D7%9C%D7%97%D7%9F%20%D7%94%D7%A2%D7%91%D7%95%D7%93%D7%94/ds-assignment-2-team-5-1/docker/mongodb/seed-data.js)

## 6. Real-Time Health Monitoring

The Municipality UI (`mo-ui`) includes a real-time health monitor that periodically (every 5s) pings the cluster nodes:

- **MongoDB Status:** Checks `rs.status()` via the Java driver to count healthy members.
- **RabbitMQ Status:** Attempts a failover-aware connection to verify broker availability.

## 7. Automated Verification

A reproducible health check script is available to verify the cluster state from the command line:

```powershell
.\scripts\verify-cluster-health.ps1
```

## 8. Text Evidence

The database cluster can be verified without screenshots by running:

```powershell
docker compose up -d
.\docker\mongodb\init-rs.ps1
.\scripts\verify-cluster-health.ps1
docker exec mongo1 mongosh "mongodb://mulligan_db_admin:db_pass_admin_99@mongo1:27017/parking_db?authSource=admin&tls=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo1.pem" --eval "rs.status().members.map(m => m.name + ':' + m.stateStr)"
docker exec mongo1 mongosh "mongodb://mulligan_db_admin:db_pass_admin_99@mongo1:27017/parking_db?authSource=admin&tls=true&tlsCAFile=/etc/mongo/certs/ca-cert.pem&tlsCertificateKeyFile=/etc/mongo/certs/mongo1.pem" --eval "db.vehicles.countDocuments(); db.zones.countDocuments(); db.spaces.countDocuments();"
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
