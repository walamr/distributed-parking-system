# LEGACY / ARCHIVED DOCUMENT

This file describes an older vulnerable Stage 1 attack surface and is retained only as historical context. It is not the current Stage 2 red-team deployment guide. Use the rebuilt `RedTeamMaterials.zip`, root `README.md`, and root `DEPLOY.md` for current instructions.

# Red Team Instructions - Mulligan Parking System

Welcome to the Mulligan Parking System attack surface. Below are the details for the two environments provided for testing.

## Environment 1: Stage 1 Prototype (Vulnerable)
This environment is based on the Assignment 1 code. It is intentionally vulnerable to:
- **Plaintext Sniffing**: No TLS on AMQP or HTTP.
- **Message Forgery**: No signatures; any JSON on the queue is trusted.
- **Replay Attacks**: No nonces or timestamps.
- **Weak Credentials**: Default `guest/guest` or `admin/admin123`.

**Endpoints:**
- RabbitMQ: `localhost:5672`
- REST API: `localhost:8080`

---

## Environment 2: Stage 2 Production (Hardened)
This is the hardened environment with the following defenses:
- **mTLS**: Peer certificate verification required for all connections.
- **HMAC-SHA256**: All messages must be signed using the shared secret.
- **Replay Protection**: Nonces and 60-second window enforced.

**Endpoints:**
- RabbitMQ Cluster: `localhost:5671`, `localhost:5673`, `localhost:5674` (mTLS enabled)
- MongoDB Cluster: `localhost:27017` (TLS enabled)

**Credentials:**
- Use the provided `docker/rabbitmq/definitions.json` and client certificates in `docker/rabbitmq/certs`.

**Setup Data:**
- Sample `vehicles`, `zones`, and `spaces` data are imported by `.\docker\mongodb\init-rs.ps1` via `docker/mongodb/seed-data.js`.

---

## Target Objectives
1. **Unauthorized Parking**: Start/Stop parking for a VIN without owning it.
2. **Citation Deletion**: Remove citation records from the database.
3. **PEO Impersonation**: Issue a citation for a legally parked vehicle.
4. **Denial of Service**: Take down one or more cluster nodes.
