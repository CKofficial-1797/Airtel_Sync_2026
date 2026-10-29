# HANDOFF - Project Udyam Digital

Living handoff file. Every phase reads this first and updates it at the end (append; don't rewrite history).

## 1. Phases completed

| Phase | Date | Summary |
|---|---|---|
| 1 | 2026-10-29 | Spring Boot backend, Neon connection, Flyway V1 schema, idempotent CSV loader, read-only product + data-summary APIs, parsing unit tests |
| 2 | 2026-10-29 | GSTIN/Udyam validators (format, mod-36 checksum, state codes), registry-aware OCR correction, `GstinVerificationService`, mock lookup APIs under `/api/mock`, masked logging, `code`/`errors` on error JSON |

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
- Example requests: `backend/http-examples/` (`mock-registry.http` + JSON bodies). Use `curl --data "@http-examples/<file>.json"` to avoid PowerShell quoting issues.

## 3. Package structure (`backend/src/main/java/com/udyam`)

```
UdyamApplication.java     @SpringBootApplication + @ConfigurationPropertiesScan
common/config/            AppProperties (app.*), WebConfig (CORS for /api/**)
common/errors/            ApiError, ApiException, NotFoundException, GlobalExceptionHandler
common/privacy/           Masking (GSTIN / Udyam masking for logs)
catalogue/                Product entity, repository, DTO, service, controller
registry/                 GstinRecord / UdyamRecord entities + repositories; registry lookups,
                          OCR correction, verification service, MOCK lookup controller
registry/validation/      Pure validators (no DB): GstinValidator, UdyamValidator, state-code tables
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
| `common.errors.GlobalExceptionHandler` | `ApiException` → its status/code/errors; `NotFoundException`/unknown path → 404; invalid body (`@Valid`) or unreadable JSON → 400 `INVALID_REQUEST`; type mismatch / `IllegalArgumentException` → 400; other → 500 "Unexpected server error" (logged) |
| `common.errors.ApiError` | record `(status, error, code, message, path, errors)`, `@JsonInclude(NON_NULL)`; 4-arg constructor keeps the Phase 1 shape |
| `common.errors.ApiException` | `RuntimeException(HttpStatus, code, message, errors?)`; use for coded API errors |
| `common.privacy.Masking` | `gstin(v)` → `32TTN*****1ZW` (first 5 + ***** + last 3); `udyam(v)` → `UDYAM-KL-00-****801`; short/odd values → `*****` |

**Phase 2 (registry + validation)**

| Class | What it does |
|---|---|
| `registry.validation.GstinValidator` | Static. `normalize` (uppercase, trim, strip spaces/`-`/`.`; null → ""), `validate(raw)` → `GstinValidationResult`, `isValid(raw)`, `checksumChar(gstin)` (official mod-36 over first 14 chars). Accepts `X` as 4th PAN char. Unknown state code → `formatValid=false`. |
| `registry.validation.GstinValidationResult` | record `(normalized, formatValid, checksumValid, stateCode, stateName, errors)`; `valid()` = format && checksum |
| `registry.validation.GstStateCodes` | Official codes 01–38 → names (dataset spelling). `name(code)` |
| `registry.validation.UdyamValidator` | Static. `normalize` (uppercase, remove ALL whitespace, keep hyphens), `validate(raw)` → `UdyamValidationResult`. Regex `^UDYAM-[A-Z]{2}-[0-9]{2}-[0-9]{7}$` (district `00` accepted). |
| `registry.validation.UdyamValidationResult` | record `(normalized, formatValid, stateCode, stateName, errors)` |
| `registry.validation.UdyamStateCodes` | Data-derived 2-letter code → state (14 codes); `name(code)` (null if not in data), `all()` |
| `registry.GstinRegistryLookup` | Interface: `find(gstin)` → `Optional<GstinDetails>`, `exists`, `findExisting(Collection)` (expects normalized GSTINs) |
| `registry.JpaGstinRegistryLookup` | `@Component` impl over `GstinRegistryRepository` (the bean the app injects) |
| `registry.CsvGstinRegistryLookup` | Non-bean impl: `CsvGstinRegistryLookup.fromCsv(path)`; for DB-free tests |
| `registry.GstinDetails` | record of a registry row `(gstin, legalName, tradeName, constitution, principalPlaceOfBusiness, state, registrationDate, status)`; `active()` = status == "Active" |
| `registry.GstinCorrectionService` | `correct(raw)` → `GstinCorrection`. Position-aware OCR fixes, max 2 substitutions; accepts only if EXACTLY ONE candidate is format+checksum valid AND in the registry. Never tries 5↔S / 8↔B. |
| `registry.GstinCorrection` | record `(original, correctedGstin, corrected, substitutions[{position (0-based), from, to}])` |
| `registry.GstinVerificationService` | **In-process entry point.** `verify(raw)` → `GstinVerification`; `validate(raw)`; `find(raw)` (exact lookup, normalized) |
| `registry.GstinVerification` | record `(input, normalized, formatValid, checksumValid, found, effectiveGstin, status, active, stateCode, stateName, correction, errors)` (see endpoint notes) |
| `registry.UdyamLookupService` | `validate(raw)`, `find(raw)` → `Optional<UdyamDetails>` |
| `registry.UdyamDetails` | record `(udyamNumber, enterpriseName, enterpriseType, majorActivity, nicDescription, state, city, dateOfRegistration)` |
| `registry.GstinLookupResponse` | GET GSTIN response record (adds `stateCode`, `active`) |
| `registry.MockRegistryController` | `/api/mock/**` endpoints; logs masked identifiers only |

No JPA entity exists for `smb_lead` / `lead_bundle_item` (read via JdbcTemplate only).

## 5. Endpoints

| Method | Path | Response | Errors |
|---|---|---|---|
| GET | `/actuator/health` | `{"status":"UP"}` (only actuator endpoint exposed) | - |
| GET | `/api/products` | `ProductDto[]` (25, sorted by id): `productId, category, productName, description, unit, priceMinInrPerMonth, priceMaxInrPerMonth, oneTimeSetupInr, minContractMonths, availableCityTiers[int], minEmployees, minSites, requiresGstin, requiresOneOf[str], typicalInstallDays, targetSegments[str]` | - |
| GET | `/api/products/{id}` | one `ProductDto` | 404 |
| GET | `/api/admin/data-summary` | `{rowCounts{products,gstinRegistry,udyamRegistry,leads,leadBundleItems}, funnel[{stage,leads}], activated, avgDaysLeadToActivation, avgFollowUpCallsActivated, totalAcquisitionCostInr, blendedCacInr, gstinStatusCounts{Active,Cancelled,Suspended}, integrity{leadGstinMissingInRegistry, leadUdyamMissingInRegistry}}` (decimals rounded to 2) | - |

| GET | `/api/mock/gstin/{gstin}` | **MOCK.** Normalized first. 200 `GstinLookupResponse {gstin, legalName, tradeName, constitution, principalPlaceOfBusiness, state, stateCode, registrationDate, status, active}`. Cancelled/Suspended → 200 with `active:false` | 400 `INVALID_FORMAT` / `INVALID_CHECKSUM` (+`errors`), 404 `NOT_FOUND` |
| GET | `/api/mock/udyam/{udyamNumber}` | **MOCK.** URL-decoded + whitespace removed. 200 `UdyamDetails` | 400 `INVALID_FORMAT`, 404 `NOT_FOUND` |
| POST | `/api/mock/gstin/verify` | body `{"gstin":"..."}` → 200 `GstinVerification` (always 200, even when invalid/not found) | 400 `INVALID_REQUEST` (blank/missing gstin, bad JSON) |

`GstinVerification` semantics: `formatValid`, `checksumValid`, `errors` describe the input as given (normalized). `effectiveGstin` = the registry GSTIN the input resolves to (itself, or the OCR-corrected value), null if none. `found/status/active/stateCode/stateName` describe `effectiveGstin` when found. `correction` is non-null only when a correction was applied. **Use `effectiveGstin` + `active`; treat `found=false` or `active=false` as "no valid GSTIN".**

Error body: `{"status":404,"error":"Not Found","message":"...","path":"/api/..."}`; Phase 2 errors add `"code"` and optionally `"errors":[...]` (fields omitted when null, so Phase 1 errors are unchanged). CORS: `/api/**` allows `http://localhost:5173`.

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

Phase 2 added no config keys.

## 7. Database (Neon PostgreSQL 18.6)

Flyway versions applied: **V1** `V1__init_reference_data.sql` (Phase 2 added no migrations).

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

Phase 2 facts (verified by tests against the CSVs):
- All 3534 registry GSTINs pass format + mod-36 checksum.
- GST state codes in data (14), official spelling, 0 mismatches: 03 Punjab, 07 Delhi, 08 Rajasthan, 09 Uttar Pradesh, 10 Bihar, 19 West Bengal, 23 Madhya Pradesh, 24 Gujarat, 27 Maharashtra, 29 Karnataka, 32 Kerala, 33 Tamil Nadu, 36 Telangana, 37 Andhra Pradesh.
- All 2723 Udyam numbers pass format; all district codes `00`. Udyam codes (data-derived, 1 code → 1 state): AP Andhra Pradesh, BR Bihar, DL Delhi, GJ Gujarat, KL Kerala, **KR Karnataka**, MH Maharashtra, MP Madhya Pradesh, PB Punjab, RJ Rajasthan, TN Tamil Nadu, **TS Telangana**, UP Uttar Pradesh, WB West Bengal.
- KYC OCR examples: `19E0GXH4115Y1ZL`→`19EOGXH4115Y1ZL`, `24X0RXG9365M1Z3`→`24XORXG9365M1Z3`, `27QHIXK0658U1ZL`→`27QHJXK0658U1ZL` (all corrected). **Tampered** `08YKZXS8189L1Z0` (registry `08YKZXS5189L1ZO`, 5→8) fails checksum and is NOT corrected.
- Handy test values: Active `32TTNXB7384T1ZW` (Balaji Dental Care LLP, Udyam `UDYAM-KL-00-3078801`); Cancelled `37IHKXV5167L1ZS`; Suspended `23QYBXS1811Q1ZD`; valid checksum but unregistered `27AAAXA0000A1ZR`.

## 9. Testing

All tests are pure unit tests (no Spring context, no DB). **Total: 41.**

| Test class | Tests | Covers |
|---|---|---|
| `dataload.CsvParsingTest` | 11 | Y/N, `;`/`|` lists, blanks, bundle parsing, quoted commas + BOM |
| `registry.validation.GstinValidatorTest` | 9 | all 3534 registry GSTINs valid, state table vs every row, normalization, invalid cases, prints a valid-but-unregistered GSTIN |
| `registry.validation.UdyamValidatorTest` | 5 | all 2723 valid, data-derived code→state equals `UdyamStateCodes`, OCR whitespace normalization |
| `registry.GstinCorrectionServiceTest` | 9 | 3 OCR corrections, tampered not corrected, unchanged valid GSTIN, never 5/8/S/B |
| `registry.GstinVerificationServiceTest` | 4 | active, cancelled, corrected, tampered |
| `common.privacy.MaskingTest` | 3 | masked output never shows middle characters |

- Test data: `src/test/java/com/udyam/TestData.java` exposes `DATA_DIR`, `GSTIN_CSV`, `UDYAM_CSV`, `PRODUCT_CSV`, `LEADS_CSV` (resolved from `../data`; working dir during `mvn test` is `backend/`). Registry-dependent tests use `CsvGstinRegistryLookup.fromCsv(TestData.GSTIN_CSV)`.
- There is deliberately **no** `@SpringBootTest` (it would need Neon).
- Run: `mvn clean test` from `backend/`.

## 10. Deviations from the Phase 1 prompt

- No Lombok (it was optional): DTOs are records, entities use plain getters.
- Data-summary JSON also includes `totalAcquisitionCostInr` (for transparency of the CAC calculation).
- Extra CHECK constraints on `smb_lead.stage_reached`, `smb_lead.outcome`, product price range and bundle quantity > 0.
- `GlobalExceptionHandler` maps `IllegalArgumentException` to 400.
- Phase 2: `GstinVerification` has an extra `effectiveGstin` field (so callers never have to choose between input and corrected value); `correction` is null unless a correction was applied (instead of an object with `corrected:false`).
- Phase 2: a format-valid GSTIN with an unknown state code (e.g. `99...`) is reported as `formatValid=false` / `INVALID_FORMAT`.
- Phase 2: `UdyamStateCodes` lists only the 14 codes present in the data; a well-formed Udyam number with another code is still format-valid (stateName null).
- Phase 2: `POST /api/mock/gstin/verify` returns 200 for invalid/unknown GSTINs (the result describes the problem); only a missing/blank body field is a 400.

## 11. Known issues / TODOs

- Flyway 11.7.2 (managed by Spring Boot 3.5.16) warns "PostgreSQL 18.6 is newer than this version of Flyway". Works fine; ignore, or pin a newer Flyway later if needed.
- Neon cold start makes first startup ~50 s; the pool connection-timeout is 30 s.
- Port 5432 is blocked on some networks (see section 2).
- No admin auth on `/api/admin/data-summary` (prototype only).
- Error responses echo the request `path`, which for `/api/mock/...` contains the identifier the caller sent (not logged; acceptable for a mock API).
- No Udyam OCR correction (only whitespace normalization); the KYC set's known Udyam OCR issues are whitespace only.
- Each `verify` of an unregistered/garbled GSTIN does 1–3 small Neon queries (candidates are filtered by checksum first, then one `findAllById`).

## 12. Notes for the next phase (Phase 3: rules engine - eligibility, recommendation, quote)

- **GSTIN in-process:** inject `com.udyam.registry.GstinVerificationService` and call `verify(raw)`. Treat the customer as having a valid GSTIN only if `found && active` (status exactly `Active`). `requires_gstin = Y` products need that. Use `effectiveGstin` for any stored value.
- **Udyam in-process:** inject `com.udyam.registry.UdyamLookupService` (`validate(raw)`, `find(raw)`).
- **Pure checks without DB:** `GstinValidator.validate/isValid`, `UdyamValidator.validate` (static).
- **Registry access:** inject the `GstinRegistryLookup` interface (bean = `JpaGstinRegistryLookup`); in tests use `CsvGstinRegistryLookup.fromCsv(TestData.GSTIN_CSV)`. Don't create new GSTIN repositories.
- **Catalogue:** `ProductRepository` / `ProductService` / `ProductDto` exist (arrays exposed as lists). Eligibility fields: `availableCityTiers`, `minEmployees`, `minSites`, `requiresGstin`, `requiresOneOf` (needs ≥1 of these in the same bundle; empty list = no dependency), `targetSegments` (codes: micro, prof, multi, mfg). Prices are a min–max range per unit; never invent prices.
- **Tests without DB:** load the catalogue with `CsvParsing.readAll(TestData.PRODUCT_CSV)` + the Phase 1 helpers (`splitIntList`, `splitList(..., ALT_LIST_SEPARATOR)` for `requires_one_of`).
- **Errors:** throw `ApiException(status, CODE, message, errors)` for coded errors, `NotFoundException` for plain 404s.
- **Privacy:** log via `Masking.gstin(...)` / `Masking.udyam(...)`; never log names or full identifiers.
- Use `AppProperties.referenceDate()` (2026-07-15) as "today". Never use `LocalDate.now()`.
- `DataSummaryService.FUNNEL_STAGES` holds the canonical stage order.
