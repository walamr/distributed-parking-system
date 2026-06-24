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
---

### 📚 Extended Documentation
For full setup details, security reports, and architectural diagrams, please refer to the following documents:
- [DEPLOY.md](DEPLOY.md) - Infrastructure setup and application startup guide.
- [ConsensusProtocolDesign.md](ConsensusProtocolDesign.md) - Sequence diagrams, JSON message schema, and voting details.
- [Defense.md](Defense.md) - Security controls and Blue team fixes report.
- [Testing.md](Testing.md) - Automated and manual verification evidence.
