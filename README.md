# Distributed Systems - Semester 2, 5786

## Team Members
| Name | Student ID | Task Performed | Hours Worked |
| :--- | :--- | :--- | :--- |
| **Walaa Mruwat** | 325224194 | Task 1: User Interfaces | 20 Hours |
| **Hanan Taha** | 212277438 | Task 2: Recommender Server | 20 Hours |
| **Aseel Shaheen** | 214228009 | Task 4: Documentation & DevOps | 20 Hours |
| **Hala Assadi** | 324830967 | Task 3: Consensus Protocol | 20 Hours |
| **Taqwa Mrowat** | 212804017 | Task 5: Security Hardening (Blue Teaming) | 20 Hours |

---

## Parking System - Stage 3: Clustered Recommender and Consensus

Welcome to **Mulligan Parking System**, a highly resilient and distributed smart parking application built for Stage 3 of the Distributed Systems course.

### 🚗 Project Overview
The Mulligan Parking System manages parking space transactions, citations, and recommendations across a distributed node network. In Stage 3, we have successfully implemented a **Leader-Follower Consensus Protocol** to ensure that all parking recommendations are synchronized and resilient against node failures or malicious actors.

### 🏗️ Architecture & Tech Stack
- **Languages/Build:** Java 21, Gradle
- **Message Broker:** RabbitMQ (Quorum Queues for high availability)
- **Database:** MongoDB Replica Set (rs0)
- **Security:** Strict mTLS between all components, distributed Nonce validation for anti-replay protection.
- **Microservices:**
  - `queue-server`: Ingests messages and validates nonces.
  - `storage-server`: Persists parking transactions and citations to MongoDB.
  - `recommender-server`: A 3-node cluster that calculates parking recommendations using a strict majority consensus protocol.
- **Client UIs:** JavaFX applications for Customers, Parking Enforcement Officers (PEO), and Management (MO).

### 🚀 Quick Start
To run the project locally:
1. **Start the Infrastructure:**
   ```bash
   docker-compose up -d
   ```
2. **Build the Application:**
   ```bash
   ./gradlew.bat build
   ```
3. **Launch the Mulligan Client UI:**
   ```bash
   ./gradlew.bat :mulligan-app:run
   ```

---

### 📚 Extended Documentation
For full setup details, security reports, and architectural diagrams, please refer to the following documents:
- [DEPLOY.md](DEPLOY.md) - Infrastructure setup and application startup guide.
- [ConsensusProtocolDesign.md](ConsensusProtocolDesign.md) - Sequence diagrams, JSON message schema, and voting details.
- [Defense.md](Defense.md) - Security controls and Blue team fixes report.
- [Testing.md](Testing.md) - Automated and manual verification evidence.
