# LEGACY / ARCHIVED DOCUMENT

This file belongs to an older pre-hardening delivery package. It is not the current Stage 2 testing guide. Use the repository-root `Testing.md` for current MongoDB, RabbitMQ TLS, quorum queue, and HMAC/replay verification.

# Testing Guide - Mulligan Parking System

## 1. Unit Testing
All domain logic and utilities must be covered by JUnit 5 tests.
- **Location**: `src/test/java/`
- **Focus**: Branch coverage, boundary values, and exception handling.

### Running Unit Tests
```bash
./gradlew test
```

## 2. Integration Testing
We use **TestContainers** to run tests against real instances of MySQL and RabbitMQ.
- **Location**: `common/src/test/java/.../integration/`
- **Requirement**: Docker must be running on the host machine.

## 3. Coverage Analysis
JaCoCo is used for code coverage reporting.
```bash
./gradlew jacocoTestReport
```
Reports are available at: `build/reports/jacoco/test/html/index.html`

## 4. Coding Standards
- **JavaDocs**: MANDATORY for all public and protected members.
- **Style**: Google Java Style is recommended.
