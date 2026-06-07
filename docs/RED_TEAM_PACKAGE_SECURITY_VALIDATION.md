# Red Team Package Security Validation

Package path: `red-team-safe-package-20260526-1125.zip`

| File / Folder Included | Why It Is Needed | Contains Secrets? | Safe To Share? | Source Code Excluded? | Notes |
|---|---|---:|---:|---:|---|
| `docker-compose.yml` | Starts MongoDB and RabbitMQ clusters | No | Yes | Yes | Localhost-bound ports; no app source |
| `docker/rabbitmq/rabbitmq.conf` | TLS-only AMQP and management config | No | Yes | Yes | Plain AMQP listener disabled |
| `docker/rabbitmq/enabled_plugins` | Enables RabbitMQ management plugin | No | Yes | Yes | Required for admin checks |
| `docker/rabbitmq/definitions.json` | Demo RabbitMQ users/vhost | Yes | Yes, demo only | Yes | Contains local demo passwords |
| `docker/rabbitmq/certs/*` | Required for RabbitMQ TLS/mTLS local run | Yes | Yes, demo only | Yes | Includes private keys; disposable local certs only |
| `docker/mongodb/init-rs.ps1` | Initializes replica set | No | Yes | Yes | Requires `mongo1` to start |
| `docker/mongodb/init-users.js` | Creates MongoDB demo RBAC users | Yes | Yes, demo only | Yes | Contains local demo passwords |
| `docker/mongodb/seed-data.js` | Loads required demo data | No | Yes | Yes | Needed for UI demos |
| `docker/mongodb/certs/*` | Required for MongoDB TLS local run | Yes | Yes, demo only | Yes | Includes node private keys and keyfile |
| `scripts/setup-rabbitmq-cluster.ps1` | Creates vhost/users/permissions | Yes | Yes, demo only | Yes | Contains local demo RabbitMQ passwords |
| `scripts/verify-cluster-health.ps1` | Health checks for teacher/Red Team | Yes | Yes, demo only | Yes | Contains local demo MongoDB admin URI |
| `apps/*/build/distributions/*.zip` | Runnable Java application/service distributions | No | Yes | Yes | Built artifacts only, no Java source |
| `apps/*/build/libs/*.jar` | Runnable JARs for services/UIs | No | Yes | Yes | Built artifacts only |
| `docs/SECURITY_HARDENING_REPORT.md` | Explains hardening and remaining risks | No | Yes | Yes | Includes exact test results |
| `docs/RED_TEAM_SAFETY_CHECKLIST.md` | Sharing checklist | No | Yes | Yes | Includes caveats |
| `README.md`, `DEPLOY.md`, `Testing.md` | Run/demo instructions | May mention demo credentials | Yes, demo only | Yes | Useful for Red Team setup |

## Excluded

- `.git`
- `apps/**/src`
- `.gradle`, `build`, `gradle-bin`
- `logs`
- `.env`
- IDE files
- old/stale delivery folders

## Validation Summary

The package is safe to share only as a local demo package. It intentionally contains disposable demo TLS private keys and demo passwords needed for local Docker startup. It does not contain repository history or Java source code.
