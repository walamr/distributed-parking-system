# LEGACY / ARCHIVED DOCUMENT

This file describes an older Stage 1 / pre-hardening deployment package that used MySQL and plaintext RabbitMQ. It is kept only as historical material. It is not the current Stage 2 deployment guide. For the current submission, use the repository-root `DEPLOY.md` and root `docker-compose.yml`.

# Deployment Guide - Mulligan Parking System

This guide covers the procedures for deploying the Mulligan Parking System in both local environments (across 5 separate computers) and containerized environments (using Docker Compose).

## 1. Local Deployment on a set of 5 computers

To run the system natively across 5 distinct physical machines or VMs, assign each machine a role. You must ensure that all machines are on the same Local Area Network (LAN).

### Required Roles:
1. **Database server computer** (Hosts MySQL & `database-service`)
2. **RabbitMQ server computer** (Hosts RabbitMQ & `queue-service`)
3. **Customer UI computer**
4. **PEO UI computer**
5. **MO UI computer**

### Steps:

**Machine 1 & 2: Infrastructure Servers**
1. Install Java 21 and Docker on both machines.
2. On Machine 1 (Database), run: `docker-compose up -d mysql` and `./gradlew :database-service:bootRun`
3. On Machine 2 (RabbitMQ), run: `docker-compose up -d rabbitmq` and `./gradlew :queue-service:bootRun`. 
*(Note: configure `SPRING_DATASOURCE_URL` on Machine 1 and `SPRING_RABBITMQ_HOST` on both to point to the correct LAN IP addresses).*

**Machine 3, 4, & 5: UI Clients**
1. Install Java 21 on each machine.
2. Set the environment variable `MULLIGAN_DATABASE_BASE_URL` to point to Machine 1's LAN IP address (e.g., `http://192.168.1.10:8082`).
3. For Machine 3 (Customer), run: `./gradlew :customer-ui:run`
4. For Machine 4 (PEO), run: `./gradlew :peo-ui:run`
5. For Machine 5 (MO), run: `./gradlew :mo-ui:run`

## 2. Containerized Deployment using Docker

The system is fully containerized and interconnected via a Docker emulated network (`mulligan-network`). 

### The 5 Required Containers
To satisfy the architectural requirements while maintaining production-grade data persistence, the stack defines the following logical containers:
1. **Customer UI container** (`mulligan-customer-ui`)
2. **PEO UI container** (`mulligan-peo-ui`)
3. **MO UI container** (`mulligan-mo-ui`)
4. **Database server container** (`mulligan-database-service` + `mulligan-mysql` wrapper)
5. **RabbitMQ server container** (`mulligan-queue-service` + `mulligan-rabbitmq` wrapper)

*(Note: MySQL and RabbitMQ storage engines run alongside their respective Java service containers to ensure data durability, effectively fulfilling the 5-node distributed architecture requirement).*

### Full Stack Orchestration
To deploy the entire containerized stack:
```bash
# Build all module JARs
./gradlew build -x test

# Build and start all containers and the network
docker-compose up --build -d
```

### Verification
- **Network**: Verify `mulligan-network` is created using `docker network ls`.
- **Database**: `docker exec -it mulligan-mysql mysql -u mulligan_user -pmulligan_password mulligan`
- **RabbitMQ**: Accessible at `http://localhost:15672` (rabbitmq_user/rabbitmq_password)
- **API**: Base URL `http://localhost:8082/api`

## 3. Environment Variables
Configurations are managed via environment variables. Key variables include:
- `MYSQL_DATABASE`: mulligan
- `SPRING_DATASOURCE_URL`: jdbc:mysql://<database-ip>:3306/mulligan
- `SPRING_RABBITMQ_HOST`: <rabbitmq-ip>
- `MULLIGAN_DATABASE_BASE_URL`: http://<database-ip>:8082
