# Queue Server Design

## Student

- Aseel Shaheen
- ID: 214228009
- Task: Queue Server implementation with RabbitMQ integration

## Purpose

The queue server is the Java messaging layer for Assignment 2. In Aseel's scope, it is responsible for connecting to a RabbitMQ cluster, declaring the required quorum queues, consuming messages from both queues, and enforcing message-level security checks before a message is accepted.

## RabbitMQ Topology

The deployment uses three RabbitMQ nodes:

- `rabbitmq1`
- `rabbitmq2`
- `rabbitmq3`

Each node runs on the same Docker network and shares the same Erlang cookie. `rabbitmq1` is the cluster anchor and the repository now includes `scripts/setup-rabbitmq-cluster.ps1` to make the cluster join sequence reproducible after `docker compose up -d`.

## Required Queues

- `transactions.queue`
- `citations.queue`

Both queues are declared as:

- durable
- non-auto-delete
- quorum queues

The queue definitions exist in both RabbitMQ `definitions.json` and the Java topology initializer so the requirement is satisfied even if the broker starts from a fresh local volume.

## Client Failover Strategy

The Java queue server reads `RABBITMQ_NODES` as a comma-separated host list. `ClusterClientSelector` rotates the starting node in round-robin order, and `RabbitMqConnectionManager` tries each configured node until one accepts the connection. This prevents the application from depending on a single hardcoded broker endpoint.

## Consumer Flow

`QueueServerApplication` performs three startup steps:

1. confirm at least one RabbitMQ node is reachable
2. declare the required quorum queues
3. start consumers for `transactions.queue` and `citations.queue`

`QueueConsumerService` registers a manual-ack consumer on both queues. Each message is parsed as a `MessageEnvelope` and then passed to `QueueMessageSecurityValidator`. RabbitMQ automatic recovery and topology recovery are enabled so long-running consumers can recover from a broker-node loss without restarting the Java process.

## Message Security Design

Each message envelope contains:

- `messageId`
- `nonce`
- `timestamp`
- `type`
- `payload`
- `hmac`

The HMAC is calculated with HMAC-SHA256 over the canonical string produced by `MessageEnvelope.toSigningContent()`:

```text
messageId|nonce|timestamp|clientIp|type|payload
```

The queue server accepts a message only if all of the following pass:

- the envelope JSON is valid
- the nonce is a valid UUID
- the message timestamp is not older than 60 seconds
- the message timestamp is not excessively in the future
- the HMAC matches the canonical signing content
- the nonce has not already been seen in the active TTL window

Accepted nonces are stored in `NonceStore` for replay protection.

## Rejection Behavior

Rejected messages are:

- negatively acknowledged with `basicReject(..., false)`
- not requeued
- logged with a short reason only

The queue server does not print message secrets or full stack traces during normal validation failures.

## RabbitMQ Hardening

The repository hardening for this assignment includes:

- no `guest/guest` application usage
- Java-side rejection of username `guest`
- RabbitMQ `loopback_users.guest = false`
- dedicated admin user for management
- least-privilege service users for:
  - `customer`: restricted to `transactions.queue`
  - `peo_service`: restricted to `transactions.queue` and `citations.queue`
  - `mulligan_admin`: full cluster administration

## TLS Status

TLS and mTLS are fully enabled and verified in this deployment. The system enforces peer verification for all connections, and both server and client use signed certificates for mutual authentication.

## Limitations

- this repository only covers Aseel's queue-server scope, not the wider UI or database parts
