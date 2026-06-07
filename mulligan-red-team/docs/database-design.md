# LEGACY / ARCHIVED DOCUMENT

This file belongs to an older red-team package snapshot. Use repository-root `DatabaseDesign.md` for the current MongoDB replica-set design.

# Database Design Document

## Purpose

The Mulligan database layer stores the authoritative assignment state for:
- vehicles
- parking zones
- parking spaces
- parking events
- investigations
- citations
- users

## Runtime Database Technology

### Local/native backend runs

- Default runtime database: H2 file database
- Configured in `database-service/src/main/resources/application.yml`

### Docker deployment

- Runtime database: MySQL 8
- Configured by `docker-compose.yml` through `SPRING_DATASOURCE_*` environment variables

## Initialization Scripts

- Authoritative schema copy used by Spring SQL init:
  - `database-service/src/main/resources/db/schema.sql`
  - `database-service/src/main/resources/db/data.sql`
- Matching reference copy for manual import:
  - `config/database/schema.sql`
  - `config/database/sample_data.sql`

## Tables

### `vehicles`

- Primary key: `id`
- Key fields: `vin`, `manufacturer`, `model`, `owner_name`
- Purpose: vehicle identity and customer ownership validation

### `parking_zones`

- Primary key: `id`
- Key fields: `zone_name`, `rate`, `daily_max_rate`, `maximum_parking_minutes`
- Purpose: charging and legality rules

### `parking_spaces`

- Primary key: `id`
- Foreign key: `zone_id -> parking_zones.id`
- Key fields: `space_number`, `space_type`, `is_available`
- Purpose: space validation and zone mapping

### `parking_events`

- Primary key: `event_id`
- Foreign keys:
  - `vehicle_id -> vehicles.id`
  - `zone_id -> parking_zones.id`
  - `space_id -> parking_spaces.id`
- Key fields: `park_time`, `unpark_time`, `duration_minutes`, `amount_due`, `is_paid`
- Purpose: active and historical parking sessions

### `investigations`

- Primary key: `id`
- Foreign keys:
  - `vehicle_id -> vehicles.id`
  - `space_id -> parking_spaces.id`
- Key fields: `peo_user_id`, `result`, `investigated_at`
- Purpose: records PEO `Parking Ok` / `Parking Not Ok` checks and authorizes valid citation issuance

### `citations`

- Primary key: `citation_id`
- Foreign keys:
  - `vehicle_id -> vehicles.id`
  - `zone_id -> parking_zones.id`
  - `space_id -> parking_spaces.id`
- Key fields: `citation_reason`, `penalty_amount`, `issued_date`, `enforcer_id`
- Purpose: persistent citation storage and MO citation reporting

### `users`

- Primary key: `id`
- Key fields: `username`, `password`, `role`, `full_name`, `associated_vin`
- Purpose: authentication, role authorization, and customer VIN binding

## Logging Note

- The current implementation writes PEO audit/query logs to the file logger via `FileLoggingService`.
- There is no active `system_logs` database table in the runtime implementation.

## Relationships

- Each parking space belongs to one parking zone.
- Each parking event belongs to one vehicle, one zone, and one space.
- Each investigation belongs to one vehicle and one space, and stores the responsible PEO user id.
- Each citation belongs to one vehicle, one zone, and one space.
- A customer user may be bound to one `associated_vin`.

## Seed Data

The default seed set includes:
- at least 10 vehicles
- at least 10 parking zones with different costs
- at least 100 parking spaces
- default users for Customer, PEO, and MO login

Seed user passwords are stored as hashes so they match the active login logic.

## Deployment Note

- The assignment container topology is declared by `docker-compose.yml`.
- The backend still uses `database-service` as the authoritative API and persistence layer.
- In Docker deployment, the required database server container is `mysql`.

## Core Access Patterns

### Customer Start Parking

- resolve authenticated customer VIN from the logged-in account
- validate space
- stop previous open event if needed
- create a new open event

### Customer Stop Parking

- resolve authenticated customer VIN
- find open event
- calculate amount
- close event

### Customer History

- resolve authenticated customer VIN
- load events ordered by start time descending
- return start, end, status, amount, and total

### PEO Investigation

- validate VIN and space
- locate active event
- compare space
- compare elapsed time to `maximum_parking_minutes`
- persist investigation result

### Citation Issuance

- validate VIN, space, amount, and reason
- find recent matching `Parking Not Ok` investigation for same PEO/vehicle/space
- store citation

### MO Reports

- transactions report reads historical stopped events from the database
- citation report reads stored citations from the database

## Security Rules Reflected In The Schema

- Customer actions are bound to `users.associated_vin`
- Role checks are enforced in the backend, not only in the UI
- Citation authorization depends on persisted investigation records
