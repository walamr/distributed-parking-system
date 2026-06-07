# LEGACY / ARCHIVED DOCUMENT

This file belongs to an older red-team package snapshot. Use repository-root `QueueServerDesign.md` for the current RabbitMQ TLS/quorum queue design.

# Queue Server Design

## Overview

Mulligan uses RabbitMQ as the assignment queue server for two business event streams:

1. `transactions`
2. `citations`

The queue topology is declared by `queue-service`, and business messages are published by `database-service` after the authoritative backend operation succeeds.

## Actual Runtime Behavior

### Transactions

- A transaction message is published when parking is stopped.
- A transaction message is also published when `startParking` automatically stops a previous open parking event.
- A transaction message is **not** published for the newly started parking event.
- Transaction messages include `eventId` so report readers can deduplicate reliably.

### Citations

- A citation message is published only after a citation is successfully stored.
- A citation is allowed only after a recent matching `Parking Not Ok` investigation by the same authenticated PEO, for the same vehicle and space.

### MO Reports

- The MO UI now connects **directly** to RabbitMQ to pull transaction and citation events.
- This ensures the UI is a first-class citizen of the event-driven architecture.
- To prevent data loss after consumption, the MO UI maintains a persistent local cache of all messages it has retrieved.

## Topology

- **Exchange:** `mulligan-exchange`
- **Type:** direct
- **Durable:** true

### Queues

| Queue | Routing key | Durable |
| --- | --- | --- |
| `transactions` | `transactions` | true |
| `citations` | `citations` | true |

## Producers

- **Customer UI:** Publishes transaction messages directly to the `transactions` queue when a parking event is stopped.
- **PEO UI:** Publishes citation messages directly to the `citations` queue when a citation is issued.

## Topology Initializer

- `queue-service` starts, connects to RabbitMQ, and idempotently declares:
  - exchange
  - queues
  - bindings

## Payloads

### Transaction payload

Maps to `com.mulligan.common.model.TransactionReportMessage`

```json
{
  "eventId": 42,
  "vehicleNumber": "604-95-839",
  "parkingSpaceId": "2",
  "parkingZone": "Zone B",
  "date": "2026-04-25",
  "startTime": "10:00:00",
  "stopTime": "11:00:00",
  "amountOwed": 12.50,
  "generatedAt": "2026-04-25T11:00:00"
}
```

### Citation payload

Maps to `com.mulligan.common.model.CitationReportMessage`

```json
{
  "vehicleNumber": "604-95-839",
  "parkingSpaceId": "2",
  "parkingZone": "Zone B",
  "date": "2026-04-25",
  "inspectionTime": "11:10:00",
  "citationReason": "Overstay",
  "amountOwed": 150.00,
  "generatedAt": "2026-04-25T11:10:00"
}
```

## Failure Handling

- If RabbitMQ is unavailable during stop/citation publishing, the UI continues operation but logs the failure.
- Backend persistence is handled via REST API calls before publishing, ensuring the authoritative state is always saved.

## Security Notes

- Queue names and routing keys are fixed and documented.
- UI/CLI clients publish directly to the broker to satisfy distributed systems decoupling requirements.
