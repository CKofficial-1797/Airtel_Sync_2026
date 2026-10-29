# Project Udyam Digital

Accel YTL Hackathon 2026, Enterprise Track A: a mobile-first journey where an MSME owner talks to an AI advisor, gets a telecom bundle from a fixed catalogue, sees one clear monthly quote, completes Smart KYC and tracks the order.

## Folder structure

```
backend/      Spring Boot 3 (Java 21, Maven): data, rules, mock registries, KYC, orders
ai-service/   Python FastAPI advisor + document extraction (later phase)
frontend/     React + Vite + Tailwind (later phase)
data/         provided CSVs + kyc_documents/ (synthetic, fictional data)
docs/         case PDF + HANDOFF.md (living handoff file, read it first)
```

Database: Neon serverless PostgreSQL (no Docker, no local Postgres).

## Run Phase 1 (backend)

Prerequisites: Java 21+, Maven 3.9+, a Neon project (use the **direct** connection, not pooled).

**Option A: local profile file (recommended)**

1. Copy `backend/src/main/resources/application-local.yml.example` to `application-local.yml` in the same folder and fill in your Neon host, username and password. It is git-ignored.
2. Run from `backend/`:
   ```
   mvn spring-boot:run -Dspring-boot.run.profiles=local
   ```

**Option B: environment variables**

```powershell
# Windows PowerShell
$env:DB_URL="jdbc:postgresql://<host>/neondb?sslmode=require&reWriteBatchedInserts=true"
$env:DB_USERNAME="<user>"
$env:DB_PASSWORD="<password>"
mvn spring-boot:run
```

```bash
# macOS / Linux
export DB_URL="jdbc:postgresql://<host>/neondb?sslmode=require&reWriteBatchedInserts=true"
export DB_USERNAME="<user>"
export DB_PASSWORD="<password>"
mvn spring-boot:run
```

On first start, Flyway creates the schema and the loader imports the CSVs from `../data` (only into empty tables, so restarts are safe).

Check: `http://localhost:8080/actuator/health`, `/api/products`, `/api/admin/data-summary`.

Unit tests (no database needed): `mvn clean test` from `backend/`.
