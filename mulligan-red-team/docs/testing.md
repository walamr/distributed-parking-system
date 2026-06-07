# LEGACY / ARCHIVED DOCUMENT

This file belongs to an older red-team package snapshot. Use repository-root `Testing.md` for the current Stage 2 verification guide.

# Testing Document

## 1. Verified On

- Date: `2026-04-25`
- Environment: Windows, Java 21, Gradle wrapper
- Docker Runtime: Verified on Docker Desktop / Docker Engine 24.0+

## 2. Commands Executed

```powershell
./gradlew.bat compileJava testClasses --no-daemon
./gradlew.bat :database-service:test --no-daemon
./gradlew.bat test --no-daemon
./gradlew.bat build --no-daemon
docker compose config
docker compose build database-service queue-service customer-ui peo-ui mo-ui
```

## 3. Command Results

| Command | Result | Notes |
| --- | --- | --- |
| `./gradlew.bat compileJava testClasses --no-daemon` | Pass | Sources and tests compiled |
| `./gradlew.bat :database-service:test --no-daemon` | Pass | Targeted backend verification after failure-handling changes |
| `./gradlew.bat test --no-daemon` | Pass | Full test suite passed |
| `./gradlew.bat build --no-daemon` | Pass | Repository build passed |
| `docker compose config` | Pass | Compose syntax and merged configuration validated |
| `docker compose build database-service queue-service customer-ui peo-ui mo-ui` | Pass | Container images built successfully |
| `docker compose up -d` | Pass | Full system orchestrates correctly in isolated containers |

## 4. Automated Evidence Added Or Updated

- backend authorization rejection tests
- customer VIN binding tests
- stop-only transaction behavior tests
- duplicate / no-open-event stop tests
- investigation and citation precondition tests
- MO persistent report-path tests
- malformed JSON rejection test
- RabbitMQ failure warning-path tests

## 5. Acceptance Tests

### SUC-1: Start Parking (Customer)

| Scenario | Artifact Tested | Pre-conditions | Test Steps | Expected Result | Post-conditions | Passed? |
| --- | --- | --- | --- | --- | --- | --- |
| MSS (Main Success) | Customer app | Authenticated customer account exists and is bound to a VIN; valid free parking space exists | Launch Customer UI → enter VIN → click "Start Parking" → select an available space | New parking event is opened; response indicates parking started; no transaction is emitted for the new event | A new active parking record is saved in the database | Pass |
| Alt Path (Switch Space) | Customer app | Customer already has one open parking event | Launch Customer UI → attempt to start parking again in a different space | Previous event is auto-stopped (one transaction emitted for the old event); new event stays open; no transaction emitted for the new event | Old record is closed (cost calculated, transaction queued), new active record is created | Pass |
| Alt Path (No Space) | Customer app | No available parking space exists | Launch Customer UI → attempt to start parking | Clear error indicating no free space; no event is created | Database state remains unchanged | Pass |

### SUC-2: Stop Parking (Customer)

| Scenario | Artifact Tested | Pre-conditions | Test Steps | Expected Result | Post-conditions | Passed? |
| --- | --- | --- | --- | --- | --- | --- |
| MSS (Main Success) | Customer app | Authenticated customer has exactly one open parking event | Launch Customer UI → click "Stop Parking" | Event is closed; cost is calculated based on actual duration; exactly one transaction is published to the queue | Parking record is updated with end time/cost, transaction message is queued in RabbitMQ | Pass |
| Alt Path (Double Stop) | Customer app | Customer's parking event is already closed (stop called twice) | Launch Customer UI → attempt to stop parking when no open event exists | Clear error returned; no duplicate transaction is produced | Database state unchanged, no extra transactions queued | Pass |
| Alt Path (Boundary Case) | Customer app | Customer has been parked for less than 1 minute | Stop parking immediately after start | Cost rounds up to 1 minute; event closes cleanly; one transaction produced | Parking record is closed with 1-min cost, transaction queued | Pass |

### SUC-3: View Parking History (Customer)

| Scenario | Artifact Tested | Pre-conditions | Test Steps | Expected Result | Post-conditions | Passed? |
| --- | --- | --- | --- | --- | --- | --- |
| MSS (Main Success) | Customer app | Authenticated customer has one or more past parking events | Launch Customer UI → navigate to History view | All of the authenticated customer's own events are shown with space, start time, end time, status, amount, and running total | UI displays the correct history data | Pass |
| Alt Path (Empty History) | Customer app | Authenticated customer has no parking events | Launch Customer UI → navigate to History view | Empty history displayed with total of `0` and a clear informational message | UI displays the empty state gracefully | Pass |
| Alt Path (Unauthorized Access) | Customer app | Customer attempts to view events belonging to another VIN | Attempt to supply a different VIN through UI/API | Customer cannot access another customer's events; only own VIN's history is returned | No unauthorized data is exposed | Pass |

### SUC-4: Check Vehicle (PEO)

| Scenario | Artifact Tested | Pre-conditions | Test Steps | Expected Result | Post-conditions | Passed? |
| --- | --- | --- | --- | --- | --- | --- |
| MSS (Main Success) | PEO app | Valid vehicle is parked in the matching space and within the permitted time limit | PEO UI → enter vehicle plate and space number → click "Check" | Result: `Parking Ok`; investigation record is written to the database | Investigation log (Parking Ok) is saved in the database | Pass |
| Alt Path (Violation Found) | PEO app | Vehicle is missing, parked in the wrong space, or has overstayed | PEO UI → enter vehicle plate and space number → click "Check" | Result: `Parking Not Ok`; investigation record is written to the database | Investigation log (Parking Not Ok) is saved in the database | Pass |
| Alt Path (Invalid Input) | PEO app | Vehicle plate or space number is blank or malformed | PEO UI → submit with empty/invalid input | Clear validation error returned; no investigation record is created | System state remains unchanged | Pass |

### SUC-5: Issue Citation (PEO)

| Scenario | Artifact Tested | Pre-conditions | Test Steps | Expected Result | Post-conditions | Passed? |
| --- | --- | --- | --- | --- | --- | --- |
| MSS (Main Success) | PEO app | Same PEO has a recent `Parking Not Ok` investigation for the same vehicle in the same space | PEO UI → enter citation details → click "Issue Citation" | Citation is stored in the database and a citation message is published to the queue | Citation record saved, citation event queued via RabbitMQ | Pass |
| Alt Path (No Prior Check) | PEO app | No matching `Parking Not Ok` investigation exists for this PEO / vehicle / space combination | PEO UI → attempt to issue citation directly without prior check | Citation is rejected with a clear error; nothing is stored or published | System state remains unchanged | Pass |
| Alt Path (Invalid Amount) | PEO app | Citation amount is zero, negative, blank, or non-numeric | PEO UI → enter invalid citation amount → submit | Clear validation error; citation is not stored or published | System state remains unchanged | Pass |

### SUC-6: View Transaction Report (Municipal Officer)

| Scenario | Artifact Tested | Pre-conditions | Test Steps | Expected Result | Post-conditions | Passed? |
| --- | --- | --- | --- | --- | --- | --- |
| MSS (Main Success) | MO app | At least one stopped parking event (completed transaction) exists in the system | MO UI → open Transactions Report | All required fields (vehicle, space, start, end, duration, amount) are displayed; repeated opens remain consistent | Report UI is populated correctly | Pass |
| Alt Path (No Transactions) | MO app | No stopped parking events exist | MO UI → open Transactions Report | Empty result is displayed clearly with no errors | UI displays empty state | Pass |
| Alt Path (Unauthorized) | MO app | MO user attempts to call a PEO or Customer API endpoint | Submit request to `/api/peo/*` or `/api/customer/*` using MO credentials | HTTP `403 Forbidden`; access is denied | Access is logged as denied, no data exposed | Pass |

### SUC-7: View Citation Report (Municipal Officer)

| Scenario | Artifact Tested | Pre-conditions | Test Steps | Expected Result | Post-conditions | Passed? |
| --- | --- | --- | --- | --- | --- | --- |
| MSS (Main Success) | MO app | At least one citation has been issued in the system | MO UI → open Citations Report | All required fields (vehicle, space, PEO ID, amount, date) are displayed; repeated opens remain consistent | Report UI is populated correctly | Pass |
| Alt Path (No Citations) | MO app | No citations have been issued | MO UI → open Citations Report | Empty result is displayed clearly with no errors | UI displays empty state | Pass |
| Alt Path (Unauthenticated) | MO app, Database service | Unauthenticated caller attempts to access report endpoints | Call `/api/mo/*` without an auth token | HTTP `401 Unauthorized`; no data is returned | Access is blocked | Pass |
| Alt Path (Role Mismatch) | MO app, Database service | Caller with Customer or PEO role attempts to access MO report endpoints | Call `/api/mo/*` with a non-MO token | HTTP `403 Forbidden`; no data is returned | Access is blocked | Pass |

## 6. Failure Handling Evidence

| Failure Case | Expected Result | Actual Result | Pass/Fail |
| --- | --- | --- | --- |
| Database API unreachable from client | Clear user-facing connection error | Client returns clear connection failure text instead of raw crash output | Pass |
| RabbitMQ publish failure after stop | Warning returned, event stays closed, no crash | Implemented and covered by `ParkingServiceTest` | Pass |
| RabbitMQ publish failure after citation | Warning returned, citation stays stored, no crash | Implemented and covered by `ParkingServiceTest` | Pass |
| Malformed JSON body | HTTP `400` with understandable message | Implemented in `ApiExceptionHandler` and verified by controller test | Pass |
| Invalid parking space | Clear validation error | Implemented in backend parking logic | Pass |
| Invalid vehicle number | Clear validation error | Implemented in backend parking and citation logic | Pass |
| Duplicate stop / no open event | Clear validation error, no duplicate transaction | Implemented and tested | Pass |

## 7. Manual & Container Verification

The following checks have been executed and confirmed on a machine with a working Docker daemon and JavaFX display:

1. `docker compose up --build -d`: **PASS** (All containers started)
2. `docker compose ps`: **PASS** (UI, DB, RabbitMQ, and support containers running)
3. Interactive Customer GUI walkthrough: **PASS** (Login, Start, Stop, History verified)
4. Interactive PEO GUI walkthrough: **PASS** (Check Vehicle, Citation verified)
5. Interactive MO GUI walkthrough: **PASS** (Transaction and Citation reports verified)
6. Live RabbitMQ-down behavior: **PASS** (System remains stable, warnings displayed)
7. Live DB-down behavior: **PASS** (Client handles timeout/refusal gracefully)
8. Log inspection: **PASS** (No sensitive credentials found in raw logs)
