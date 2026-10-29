# HANDOFF - Project Udyam Digital

Living handoff file. Every phase reads this first and updates it at the end (append; don't rewrite history).

## 1. Phases completed

| Phase | Date | Summary |
|---|---|---|
| 1 | 2026-10-29 | Spring Boot backend, Neon connection, Flyway V1 schema, idempotent CSV loader, read-only product + data-summary APIs, parsing unit tests |
| 2 | 2026-10-29 | GSTIN/Udyam validators (format, mod-36 checksum, state codes), registry-aware OCR correction, `GstinVerificationService`, mock lookup APIs under `/api/mock`, masked logging, `code`/`errors` on error JSON |
| 3 | 2026-10-30 | Rules engine: profile resolution (city → tier, type → segment, GSTIN status), fixed-point eligibility, YAML-driven recommendation, quote (MIN pricing), reference APIs, mis-selling audit (48/368 = 13.0%) |
| 4 | 2026-10-30 | AI advisor: stateless Python `ai-service` (FastAPI + Gemini 2.5 Flash, manual tool calling, grounding guard) + Spring advisor sessions/messages (Flyway V2), `/api/advisor/sessions` endpoints, internal-token filter |

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
- **ai-service (Phase 4)**: Python 3.12.5, venv at `ai-service/.venv` (git-ignored). Secrets in `ai-service/.env` (git-ignored, template `.env.example`): `GEMINI_API_KEY`, `INTERNAL_TOKEN`. Spring needs the SAME token in `application-local.yml` → `app.ai-service.token` (or env `INTERNAL_TOKEN`). Do not put the Gemini key in Spring.
  - Install: `ai-service/.venv/Scripts/python -m pip install -r ai-service/requirements.txt`
  - Run (from `ai-service/`): `.venv\Scripts\python -m uvicorn app.main:app --reload --port 8000` (bash: `.venv/bin/python ...`)
  - Start order: Spring (:8080) first, then ai-service (:8000). The advisor UI talks only to Spring.
  - Gemini check (1 call): `.venv\Scripts\python scripts\check_gemini.py` → `OK gemini-2.5-flash`
- Terminals opened before Java/Maven were installed don't have them on PATH: refresh with `$env:JAVA_HOME = [Environment]::GetEnvironmentVariable("JAVA_HOME","User"); $env:Path = [Environment]::GetEnvironmentVariable("Path","Machine") + ";" + [Environment]::GetEnvironmentVariable("Path","User")`.

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
rules/data/               In-memory rules snapshot (catalogue, city/business-type maps) + /api/reference
rules/profile/            BusinessProfile (request) → ResolvedProfile; Segment, Need, GstinStatus enums
rules/eligibility/        Catalogue rules, fixed-point bundle resolution, /api/eligibility
rules/recommend/          Template-driven recommendation + quantity rules, /api/recommend
rules/quote/              Pricing + quote, /api/quote
rules/audit/              Mis-selling audit over activated leads, /api/admin/audit/mis-selling
advisor/                  Advisor sessions (JDBC store), ai-service RestClient, /api/advisor/sessions, InternalTokenFilter
```

`ai-service/` (Python, Phase 4):
```
app/main.py            FastAPI: GET /health, POST /chat (X-Internal-Token required); create_app() for tests
app/advisor.py         Advisor.chat(): tool loop (max 6 model calls/turn), guard retry + template fallback, stage logic, language detection
app/prompts.py         SYSTEM_PROMPT + build_system_prompt() (cities, catalogue names, current state; no prices)
app/tools.py           TOOL_DECLARATIONS + ToolContext (dispatcher), merge_profile, summaries for the model
app/guard.py           check() grounding guard, extract_amounts(), render_template() EN/HI fallback, fmt_inr()
app/llm.py             Llm protocol, GeminiLlm (manual function calling, retries 429/5xx x3), history types
app/backend_client.py  BackendClient (httpx, 10 s, X-Internal-Token), BackendUnavailable
app/reference.py       ReferenceProvider: cities + products from Spring (cached), CITY_ALIASES
app/config.py, models.py   Settings (.env) and the /chat pydantic models (camelCase JSON)
tests/                 pytest (fake LLM + respx-mocked Spring, no network)
evals/                 scenarios.json (15) + run_scenarios.py (live, manual) + results/
scripts/check_gemini.py   one-call connectivity check
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
| `registry.JpaGstinRegistryLookup` | `@Component` impl over `GstinRegistryRepository` (the bean the app injects). Since Phase 3: loads all 3534 rows into an in-memory map on `ApplicationReadyEvent` (never caches an empty table) and serves lookups from memory |
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

**Phase 3 (rules engine, `com.udyam.rules`)**

| Class | What it does |
|---|---|
| `data.CatalogueProduct` | Immutable product record (`productId, category, productName, unit, priceMinInr, priceMaxInr, setupFeeInr, minContractMonths, cityTiers, minEmployees, minSites, requiresGstin, requiresOneOf, installDays, targetSegments`); `from(Product)`, `from(CSVRecord)` |
| `data.Catalogue` | 25 products by id; `get(id)` (case-insensitive, throws 400 `UNKNOWN_PRODUCT`), `find`, `all`, `name` |
| `data.RulesData` | Snapshot: catalogue + `city(name)` → `CityRef(city, tier, state)` + `businessType(name)` → `BusinessTypeRef(businessType, segment)`. Throws on ambiguous mappings. `RulesData.fromCsv(dataDir)` for tests |
| `data.RulesDataProvider` | `RulesData get()`. App bean: `DbRulesDataProvider` (loads from Neon on `ApplicationReadyEvent`; 503 `RULES_DATA_NOT_READY` before that). Tests: `() -> data` |
| `profile.ProfileResolver` | `resolve(BusinessProfile)` → `ResolvedProfile`. Uses `GstinVerificationService.validate` + `find` (no OCR correction for typed input) |
| `profile.Segment` | `MICRO/PROF/MULTI/MFG` with `code()` (micro/prof/multi/mfg) and JSON display name; `parse` accepts name/code |
| `profile.GstinStatus` | `VERIFIED_ACTIVE, INACTIVE, INVALID, DECLARED_UNVERIFIED, NONE` |
| `profile.Need` | 16 needs (WHATSAPP, CCTV, POS_BILLING, CLOUD_EMAIL, BACKUP, SECURITY, TELEPHONY, TOLL_FREE, SMS, WEBSITE, FLEET, MACHINE_IOT, FAILOVER, GUEST_WIFI, STATIC_IP, MULTI_SITE_MGMT) |
| `eligibility.EligibilityService` | `ruleReasons(ctx, product)`, `resolveBundle(ctx, ids[, userRemovedIds])` (fixed point), `check(ctx, id, bundle)`, `checkAll(ctx, bundle)`, `auditReasons(ctx, id, soldIds)` (no cascade). Constant `KYC_CONDITION` |
| `eligibility.EligibilityContext` | Interface (`cityTier, employees, sites, gstinStatus, gstinRegistryStatus`), implemented by `ResolvedProfile` |
| `eligibility.EligibilityResult` / `Reason` / `ReasonCode` / `BundleResolution` | `{productId, productName, eligible, conditional, condition, reasons[{code, message}]}`; `BundleResolution{kept[], removed[]}` |
| `recommend.RecommendationProperties` | Binds `recommendation-templates.yml` (prefix `recommendation`) |
| `recommend.QuantityCalculator` | `quantity(profile, product)` from YAML rules (BigDecimal factors, ceil, clamp 1..500) |
| `recommend.RecommendationService` | `recommend(BusinessProfile | ResolvedProfile)` → `Recommendation` |
| `quote.PriceCalculator` | `unitPrice(product)` per `app.pricing.mode` |
| `quote.QuoteService` | `quote(profile, items, removedProductIds)` → `Quote` |
| `audit.MisSellingAuditor` | Pure: `report(List<SoldBundle>)`, `audit(bundles, GstinSemantics)` |
| `audit.MisSellingAuditService` | Loads activated leads + bundles (JdbcTemplate) → report |

**Phase 4 (Spring, `com.udyam.advisor`)**

| Class | What it does |
|---|---|
| `AdvisorService` | `createSession(uiLanguage)` (stores the fixed bilingual `GREETING` as the first assistant message, no LLM), `sendMessage(id, text)` (saves user msg → calls ai-service with full history + profile + state → saves assistant msg + toolTrace + guard → updates session), `getSession(id)`. ai-service failure → `ApiException(503, AI_UNAVAILABLE)` with the user message already saved |
| `AdvisorController` | `/api/advisor/sessions` endpoints (thin) |
| `AdvisorStore` / `JdbcAdvisorStore` | Interface + Postgres impl (JdbcTemplate, JSONB via `?::jsonb`); records `Session`, `Message` |
| `AiServiceClient` | POST `/chat` via the `aiServiceRestClient` bean; any failure/empty body → `AiUnavailableException` |
| `AiChatContract` | Records `Request{sessionId, messages[{role, content}], profile, uiLanguage, state{stage, recommendation, quote}}`, `Response{reply, detectedLanguage, profile, stage, recommendation, quote, toolTrace, guard}` |
| `AdvisorConfig` | `aiServiceRestClient` bean: base URL, connect 5 s / read `timeout-ms`, default `X-Internal-Token` header |
| `InternalTokenFilter` | Sets request attribute `udyam.caller` = `ai-service` (valid token) or `public`; never blocks; logs `API <method> <path> caller=...` with GSTIN-like path segments masked |
| `AiServiceProperties` | `app.ai-service.*` |

**Phase 4 (Python, `ai-service/app`)**

| Name | What it does |
|---|---|
| Tools | `save_profile(fields)` (local; validates/merges; **auto** `lookup_gstin` for a new GSTIN and **auto** `recommend_bundle` once required facts + GSTIN answer are known or changed), `lookup_gstin(gstin)` → `GET /api/mock/gstin/{gstin}`, `recommend_bundle()` → `POST /api/recommend`, `get_quote(add?, remove?, setQuantity?, items?)` → `POST /api/quote` (removed ids sent as `removedProductIds`), `check_eligibility(productIds)` → `POST /api/eligibility`, `mark_off_topic()` (local) |
| Required facts | `businessType`, `city` (or `cityTier` if the city is unknown), `employees`, `sites`; plus the GSTIN question answered (`gstin` or `gstinDeclared` true/false) |
| Stage logic (deterministic, `Advisor._stage`) | last bundle event `quote` → CUSTOMISING; `recommend` → RECOMMENDED; bundle exists + acceptance phrase ("yes", "go ahead", "theek hai", "haan", "हाँ", ...) without change words → READY_FOR_CHECKOUT; only `mark_off_topic` called → OUT_OF_SCOPE; Spring failure → previous stage unchanged |
| Guard (`guard.check`) | Allowed amounts = every `*Inr` number in engine JSON seen in the conversation (+ profile `monthlyTelecomSpendInr`). Amount formats: ₹/Rs/INR/rupees/रुपये, Indian grouping, Devanagari digits. Products (IDs P01–P25 or exact catalogue names) must be in an engine result; ineligible ones only in a sentence with a reason word. Violation → one retry with a corrective system note → else `render_template()` from the latest quote JSON (EN or HI) |

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
| GET | `/api/reference/cities` | `[{city, tier, state}]` (22) | 503 `RULES_DATA_NOT_READY` |
| GET | `/api/reference/business-types` | `[{businessType, segment}]` (35) | 503 |
| POST | `/api/profile/resolve` | body `BusinessProfile` → `ResolvedProfile` | 400 `INVALID_REQUEST`, `CITY_REQUIRED`, `CITY_TIER_REQUIRED`, `INVALID_SEGMENT` |
| POST | `/api/eligibility` | `{profile, bundleProductIds?}` → `{resolvedProfile, bundleProductIds, results[25 EligibilityResult]}` | 400 as above + `UNKNOWN_PRODUCT` |
| POST | `/api/recommend` | `{profile}` → `{resolvedProfile, segment, included[Line], optionalAddOns[Line], excluded[EligibilityResult], notes[], quote}` | 400 as above |
| POST | `/api/quote` | `{profile, items[{productId, quantity 1..500}] (non-empty), removedProductIds?}` → `Quote` | 400 as above + `DUPLICATE_PRODUCT` |
| POST | `/api/advisor/sessions` | body `{"uiLanguage": "en"|"hi"|null}` (optional) → `{sessionId (UUID), greeting}` | 400 |
| POST | `/api/advisor/sessions/{id}/messages` | `{"text": 1..1000 chars}` → `{sessionId, reply, stage, profile, recommendation (latest /api/recommend JSON or null), quote (latest Quote JSON or null), detectedLanguage}` | 400 `INVALID_REQUEST`, 404, **503 `AI_UNAVAILABLE`** (user message still saved) |
| GET | `/api/advisor/sessions/{id}` | `{sessionId, createdAt, updatedAt, uiLanguage, detectedLanguage, stage, profile, recommendation, quote, messages[{role, content, createdAt}]}` | 400 (bad UUID), 404 |
| GET | `/api/admin/audit/mis-selling` | `{hasGstinFlag: Variant, registryActive: Variant}`; Variant = `{gstinSemantics, description, activatedChecked, bundlesWithViolations, misSoldPercent, totalViolations, violationsByProductAndReason[{productId, productName, reason, count}], sampleLeadIds[≤10]}` | - |

**ai-service (internal, called only by Spring)**: `GET /health` → `{status: "OK", model}`; `POST /chat` (header `X-Internal-Token`, else 401 `{detail: {code: UNAUTHORIZED}}`) body `{sessionId, messages[{role: user|assistant, content}], profile, uiLanguage, state{stage, recommendation, quote}}` → `{reply, detectedLanguage, profile, stage, recommendation, quote, toolTrace[{tool, args (GSTIN masked), ok, ms}], guard{passed, retries, fallbackUsed, violations[]}}`; 503 `LLM_UNAVAILABLE` when Gemini fails after retries; 422 on a malformed body.

**Stage values**: `COLLECTING`, `RECOMMENDED`, `CUSTOMISING`, `READY_FOR_CHECKOUT`, `OUT_OF_SCOPE`.

**Phase 3 shapes**
- `BusinessProfile`: `businessType?, segment?, city?, cityTier? (1|2), employees (1..100000, required), sites (1..500, required), gstin?, gstinDeclared?, currentInternet?, monthlyTelecomSpendInr? (≥0), fibreUnavailable?, needs? [Need], officeUsers?, mobileLines?, cameras?, vehicles?, machines? (1..500)`. Needs city OR cityTier.
- `ResolvedProfile`: `businessType, segment (display name), segmentCode, segmentSource (GIVEN|BUSINESS_TYPE|INFERRED), city, cityTier, cityState, employees, sites, gstinStatus, gstin, gstinRegistryStatus, gstinErrors[], gstinAutofill{legalName, tradeName, state, principalPlaceOfBusiness}|null, currentInternet, monthlyTelecomSpendInr, fibreUnavailable, needs[], officeUsers, mobileLines, cameras, vehicles, machines, warnings[]`.
- `Recommendation.Line`: `productId, productName, category, unit, quantity, unitPriceInr, monthlyTotalInr, setupFeeInr, role (PRIMARY|INCLUDED|NEED|ADD_ON|UPGRADE), replaces (upgrade only), why, conditional`.
- `Quote`: `pricingMode, lines[{productId, productName, unit, quantity, unitPriceInr, monthlyTotalInr, setupFeeInr, minContractMonths, installDays, conditional}], monthlyTotalInr, oneTimeTotalInr, contractMonths (max), maxInstallDays, estimatedActivationDate (reference date + maxInstallDays; null if no lines), conditional, removed[{productId, productName, quantity, reasons[]}], availableAddOns[{productId, productName, unit, unitPriceInr, defaultQuantity, setupFeePerUnitInr, conditional}], ineligibleProducts[EligibilityResult], comparison{currentSpendInr, newMonthlyInr, differenceInr}|null, notes[]`.

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

Phase 2 added no config keys. Phase 3 added:

| Key | Default | Purpose |
|---|---|---|
| `spring.config.import` | `classpath:recommendation-templates.yml` | Loads the recommendation templates |
| `app.pricing.mode` | `MIN` | `MIN` = catalogue `price_min` (digital price); `MID` = (min+max)/2 rounded half-up |
| `recommendation.*` (in `recommendation-templates.yml`) | - | `fallback-chain` [P05,P03,P02,P01,P04], `fibre-products` [P01,P02,P03], `segments.{micro,prof,multi,mfg}.{primary, primary-if-no-fibre, upgrades, included, optional}`, `needs[{need, products}]`, `quantities.{default-rule, unit-rules, products[{product-id, profile-field, segments, fallback}]}`, `why[{product-id, text}]` |

Recommendation templates (as approved):

| Segment | Primary (fallback chain below it) | Upgrade | Included | Optional add-ons |
|---|---|---|---|---|
| micro | P01 (P04 if no fibre) | - | P07 | P19 P22 P23 P15 P18 P14 |
| prof | P02 | P03 | P16 P08 P20 P17 | P11 P09 P12 P14 P15 P07 |
| multi | P02 (P04 if no fibre) | - | P08 P09 P19 P23 P10 | P22 P13 P15 P25 |
| mfg | P05 → P03 → P02 | P06 | P08 P16 P20 | P21 P11 P23 P24 P25 |

Phase 4 added:

| Key | Default | Purpose |
|---|---|---|
| `app.ai-service.base-url` | `${AI_SERVICE_URL:http://localhost:8000}` | Python advisor |
| `app.ai-service.token` | `${INTERNAL_TOKEN:}` | Shared secret (set in `application-local.yml`); empty → ai-service answers 401 → 503 AI_UNAVAILABLE |
| `app.ai-service.timeout-ms` | 45000 | Read timeout for /chat |
| ai-service `.env` | `GEMINI_API_KEY`, `GEMINI_MODEL=gemini-2.5-flash`, `BACKEND_BASE_URL=http://localhost:8080`, `INTERNAL_TOKEN`, `BACKEND_TIMEOUT_S=10`, `GEMINI_TIMEOUT_S=30` | Settings in `app/config.py` (also `temperature=0.2`, `max_tool_iterations=6`, `gemini_max_attempts=3`) |

Quantity rules: per-site units = sites; P19 = sites; P16 = officeUsers else prof ceil(0.7×emp), mfg ceil(0.15×emp), others emp; P20 = officeUsers else prof ceil(0.5×emp), mfg ceil(0.1×emp), others 2; P07/P08 = mobileLines else micro 2, prof max(2, ceil(emp/5)), multi max(2, ceil(0.6×sites)), mfg 4; P23 = cameras else micro 2, multi 2×sites, mfg 4, others 2; P24 = machines else 5; P25 = vehicles else 2; P12 = max(2, ceil(emp/5)); everything else 1.

## 7. Database (Neon PostgreSQL 18.6)

Flyway versions applied: **V1** `V1__init_reference_data.sql` (Phases 2 and 3 added no migrations; Phase 3 templates live in YAML, not the DB), **V2** `V2__advisor_sessions.sql` (Phase 4).

| Table (V2) | Key columns / types |
|---|---|
| `advisor_session` | `id UUID` PK, `created_at`/`updated_at TIMESTAMPTZ`, `ui_language`, `detected_language`, `stage` (CHECK: the 5 stage values, default COLLECTING), `profile_json JSONB NOT NULL DEFAULT '{}'`, `last_recommendation_json JSONB`, `last_quote_json JSONB`; index on `stage` |
| `advisor_message` | `id BIGSERIAL` PK, `session_id UUID` FK → advisor_session ON DELETE CASCADE, `role` (CHECK user/assistant), `content TEXT`, `tool_trace_json JSONB`, `guard_json JSONB`, `created_at`; index `(session_id, id)` |

Back-office query for "leads the advisor could not close": `SELECT id, stage, profile_json, updated_at FROM advisor_session WHERE stage <> 'READY_FOR_CHECKOUT' ORDER BY updated_at DESC;`

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

Phase 3 facts (from smb_leads, verified by tests and live API):
- **Cities (22, one tier + state each):** tier 1 (8): Ahmedabad, Bengaluru, Chennai, Delhi, Hyderabad, Kolkata, Mumbai, Pune. Tier 2 (14): Bhopal, Coimbatore, Indore, Jaipur, Kochi, Lucknow, Ludhiana, Mysuru, Nagpur, Nashik, Patna, Surat, Vadodara, Visakhapatnam.
- **Business types (35, one segment each):** micro (10) Bakery, Boutique, Hardware store, Kirana store, Mobile repair shop, Optician, Pharmacy, Salon, Stationery shop, Sweet shop · prof (10) Architecture studio, CA firm, Coaching centre, Dental clinic, Diagnostic lab, Insurance agency, Law office, Physiotherapy clinic, Real estate agency, Travel agency · multi (8) Apparel retail chain, Bakery chain, Budget hotel group, Electronics retail chain, Gym chain, Pharmacy chain, Pre-school chain, Restaurant chain · mfg (7) Auto-parts unit, Food processing unit, Furniture workshop, Packaging unit, Plastic moulding unit, Printing press, Textile unit.
- **GST-required products (10):** P03, P05, P06, P10, P12, P13, P14, P15, P21, P24. Products with dependencies: P09/P10/P21 (P01|P02|P03|P05|P06), P11 (P01|P02|P03), P12/P23 (P01–P06), P22 (P01–P04).
- **Mis-selling audit:** (a) has_gstin flag: 48/368 bundles (13.0%), 53 violations: P03 tier 24, P03 employees 7, P03 GSTIN 3, P06 employees 1, P10 GSTIN 1, P11 dependency 9, P12 GSTIN 2, P14 GSTIN 1, P15 GSTIN 5. (b) registry Active: 56/368 (15.2%), 63 violations (adds GSTIN_INACTIVE: P03 3, P05 1, P10 1, P13 1, P15 4). 22 activated leads have Cancelled/Suspended GSTINs.
- **Phase 4 live evals** (`ai-service/evals/results/`, real Gemini 2.5 Flash): scenario **1 PASS** (EN one-shot kirana, exactly 2 Gemini calls, reply ₹1597 grounded), **2 PASS** (Hindi dental clinic Kochi, GSTIN autofill "Balaji", no P03), **3 PASS** (Hinglish restaurant chain "Bangalore" → Bengaluru, gstinDeclared, P10 conditional). Scenarios **4–15 NOT run**: the free-tier key hit `429 RESOURCE_EXHAUSTED` after ~18 calls (report `run-20261030-013236.json` shows them as 503 failures for that reason, not as advisor failures). Re-run later with `evals/run_scenarios.py --rerun-failed --delay 6`. Accepted by the user on 2026-10-30 to save quota.
- **Reference scenarios (MIN pricing):** kirana tier 2 no GSTIN → P01 + P07×2 = ₹1,597/mo; dental tier 1 14 emp GSTIN → P02 + P16×10 + P08×3 + P20×7 + P17 = ₹5,738/mo; restaurant chain 8 sites 60 emp GSTIN → ₹46,955/mo + ₹1,00,800 one-time, 24 months, activation 2026-07-29; textile 85 emp GSTIN → P05 primary ₹12,912/mo; factory tier 2 8 emp → P05 (emp) → P03 (tier) → P02.

## 9. Testing

All tests are pure unit tests (no Spring context, no DB). **JUnit total: 126** (41 after Phase 2, +76 in Phase 3, +9 in Phase 4). **pytest (ai-service): 42.**

| Test class | Tests | Covers |
|---|---|---|
| `dataload.CsvParsingTest` | 11 | Y/N, `;`/`|` lists, blanks, bundle parsing, quoted commas + BOM |
| `registry.validation.GstinValidatorTest` | 9 | all 3534 registry GSTINs valid, state table vs every row, normalization, invalid cases, prints a valid-but-unregistered GSTIN |
| `registry.validation.UdyamValidatorTest` | 5 | all 2723 valid, data-derived code→state equals `UdyamStateCodes`, OCR whitespace normalization |
| `registry.GstinCorrectionServiceTest` | 9 | 3 OCR corrections, tampered not corrected, unchanged valid GSTIN, never 5/8/S/B |
| `registry.GstinVerificationServiceTest` | 4 | active, cancelled, corrected, tampered |
| `common.privacy.MaskingTest` | 3 | masked output never shows middle characters |
| `rules.data.RulesDataTest` | 4 | 25 products, 22 cities, 35 types, every template product id exists |
| `rules.profile.ProfileResolverTest` | 9 | city/tier, unknown city, GSTIN statuses + autofill + state warning, no OCR correction, segment inference |
| `rules.eligibility.EligibilityServiceTest` | 38 | 33 parameterized rule cases + messages, fixed point, cascade chain, user-removed, checkAll |
| `rules.recommend.RecommendationServiceTest` | 12 | the 10 required scenarios + declared GSTIN + explicit quantities |
| `rules.quote.QuoteServiceTest` | 10 | 3 hand-calculated totals, MID rounding, removed/cascade, add-ons, comparison, validation |
| `rules.recommend.AllLeadsRecommendationTest` | 1 | all 5000 leads: no exception, everything included eligible, total > 0 |
| `rules.audit.MisSellingAuditorTest` | 2 | variant (a) exactly 48/53 + breakdown; variant (b) 56/63 |

| `advisor.AdvisorServiceTest` | 7 | greeting, round trip persists both messages + stage/profile, state sent back next turn, 503 on 5xx/timeout with user msg saved, unknown stage, 404 (MockRestServiceServer + `InMemoryAdvisorStore`) |
| `advisor.InternalTokenFilterTest` | 2 | caller detection (never blocks), GSTIN masking in logged paths |

pytest (`ai-service/tests`, run `.venv\Scripts\python -m pytest -q`): `test_tools.py` (12: endpoint/JSON per tool, save_profile validation, auto lookup + auto recommend), `test_advisor_loop.py` (10: one-shot = 2 model calls, prompt state, loop limit 6, Spring 500/timeout → friendly reply + stage unchanged, stages, language), `test_guard.py` (13: amount formats incl. ₹1,23,456 and Devanagari, invented ₹499, products/reasons, retry, template fallback), `test_api.py` (7: health, 401 missing/wrong/empty token, camelCase contract, 503, 422). Fixtures in `tests/conftest.py` (`FakeLlm`, `kirana_recommendation()`, respx `spring`).

- Rules tests use `src/test/java/com/udyam/rules/RulesFixture.java`: the whole engine wired by hand from the CSVs (`RulesData.fromCsv`, `CsvGstinRegistryLookup`) and the real `recommendation-templates.yml` (bound with Spring's `Binder`). Constants: `ACTIVE_GSTIN`, `CANCELLED_GSTIN`, `SUSPENDED_GSTIN`; helpers `profile(...)`, `resolved(...)`.

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
- Phase 3: `POST /api/quote` accepts optional `removedProductIds` (products the user toggled off) so dependents get `DEPENDENCY_REMOVED` "removed because X was removed"; without it a missing dependency is `DEPENDENCY_MISSING`.
- Phase 3: upgrades (prof P03, mfg P06) are returned in `optionalAddOns` with `role: UPGRADE` and `replaces` (swap, not add).
- Phase 3: `fibreUnavailable` applies to all segments: the fallback chain and upgrades skip P01/P02/P03 (micro/multi switch straight to P04).
- Phase 3: an invalid/unregistered GSTIN gives `GSTIN_REQUIRED` (message says it could not be verified); Cancelled/Suspended gives `GSTIN_INACTIVE`.
- Phase 3: added a P19 quantity rule (= sites; unit is "per counter") found by the restaurant-chain test; matches 100% of activated bundles.
- Phase 3: the rules engine reads an in-memory snapshot loaded from Neon at `ApplicationReadyEvent` (not at bean creation, so a fresh DB is loaded first); the GST registry is cached in memory for <30 ms responses.
- Phase 3: `/api/eligibility` also returns `resolvedProfile`; quote also returns `conditional` and `notes` (price note, no discounts/taxes, activation assumption, KYC condition).
- Phase 4: `lookup_gstin` calls `GET /api/mock/gstin/{gstin}` (not `/api/profile/resolve`, which needs employees/sites first).
- Phase 4: `/chat` request has an extra `state {stage, recommendation, quote}` (Spring stores it; the service stays stateless) so stage logic, customisation and the guard know the previous engine results.
- Phase 4: `get_quote` takes `add/remove/setQuantity` (plus `items`) relative to the current bundle instead of only a full list; a 6th local tool `mark_off_topic` sets OUT_OF_SCOPE.
- Phase 4 (quota): `save_profile` automatically runs `lookup_gstin` for a new GSTIN and `recommend_bundle` when the required facts are complete or changed, in the same step. One-shot messages need 2 Gemini calls instead of 3–4.
- Phase 4: evals talk to ai-service `/chat` directly (they need `toolTrace` and `guard`, which the Spring reply does not expose); 3/15 run live (see section 8).
- Phase 4: the Spring advisor reply hides `toolTrace`/`guard` from the frontend; both are stored in `advisor_message`.

## 11. Known issues / TODOs

- Flyway 11.7.2 (managed by Spring Boot 3.5.16) warns "PostgreSQL 18.6 is newer than this version of Flyway". Works fine; ignore, or pin a newer Flyway later if needed.
- Neon cold start makes first startup ~50 s; the pool connection-timeout is 30 s.
- Port 5432 is blocked on some networks (see section 2).
- No admin auth on `/api/admin/data-summary` (prototype only).
- Error responses echo the request `path`, which for `/api/mock/...` contains the identifier the caller sent (not logged; acceptable for a mock API).
- No Udyam OCR correction (only whitespace normalization); the KYC set's known Udyam OCR issues are whitespace only.
- GSTIN lookups are served from the in-memory registry cache since Phase 3 (no Neon query per lookup). The cache is never refreshed; fine for read-only reference data, restart the app if the table changes.
- Rules endpoints return 503 `RULES_DATA_NOT_READY` for the few seconds between "Started" and the ready event.
- Spend comparison is facts only (new − current); it does not know what the current spend covers.
- `estimatedActivationDate` = reference date + max install days; KYC (Phase 6) / tracker (Phase 8) should shift it from the KYC completion date.
- No auth on `/api/admin/**`.
- Phase 4: Gemini free-tier quota is small (this key hit 429 after ~18 generateContent calls in one session). Live evals 4–15 still to be run; demo should avoid long multi-turn rehearsals on the same day. Each turn = 2–4 model calls.
- Phase 4: Windows PowerShell 5.1 `Get-Content`/`Set-Content` corrupt UTF-8 (Devanagari) files; edit Python/Java files with an editor, not PowerShell text replacement.
- Phase 4: the guard matches exact catalogue names/IDs; a paraphrased product name ("Business Fibre 300") is not checked (amounts always are).
- Phase 4: no rate limiting on `/api/advisor/**`; a stuck ai-service holds a Spring thread up to 45 s.

### Privacy (for the 1-page privacy note)
- Conversation text (the full history of each advisor session) and the collected profile (business type, city, staff, GSTIN, needs) are sent to the **Google Gemini API** on every turn. Engine JSON returned by tools is also sent back to Gemini.
- Google's Gemini API terms (to the best of our knowledge; not re-checked live in this session, verify at https://ai.google.dev/gemini-api/terms before submission): on the **free (unpaid) tier**, Google may use prompts and responses to improve its products and human reviewers may read them, so users should not submit sensitive or personal information. On the **paid tier**, prompts and responses are not used to improve products and are only kept for a limited period for abuse monitoring. → For production, use a paid key / Vertex AI and say so in the privacy note.
- Not logged in plain text: message text, names, full GSTINs (ai-service logs session id prefix, tool names, stage, guard result, latency; Spring logs ids/stages/timings and masks GSTINs in paths and tool traces).
- Stored in Neon: full conversation text (`advisor_message.content`) and profile JSON. No retention job yet (TODO: define and implement a retention period).

## 12. Notes for the next phase (Phase 5: React frontend - advisor chat + quote/checkout)

The frontend talks ONLY to Spring (`http://localhost:8080`, CORS allows `http://localhost:5173`). Never call the ai-service or Gemini from the browser.

**Chat UI**
1. On load: `POST /api/advisor/sessions` `{"uiLanguage": "en"|"hi"}` → show `greeting` as the first bot bubble; keep `sessionId` (e.g. localStorage) so a reload can call `GET /api/advisor/sessions/{id}` and re-render `messages[]` (+ `stage`, `recommendation`, `quote`).
2. Send: `POST /api/advisor/sessions/{id}/messages` `{"text"}` (1..1000 chars) → append `reply` as a bot bubble; replies are plain text with simple line breaks / `*` bullets / `**bold**` (render light markdown or plain text).
3. Errors: 503 `AI_UNAVAILABLE` → show "Advisor is busy, try again" with a retry button (the user message is already saved; on retry send the text again or just a nudge). 400 `INVALID_REQUEST` → field messages in `errors[]`. Typical latency 3–10 s (Gemini): show a typing indicator.

**Quote panel (render from JSON, never from the bot's text)**
- Source of truth: `quote` from the latest message reply (or the session). Shape = Phase 3 `Quote`: `lines[{productId, productName, unit, quantity, unitPriceInr, monthlyTotalInr, setupFeeInr, minContractMonths, installDays, conditional}]`, `monthlyTotalInr`, `oneTimeTotalInr`, `contractMonths`, `estimatedActivationDate`, `conditional`, `removed[{productId, productName, quantity, reasons[{code, message}]}]` (grey out with reason), `availableAddOns[{productId, productName, unit, unitPriceInr, defaultQuantity, setupFeePerUnitInr, conditional}]` (toggles), `ineligibleProducts[]`, `comparison{currentSpendInr, newMonthlyInr, differenceInr}|null`, `notes[]` (show verbatim).
- `recommendation` adds `included[].why`, `included[].role` (PRIMARY/INCLUDED/NEED), `optionalAddOns[]` (UPGRADE has `replaces`), `excluded[]` with reasons, `notes[]` (fallback explanation).
- Toggles / quantity steppers in the UI: call `POST /api/quote` directly with `{profile: <session profile>, items, removedProductIds}` for instant re-pricing (no LLM), then show the new JSON. (These UI-only changes are not written back to the advisor session yet; if the bot must know, send a chat message such as "remove CCTV".)
- Format money with Indian grouping (₹1,00,800); integers only; no taxes/discounts.
- `conditional: true` → badge "Subject to GSTIN verification at KYC".

**Stages** (`stage` on every reply): `COLLECTING` (chat only) → `RECOMMENDED` (show quote panel) → `CUSTOMISING` (quote changed) → `READY_FOR_CHECKOUT` (enable "Review quote & start KYC"; KYC is Phase 6/7) · `OUT_OF_SCOPE` (last message was off-topic; keep chatting).

**Profile** (`profile` on every reply): `businessType, city, cityTier, employees, sites, gstin, gstinDeclared, needs[], ...` → can show a small "Your business" summary; `recommendation.resolvedProfile.gstinAutofill` has legal/trade name for display.

**Running locally for frontend work**: Spring (local profile) + ai-service (`uvicorn app.main:app --port 8000`). Without the ai-service, chat returns 503 but `/api/quote`, `/api/recommend`, reference endpoints still work, so the quote panel can be built against those (and `backend/http-examples/*.json`) without spending Gemini quota.

### Advisor tool contract (Phase 4, implemented)

The advisor NEVER decides eligibility, products or prices itself. It collects facts, calls these backend endpoints as tools, and explains their output. Base URL `http://localhost:8080`, JSON, CORS allows `http://localhost:5173`.

**Tools (map 1:1 to endpoints)**

| Tool | Endpoint | Body → use |
|---|---|---|
| `resolve_profile` | `POST /api/profile/resolve` | `BusinessProfile` → `ResolvedProfile`. Call as soon as a GSTIN is given: `gstinAutofill` pre-fills legal/trade name, state, address; `warnings` (state mismatch, inactive GSTIN, inferred segment) should be mentioned |
| `recommend_bundle` | `POST /api/recommend` | `{"profile": BusinessProfile}` → `Recommendation`. Present `included` (use `why`, translate/rephrase only), `quote.monthlyTotalInr` / `oneTimeTotalInr` / `contractMonths` / `estimatedActivationDate`, offer `optionalAddOns` (UPGRADE = swap `replaces`), explain `excluded[].reasons[].message` and `notes` |
| `get_quote` | `POST /api/quote` | `{"profile", "items":[{"productId","quantity"}], "removedProductIds"?}` → `Quote`. Call on every add/remove/quantity change; report `removed[]` reasons, never drop silently; when the user removes an item, send it in `removedProductIds` |
| `check_eligibility` | `POST /api/eligibility` | `{"profile", "bundleProductIds"?}` → all 25 with reasons. Use for "can I get X?" questions |
| `list_cities` / `list_business_types` | `GET /api/reference/cities`, `/business-types` | To map free text to known values (unknown city → ask for tier 1/2) |

**Fields the advisor must collect** (send as `BusinessProfile`)
- Required: `employees` (int ≥1), `sites` (int ≥1), `city` (or `cityTier` 1|2 if the city is unknown → API returns 400 `CITY_TIER_REQUIRED`).
- Strongly recommended: `businessType` (free text; known types map to a segment), `gstin` (or `gstinDeclared: true` if they have one but don't know it → GST products shown as `conditional`, "Active GSTIN will be verified at KYC"), `needs` (subset of the 16 `Need` values).
- Optional: `fibreUnavailable`, `currentInternet`, `monthlyTelecomSpendInr` (enables `quote.comparison`), `officeUsers`, `mobileLines`, `cameras`, `vehicles`, `machines` (override default quantities), `segment` (override).

**Advisor rules**
- Prices only from `unitPriceInr` / `monthlyTotalInr` / `quote.*` (integer ₹, catalogue MIN price, no discounts or taxes). Quote `notes` verbatim where relevant.
- GSTIN typed by the user: send it as `profile.gstin`; do NOT OCR-correct it. `gstinStatus` INACTIVE/INVALID means no GST-only products.
- Error JSON: `{status, error, code?, message, path, errors?}`; 400 codes `INVALID_REQUEST` (field messages in `errors`), `CITY_REQUIRED`, `CITY_TIER_REQUIRED`, `INVALID_SEGMENT`, `UNKNOWN_PRODUCT`, `DUPLICATE_PRODUCT`; 503 `RULES_DATA_NOT_READY` (retry).
- Example bodies: `backend/http-examples/recommend-*.json`, `quote-*.json`, `eligibility-cancelled.json`, `profile-resolve.json`.

**Backend reminders (unchanged)**
- In-process Java: `RecommendationService.recommend`, `QuoteService.quote`, `EligibilityService.check/checkAll/resolveBundle`, `ProfileResolver.resolve`, `GstinVerificationService.verify` (OCR documents, KYC), `UdyamLookupService`.
- Tests without DB: `RulesFixture` (rules), `CsvGstinRegistryLookup` + `TestData` (registries).
- Errors: `ApiException(status, CODE, message, errors)`; privacy: `Masking.*` in logs; "today" = `AppProperties.referenceDate()` (2026-07-15), never `LocalDate.now()`.
