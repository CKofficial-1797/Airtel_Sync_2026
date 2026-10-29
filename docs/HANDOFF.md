# HANDOFF - Project Udyam Digital

Living handoff file. Every phase reads this first and updates it at the end (append; don't rewrite history).

## 1. Phases completed

| Phase | Date | Summary |
|---|---|---|
| 1 | 2026-10-29 | Spring Boot backend, Neon connection, Flyway V1 schema, idempotent CSV loader, read-only product + data-summary APIs, parsing unit tests |

## 2. How to run

- Prereqs: Java 21 (Temurin 21.0.12), Maven 3.9.16, Neon project (database `neondb`, **direct** host, not `-pooler`).
- Network: outbound **TCP 5432** must be open (some Wi-Fi networks block it → `SocketTimeoutException: Connect timed out`; use a hotspot).
- Credentials (never committed), either:
  - `backend/src/main/resources/application-local.yml` (git-ignored; template: `application-local.yml.example`) with `spring.datasource.url / username / password`, run with profile `local`; or
  - env vars `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` (no profile needed).
- URL format: `jdbc:postgresql://<direct-host>/neondb?sslmode=require&reWriteBatchedInserts=true` (not Neon's raw `postgresql://user:pass@...` string).
- Commands (from `backend/`):
  - `mvn clean test`: unit tests, no DB
  - `mvn spring-boot:run "-Dspring-boot.run.profiles=local"` (PowerShell) / `mvn spring-boot:run -Dspring-boot.run.profiles=local` (bash)
- App: `http://localhost:8080`. Startup takes ~50 s on a cold Neon; first data load ~27 s, later starts skip loading.

## 3. Package structure (`backend/src/main/java/com/udyam`)

```
UdyamApplication.java     @SpringBootApplication + @ConfigurationPropertiesScan
common/config/            AppProperties (app.*), WebConfig (CORS for /api/**)
common/errors/            ApiError, NotFoundException, GlobalExceptionHandler
catalogue/                Product entity, repository, DTO, service, controller
registry/                 GstinRecord / UdyamRecord entities + repositories
leads/                    DataSummary DTO, DataSummaryService (JdbcTemplate), DataSummaryController
dataload/                 CsvParsing (static helpers), BundleItem, DataLoader (ApplicationRunner)
```

## 4. Key classes

| Class | What it does |
|---|---|
| `dataload.CsvParsing` | Pure static helpers, no Spring/DB: `readAll(Path)` (Commons CSV, header row, trims, skips BOM) → `List<CSVRecord>`; `text` (blank→null), `toInt`, `toDate` (ISO), `yesNo` (Y/N→Boolean, blank→null, else IAE), `splitList(value, sep)` / `splitIntList` (blank→empty list), `parseBundle("P01x1;P07x2")` → `List<BundleItem>` (IAE on malformed, qty ≤0, duplicate product). Constants `LIST_SEPARATOR=";"`, `ALT_LIST_SEPARATOR="|"`. |
| `dataload.BundleItem` | record `(String productId, int quantity)` |
| `dataload.DataLoader` | `ApplicationRunner`. Fails fast if any CSV is missing. Order: product → gstin_registry → udyam_registry → smb_lead → lead_bundle_item. Each table loads only if empty, in one `TransactionTemplate` tx, `JdbcTemplate.batchUpdate` batch 500. Validates bundle product IDs exist. Logs counts only. Public constants `PRODUCT_FILE`, `GSTIN_FILE`, `UDYAM_FILE`, `LEADS_FILE`; `resolveDataDir(String)`. |
| `catalogue.Product` | `@Immutable` JPA entity on `product`; arrays as `Integer[]` / `String[]` with `@JdbcTypeCode(SqlTypes.ARRAY)` |
| `catalogue.ProductRepository` | `JpaRepository<Product,String>`, `findAllByOrderByProductIdAsc()` |
| `catalogue.ProductDto` | API record; arrays → `List`; `from(Product)` |
| `catalogue.ProductService` | `findAll()`, `findById(id)` (throws `NotFoundException`) |
| `catalogue.ProductController` | `/api/products` endpoints |
| `registry.GstinRecord` | `@Immutable` entity on `gstin_registry` (gstin mapped as `SqlTypes.CHAR`, length 15) |
| `registry.UdyamRecord` | `@Immutable` entity on `udyam_registry` |
| `registry.GstinRegistryRepository` | `JpaRepository<GstinRecord,String>`, `countByStatus()` → `List<Object[]{status,count}>` |
| `registry.UdyamRegistryRepository` | `JpaRepository<UdyamRecord,String>` |
| `leads.DataSummaryService` | Builds `DataSummary` via JdbcTemplate + repos. Public `FUNNEL_STAGES` list (stage order). |
| `leads.DataSummary` | record with nested `RowCounts`, `FunnelStage(stage, leads)`, `Integrity` |
| `common.config.AppProperties` | record `(dataDir, referenceDate: LocalDate, cors.allowedOrigins)` |
| `common.errors.GlobalExceptionHandler` | `NotFoundException`/unknown path → 404; type mismatch / validation / `IllegalArgumentException` → 400; other → 500 "Unexpected server error" (logged) |

No JPA entity exists for `smb_lead` / `lead_bundle_item` (read via JdbcTemplate only).

## 5. Endpoints

| Method | Path | Response | Errors |
|---|---|---|---|
| GET | `/actuator/health` | `{"status":"UP"}` (only actuator endpoint exposed) | - |
| GET | `/api/products` | `ProductDto[]` (25, sorted by id): `productId, category, productName, description, unit, priceMinInrPerMonth, priceMaxInrPerMonth, oneTimeSetupInr, minContractMonths, availableCityTiers[int], minEmployees, minSites, requiresGstin, requiresOneOf[str], typicalInstallDays, targetSegments[str]` | - |
| GET | `/api/products/{id}` | one `ProductDto` | 404 |
| GET | `/api/admin/data-summary` | `{rowCounts{products,gstinRegistry,udyamRegistry,leads,leadBundleItems}, funnel[{stage,leads}], activated, avgDaysLeadToActivation, avgFollowUpCallsActivated, totalAcquisitionCostInr, blendedCacInr, gstinStatusCounts{Active,Cancelled,Suspended}, integrity{leadGstinMissingInRegistry, leadUdyamMissingInRegistry}}` (decimals rounded to 2) | - |

Error body (all errors): `{"status":404,"error":"Not Found","message":"...","path":"/api/..."}`. CORS: `/api/**` allows `http://localhost:5173`.

## 6. Config keys (`application.yml`)

| Key | Default | Purpose |
|---|---|---|
| `spring.datasource.url / username / password` | `${DB_URL}` / `${DB_USERNAME}` / `${DB_PASSWORD}` | Neon connection (overridden by `application-local.yml`) |
| `spring.datasource.hikari.maximum-pool-size` | 5 | Neon free tier |
| `spring.datasource.hikari.connection-timeout` | 30000 | Neon cold start |
| `spring.jpa.hibernate.ddl-auto` | validate | Flyway owns schema |
| `spring.jpa.open-in-view` | false | |
| `management.endpoints.web.exposure.include` | health | |
| `app.data-dir` | `../data` | CSV folder, resolved against the working dir (`backend/`) → `<repo>/data` |
| `app.reference-date` | 2026-07-15 | "Today" for all date checks (README) |
| `app.cors.allowed-origins` | `[http://localhost:5173]` | Future frontend |

## 7. Database (Neon PostgreSQL 18.6)

Flyway versions applied: **V1** `V1__init_reference_data.sql`.

| Table | Key columns / types |
|---|---|
| `product` | `product_id VARCHAR(5)` PK; prices/setup/contract/min_employees/min_sites/install_days `INT NOT NULL`; `available_city_tiers INT[]`; `requires_gstin BOOLEAN`; `requires_one_of TEXT[]` (default `'{}'`, empty when blank); `target_segments TEXT[]`; CHECK price_min ≤ price_max |
| `gstin_registry` | `gstin CHAR(15)` PK; names/constitution/address/state `TEXT`; `registration_date DATE`; `status TEXT` CHECK IN (Active, Cancelled, Suspended) |
| `udyam_registry` | `udyam_number VARCHAR(19)` PK; enterprise_name/type, major_activity, nic_description, state, city `TEXT`; `date_of_registration DATE` |
| `smb_lead` | all 40 CSV columns. `lead_id VARCHAR(10)` PK; Y/N → `BOOLEAN` (`churned_within_6_months` nullable); stage dates `DATE` nullable; `gstin CHAR(15)` / `udyam_number VARCHAR(19)` nullable, no FK to the registries; `products_of_interest TEXT[]`; `bundle_products TEXT` (raw); post-activation columns nullable; CHECK on `stage_reached` (6 stages) and `outcome` (Activated, Dropped) |
| `lead_bundle_item` | `lead_id` FK → smb_lead, `product_id` FK → product, `quantity INT > 0`, PK(lead_id, product_id) |

Indexes: `lower(legal_name)`, `lower(trade_name)` on gstin_registry; `lower(enterprise_name)` on udyam_registry; `smb_lead(segment)`, `(outcome)`, `(gstin)`.

## 8. Data facts verified (2026-10-29)

- Rows: product 25, gstin_registry 3534, udyam_registry 2723, smb_lead 5000, lead_bundle_item 1651.
- Funnel (reached at least): Lead 5000 / Contacted 3406 / Needs assessed 1974 / Quote accepted 861 / KYC complete 588 / Activated 368.
- Activated 368; avg days to activation 17.62; avg follow-up calls 3.01; total acquisition cost ₹5,539,640; blended CAC ₹15,053.37.
- GSTIN status: Active 3266, Cancelled 167, Suspended 101.
- Integrity: leads with GSTIN missing from registry 0; Udyam missing 0. All 3534 registry GSTINs appear on exactly one lead each.
- CSV facts: no BOM; all dates ISO `yyyy-MM-dd`; only `requires_one_of` is blank in the catalogue (18 rows); P03 is the only tier-1-only product; leads' post-activation columns are blank for the 4632 dropped leads; `drop_reason` blank for activated; no bundle repeats a product.
- Identifiers stored as-is: all GSTINs have `X` as the 4th PAN char; Udyam numbers use district `00` (e.g. format `UDYAM-KL-00-3078801`).

## 9. Testing

- `backend/src/test/java/com/udyam/dataload/CsvParsingTest.java`: **11 tests**, pure unit tests (no Spring context, no DB). Covers Y/N, `;` and `|` lists, blank handling, bundle parsing + malformed input, quoted commas + BOM via `@TempDir`.
- There is deliberately **no** `@SpringBootTest` (it would need Neon). Later phases that need data in tests should load the CSVs with `CsvParsing.readAll(Path.of("../data/<file>"))` (working dir during `mvn test` is `backend/`).
- Run: `mvn clean test` from `backend/`.

## 10. Deviations from the Phase 1 prompt

- No Lombok (it was optional): DTOs are records, entities use plain getters.
- Data-summary JSON also includes `totalAcquisitionCostInr` (for transparency of the CAC calculation).
- Extra CHECK constraints on `smb_lead.stage_reached`, `smb_lead.outcome`, product price range and bundle quantity > 0.
- `GlobalExceptionHandler` maps `IllegalArgumentException` to 400.

## 11. Known issues / TODOs

- Flyway 11.7.2 (managed by Spring Boot 3.5.16) warns "PostgreSQL 18.6 is newer than this version of Flyway". Works fine; ignore, or pin a newer Flyway later if needed.
- Neon cold start makes first startup ~50 s; the pool connection-timeout is 30 s.
- Port 5432 is blocked on some networks (see section 2).
- No admin auth on `/api/admin/data-summary` (prototype only).

## 12. Notes for the next phase (Phase 2: GSTIN/Udyam lookup + validators)

- Reuse `GstinRegistryRepository` / `UdyamRegistryRepository` and the `GstinRecord` / `UdyamRecord` entities. Don't create new ones.
- The README says GSTINs have a **valid checksum** (real 15-char structure). A format validator must accept `X` as the 4th PAN char. Udyam format is `UDYAM-<STATE>-00-<7 digits>`; district `00` is intentional.
- GSTIN `status` text is exact: `Active`, `Cancelled`, `Suspended`. Only Active should pass KYC/eligibility later (`requires_gstin = Y` means a valid **active** GSTIN).
- Name search can use the `lower(...)` indexes (`lower(legal_name)`, `lower(trade_name)`, `lower(enterprise_name)`).
- Use `AppProperties.referenceDate()` (2026-07-15) as "today". Never use `LocalDate.now()`.
- Throw `NotFoundException` for 404s; error JSON is already handled globally.
- Privacy: mask identifiers in logs and API errors (log counts only, as the loader does).
- `DataSummaryService.FUNNEL_STAGES` holds the canonical stage order.
