# Security Policy

## Reporting Security Issues
If you discover a security vulnerability or exposed secret within this project, please **DO NOT** open a public issue.

Instead, report it directly to the security maintainers via email:
- Security Contact: `security@example.com`

## Secret Management Policy
- **No Hardcoded Credentials:** Never commit credentials, HMAC keys, TLS certificates, or passwords into the Git repository.
- **Environment Variables:** All application secrets must be supplied via system environment variables or `.env` files (which are ignored by Git).
- **Automated Scanning:** Push protection and secret scanning are enforced on all branches.
