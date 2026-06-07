# Red Team Safety Checklist

## Before Sharing

| Check | Status | Evidence / Notes |
|---|---|---|
| TLS/mTLS ready | Pass | RabbitMQ TLS smoke publisher succeeded; MongoDB compose uses `--tlsMode requireTLS` |
| HMAC ready | Pass | HMAC signing tests pass; missing/invalid HMAC rejected |
| Replay protection ready | Pass | Duplicate nonce test rejects replay |
| Timestamp protection ready | Pass | Old timestamp test rejects stale messages |
| UUID nonce ready | Pass | Missing/invalid nonce rejected |
| Input validation ready | Fixed | Server-side payload schema, amount, space, reason, length, and character validation added |
| Secure logging ready | Fixed | Logs redact secrets/credential URIs; third-party DEBUG logs suppressed |
| Least privilege ready | Pass | RabbitMQ live permissions verified on `/parking` |
| Default RabbitMQ credentials removed | Pass | `guest` deleted or cannot authenticate |
| Stack traces hidden from CLI users | Fixed | CLI smoke exits cleanly; generic user errors used |
| Secrets protected in logs | Pass | Checked logs for known passwords/HMAC/private keys/full credential URIs |
| Docker exposure checked | Pass | AMQP TLS only exposed on localhost ports; plaintext `5672` closed |
| MongoDB auth checked | Partial | Config has keyFile/RBAC/TLS; live auth validation blocked by local `27017` conflict |
| RabbitMQ auth checked | Pass | Users and permissions verified live |
| Non-JavaFX CLI checked | Pass | `:customer-ui:runCLI`, `:peo-ui:runCLI`, `:mo-ui:runCLI` run with Java 21 and no GUI |
| Red Team package checked | Pass with caveat | Runtime package excludes source and `.git`; includes local demo cert keys |
| Source code excluded | Pass | New package excludes `apps/*/src`, `.git`, build temp, logs, IDE files |

## Do Not Include

- `.git`
- `apps/**/src`
- `build`, `.gradle`, `gradle-bin`
- `logs`
- IDE folders and local notes
- `.env` with active local secrets
- stale `delivery`, `mulligan-red-team`, or old `red-team-delivery` folders

## Must Tell Red Team

- The included certificates and passwords are disposable demo/local credentials.
- They must run from the package root and use the included Docker Compose/config scripts.
- MongoDB validation requires local port `127.0.0.1:27017` to be free.
- Plain AMQP `5672` is intentionally closed; use TLS AMQP `5671`, `5673`, `5674`.
