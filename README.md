# Distributed Systems Course - Semester 2, Academic Year 2025/2026 (5786)

## Team Members
| Name | Student ID | Task Performed | Hours Worked |
| :--- | :--- | :--- | :--- |
| **Walaa Mruwat** | 325224194 | Task 1: User Interfaces | 22 Hours |
| **Hanan Taha** | 212277438 | Task 2: Recommender Server | 20 Hours |
| **Aseel Shaheen** | 214228009 | Task 4: Documentation & DevOps | 21 Hours |
| **Hala Assadi** | 324830967 | Task 3: Consensus Protocol | 19 Hours |
| **Taqwa Mrowat** | 212804017 | Task 5: Security Hardening (Blue Teaming) | 18 Hours |

---

## Parking System - Stage 3: Clustered Recommender and Consensus

Welcome to **Mulligan Parking System**, a highly resilient and distributed smart parking application built for Stage 3 of the Distributed Systems course.

## Demo Run Steps

Demo credentials, TLS certificates, and local private keys are intentionally included for academic grading and local lab execution only. They are not production credentials.

1. **Build:**
   ```powershell
   .\gradlew.bat clean build
   ```

2. **Start the full system:**
   ```powershell
   docker compose up -d
   ```

3. **Check containers:**
   ```powershell
   docker compose ps
   ```

4. **Run/verify the customer app or CLI:**
   ```powershell
   .\gradlew.bat :customer-ui:run
   ```

   CLI alternative:
   ```powershell
   .\gradlew.bat :customer-ui:runCLI
   ```

5. **Stop system:**
   ```powershell
   docker compose down
   ```

### Recommender Cluster & Consensus (Stage 3)

The 3-node recommender cluster (`recommender1` leader on 8091, `recommender2`/`recommender3` followers on 8092/8093) starts automatically with `docker compose up -d`. To exercise the new feature:

- **Customer GUI:** click **💡 Recommend Parking**, enter a space number, and view the recommended space(s) with citation counts.
- **Customer CLI:** choose option **[4] Get Parking Recommendation**.

Configure a node as malicious for consensus testing (per assignment Section 4.2), e.g.:
```powershell
$env:RECOMMENDER2_MALICIOUS='true'; $env:RECOMMENDER2_PAYLOAD='999;999'
docker compose up -d recommender2
```
A node can also be toggled at runtime from its GUI checkbox or its `--cli` menu. Only the Customer UI/CLI changed for this feature; the MO and PEO UIs are unchanged.

---

### 📚 Extended Documentation
For full setup details, security reports, and architectural diagrams, please refer to the following documents:
- [DEPLOY.md](DEPLOY.md) - Infrastructure setup and application startup guide.
- [ConsensusProtocolDesign.md](ConsensusProtocolDesign.md) - Sequence diagrams, JSON message schema, voting details, and worked examples.
- [QueueServerDesign.md](QueueServerDesign.md) - RabbitMQ topology, security controls, and queue design.
- [DatabaseDesign.md](DatabaseDesign.md) - MongoDB replica set, schemas, and recommender query patterns.
- [Defense.md](Defense.md) - Security controls and Blue team fixes report.
- [Testing.md](Testing.md) - Automated and manual verification evidence.
