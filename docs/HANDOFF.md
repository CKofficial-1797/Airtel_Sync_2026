# HANDOFF - Project Udyam Digital

Living handoff file. Every phase reads this first and updates it at the end (append; don't rewrite history).

## 1. Phases completed

| Phase | Date | Summary |
|---|---|---|
| 1 | 2026-10-29 | Spring Boot backend, Neon connection, Flyway V1 schema, idempotent CSV loader, read-only product + data-summary APIs, parsing unit tests |
| 2 | 2026-10-29 | GSTIN/Udyam validators (format, mod-36 checksum, state codes), registry-aware OCR correction, `GstinVerificationService`, mock lookup APIs under `/api/mock`, masked logging, `code`/`errors` on error JSON |
| 3 | 2026-10-30 | Rules engine: profile resolution (city → tier, type → segment, GSTIN status), fixed-point eligibility, YAML-driven recommendation, quote (MIN pricing), reference APIs, mis-selling audit (48/368 = 13.0%) |
| 4 | 2026-10-30 | AI advisor: stateless Python `ai-service` (FastAPI + Gemini 2.5 Flash, manual tool calling, grounding guard) + Spring advisor sessions/messages (Flyway V2), `/api/advisor/sessions` endpoints, internal-token filter |
| 5 | 2026-10-30 | React frontend (Landing, Advisor chat, Quote & checkout, KYC/Track placeholders, EN/HI) + orders backend (Flyway V3, `POST/GET /api/orders`, server-side quote recompute, 409 QUOTE_CHANGED) |
| 6 | 2026-10-30 | Smart KYC backend: DPDP consent (EN/HI notice, withdraw + purge), upload (magic bytes, temp file always deleted), ai-service `POST /extract` (OpenCV blur/skew + Gemini structured output), 17 checks C01–C17 with EN/HI messages + fix hints, KYC order statuses (Flyway V4), specimen harness + 47 golden fixtures (12/12 verdicts match). Live Gemini specimen run deferred (no API quota) |
| 7 | 2026-10-30 | KYC frontend: `/kyc/:orderId` (consent → upload → results, resumable, EN/HI), journey stepper, client-side photo resize, withdraw flow, `/track/:orderId` placeholder. Backend: fixed a Phase 6 withdrawal bug (withdraw + re-consent could give a false VERIFIED, and names survived in check messages), fixture cache answers WRONG_DOC_TYPE for a specimen in another slot, readable wrong-document hint |

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
- **frontend (Phase 5)**: Node 24.16 / npm 11.13. From `frontend/`: `npm install` (first time), `npm run dev` → http://localhost:5173 (`npm run dev -- --host` to test on a phone over LAN), `npm run test` (Vitest), `npm run build` (`tsc --noEmit && vite build` → `dist/`). The Vite dev server proxies `/api` → `http://localhost:8080`, so the browser never calls Spring cross-origin and never calls the ai-service.
  - Full stack for a demo: Spring (:8080) → ai-service (:8000) → `npm run dev` (:5173).
- **KYC (Phase 6)**: uploads go Spring → ai-service `/extract` (needs the ai-service running and a Gemini key). **Quota saver / offline demo:** start Spring with `KYC_FIXTURE_CACHE=true` (PowerShell `$env:KYC_FIXTURE_CACHE = "true"`): any upload byte-identical to a specimen image in `data/kyc_documents/` is answered from its golden fixture (no ai-service, no Gemini): the fixture in its own slot, `WRONG_DOC_TYPE` in another slot (the blurred K04 GST stays `UNREADABLE` in any slot). Other uploads still go to the ai-service. Off by default.
  - Calibration table (OpenCV only, no Gemini): `ai-service\.venv\Scripts\python ai-service\scripts\calibrate_quality.py`
  - Specimen harness: `POST /api/admin/kyc/run-specimens[?applicants=K02,K07]` with header `X-Internal-Token: <app.ai-service.token>`. With the cache OFF it calls Gemini once per readable image (46 for all 12) and overwrites the fixtures it extracts.
  - Regenerate the hand-transcribed fixtures: `ai-service\.venv\Scripts\python ai-service\scripts\write_kyc_fixtures.py`
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
orders/                   Orders from accepted quotes (server recompute), /api/orders, order events
kyc/                      KycService (consent → upload → submit → order status), KycController, KycStore/JdbcKycStore,
                          KycDocument, DocType, KycMasking, KycProperties (app.kyc.*)
kyc/consent/              ConsentNotices (EN/HI DPDP notice text, purpose codes), ConsentNotice record
kyc/extract/              DocumentExtractor (interface), AiServiceExtractor (multipart → ai-service /extract),
                          ExtractResult (ai-service + fixture format), FixtureCache (dev only), Hashing (sha256)
kyc/checks/               KycValidationService (C01–C17, pure), CheckCode, CheckResult, Text (en/hi), KycInput,
                          ValidationReport (overall rule), NameMatcher, AddressMatcher
kyc/harness/              SpecimenHarness + controller (/api/admin/kyc/run-specimens), ExpectedVerdicts
```

`frontend/src` (React 18 + TypeScript strict + Vite 8 + Tailwind 4, Phase 5):
```
main.tsx, App.tsx      BrowserRouter + routes inside ErrorBoundary + Layout
api/                   client.ts (fetch wrapper + typed errors), types.ts (mirrors Spring JSON), advisor.ts, quote.ts, orders.ts
i18n/                  index.ts (react-i18next init, language persisted), en.json, hi.json
lib/                   format.ts (formatInr ₹1,23,456, formatDate), mask.ts (maskGstin), storage.ts (safe localStorage)
hooks/                 useAdvisorSession.ts, useQuote.ts (+ pure helpers), useSessionSnapshot.ts
components/            Layout (+Header), LanguageToggle, ChatBubble, TypingIndicator, QuickReplies, RecommendationCard,
                       ProfileSummary, QuoteLineItem, AddOnToggle, IneligibleList, TotalsBar, ErrorBanner, ConfirmDialog, ErrorBoundary
pages/                 Landing, Advisor, Quote, Kyc (Phase 7, step router), TrackPlaceholder, NotFound
components/kyc/        (Phase 7) JourneyStepper, ConsentStep, UploadStep, UploadSlot, ResultsStep, StatusHeader, CheckItem
components/Toast.tsx   (Phase 7) short auto-dismissing confirmation
api/kyc.ts             (Phase 7) typed KYC calls; hooks/useKyc.ts (state); lib/image.ts (checks + resize); lib/kyc.ts (pure helpers)
test/                  setup.ts (jest-dom + i18n + cleanup), fixtures.ts (kirana quote/recommendation)
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
app/quality.py         (Phase 6) decode + magic bytes, blur (Laplacian variance @1240 px), skew (Hough, red watermark masked), deskew
app/extract.py         (Phase 6) per-docType JSON schemas, extraction prompt, GeminiExtractor (structured output, retries), clean_fields
app/kyc.py             (Phase 6) KycExtractor.run(): quality gate → UNREADABLE (no model call) | Gemini → OK | WRONG_DOC_TYPE
tests/                 pytest (fake LLM + respx-mocked Spring, no network; Phase 6: OpenCV over the 47 specimens + FakeVision)
evals/                 scenarios.json (15) + run_scenarios.py (live, manual) + results/
scripts/check_gemini.py   one-call connectivity check
scripts/calibrate_quality.py   (Phase 6) blur/skew table for all 47 specimen images
scripts/write_kyc_fixtures.py  (Phase 6) writes the 47 hand-transcribed golden fixtures (quality measured live)
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

**Phase 5 (Spring, `com.udyam.orders`)**

| Class | What it does |
|---|---|
| `OrderService` | `create(sessionId?, profile, items)` (`@Transactional`): recomputes the quote with `QuoteService` (client totals never used); any `removed[]` → `QuoteChangedException` (nothing saved); else inserts `customer_order` + `order_event(QUOTE_ACCEPTED)` and, with a session, sets it to `CHECKOUT_DONE` + `order_id`. `get(orderId)` → `OrderView` with GSTIN masked. `orderRef(n)` = `UD-` + reference date `yyMMdd` + `-` + n padded to 4 (e.g. `UD-260715-0042`) |
| `OrderController` | `POST /api/orders` (201), `GET /api/orders/{id}`; request record `CreateOrderRequest(sessionId, profile, items)` has no totals on purpose |
| `OrderStore` / `JdbcOrderStore` | Interface + Postgres impl (`nextval('order_ref_seq')`, inserts, events, `markSessionCheckedOut`) |
| `QuoteChangedException` + `OrderExceptionHandler` | 409 `{status, error, code: QUOTE_CHANGED, message, path, quote}` (`@Order(HIGHEST_PRECEDENCE)` so it wins over `GlobalExceptionHandler`) |
| `OrderStatus` | `QUOTE_ACCEPTED` (only status so far) |
| `AdvisorService` (changed) | A retry re-sending the last unanswered user message is not stored twice; a `CHECKOUT_DONE` session stays `CHECKOUT_DONE` (sent to the ai-service as `READY_FOR_CHECKOUT`) |

**Phase 6 (Spring, `com.udyam.kyc`)**

| Class | What it does |
|---|---|
| `KycService` | `notice(lang)`; `giveConsent(orderId, {noticeVersion, language, purposes})` (`@Transactional`; replaces any active consent, stores all 3 purposes, QUOTE_ACCEPTED/KYC_PENDING_CONSENT → KYC_IN_PROGRESS); `withdrawConsent(orderId)` (closes consent, **deletes the order's document and check rows** (Phase 7 fix), clears activation date, → KYC_PENDING_CONSENT); `upload(orderId, docType, bytes)` (consent → docType → empty/size/magic bytes → sha256 → fixture cache or temp file + extractor → save; a re-upload after a verdict → KYC_IN_PROGRESS); `submit(orderId)` (`@Transactional`; runs C01–C17, saves a run, sets order status + event, VERIFIED → `estimated_activation_date` = reference date + `quote.maxInstallDays`); `view(orderId)`. Every transition = `OrderStore.updateStatus` + `addEvent`. Logs order ref, doc type, status, ms, check codes only |
| `KycController` | thin; the 6 KYC endpoints below |
| `KycStore` / `JdbcKycStore` | consent rows, document upsert (`ON CONFLICT (order_id, doc_type)`), check runs (`run_id`), `latestChecks`, `deleteDocumentsAndChecks`, activation date |
| `KycDocument` | `(docType, status, detectedDocType, sha256, blurScore, skewDeg, deskewed, fields, model, uploadedAt)`; `from(slot, sha, ExtractResult)` keeps fields only when status OK; `usable()`, `text(field)` (blank → null) |
| `KycMasking.fields` | masks `gstin`, `udyamNumber`, `idNumber`, `consumerNumber` before any API response |
| `consent.ConsentNotices` | Purpose codes `KYC_VERIFICATION` (required), `SERVICE_INSTALLATION` (required), `WHATSAPP_UPDATES` (optional); EN + HI notice text; `version()` = `app.kyc.notice-version` |
| `extract.DocumentExtractor` | interface `extract(Path, DocType)`; throws `ExtractionUnavailableException` (→ 503) / `InvalidImageException` (→ 400) |
| `extract.AiServiceExtractor` | multipart POST `/extract` via the existing `aiServiceRestClient` (token header, 45 s read timeout) |
| `extract.FixtureCache` | dev only; indexes sha256 of `data/kyc_documents/*.png` → fixture JSON; a hit in its own slot returns the fixture, in another slot `WRONG_DOC_TYPE` (UNREADABLE stays UNREADABLE) |
| `checks.KycValidationService` | pure, deterministic; one method per check; input `KycInput(documents, hasOrderProfile, orderGstin, gstRequiredProducts)`; output `ValidationReport(overall, checks[17])` |
| `checks.CheckResult` | `(code, name, status PASS|WARN|FAIL|SKIPPED, severity BLOCKING|SOFT, fixable, messageEn, messageHi, fixHintEn, fixHintHi, evidence)`; WARN is always SOFT |
| `checks.ValidationReport.of` | any non-fixable FAIL → `REJECTED`; only fixable FAILs → `ACTION_REQUIRED`; no FAIL → `VERIFIED` (WARNs allowed) |
| `checks.NameMatcher` | normalize (lowercase, `&`→and, punctuation→space, `private limited`→`pvt ltd`, `limited liability partnership`→`llp`, `limited`→`ltd`), same token count, token equal or (both ≥ 6 letters and Levenshtein ≤ 1); `splitProprietor("X (Prop. Y)")` |
| `checks.AddressMatcher` | pincode (last 6-digit number) must be equal AND street-token overlap ≥ 0.70 (shared / tokens of the longer address); `state(address)` = last comma part before the pincode |
| `harness.SpecimenHarness` | per applicant: `OrderService.createBlank` (profile `null`, quote `{}` → C17 skipped) → consent → upload available files → (live: write fixtures) → submit → compare with `expected-verdicts.json` (verdict AND blocking-code set) |
| `orders` (changed) | `OrderStatus` + 5 KYC constants; `OrderStore.updateStatus`; `OrderService.createBlank(note)` |
| `registry` (changed) | new `UdyamRegistryLookup` interface (`JpaUdyamRegistryLookup` bean, `CsvUdyamRegistryLookup.fromCsv` for tests); `UdyamLookupService` now depends on it (same API). `Masking.keepLast3` for ID / consumer numbers |

**Phase 7 (frontend)**

| Name | What it does |
|---|---|
| `pages/Kyc` | Step router: `stepFor(view)` from server state, plus local `fixSlot` (a "Re-upload" button shows the upload step while the verdict still stands). Skeleton, not-found, retry on network error, withdraw dialog + toast |
| `hooks/useKyc` | Mirrors `GET /kyc`: `view`, `loadError`, `reload`, `consent`, `upload`, `submit`, `withdraw`. A `changes` counter drops any GET that started before a change was applied (a slow GET used to wipe finished uploads). 403 `CONSENT_REQUIRED` reloads, which lands on the consent step |
| `ConsentStep` | Fetches the notice per UI language; plain-language summary, one unticked checkbox per purpose, `<details>` full notice; button disabled until required purposes are ticked; sends all purposes as true/false |
| `UploadSlot` | One document: icon or thumbnail, hint, status (icon + text), masked summary, backend fix hint, Take photo (`capture="environment"`) + Choose photo (gallery / desktop), Retry with the same file on network / 503. Fields are read-only: the only fix is another photo. A failed attempt keeps the saved document |
| `UploadStep` | The 4 slots, privacy note, "From your last check" list, **Check my documents** (or **Check again** after a verdict; enabled when all 4 are read and none is busy), "I don't have all documents" + dialog naming what is missing |
| `ResultsStep` | `StatusHeader` + VERIFIED (date, Track) / ACTION_REQUIRED (fixable FAILs with hint + Re-upload/Upload buttons) / REJECTED (plain reason from `kyc.results.reason.*`, backend message + hint, "We also noticed", call back, no re-upload buttons); OCR note from C04 `evidence.correctedTo`; "Good to know" (WARN); `<details>` all checks |
| `CheckItem` | Icon AND status word, message in the UI language, optional hint and actions |
| `lib/image` | `targetSize` (long side ≤ 2000, pure), `prepareImage`: type check, reject > 30 MB input, decode, re-encode as JPEG 0.9 only when resizing (smaller photos stay byte-identical, so the fixture cache still matches), then the 5 MB limit. `ImageRejected(problem: type/size/decode)` |
| `lib/kyc` | `DOC_TYPES`, `stepFor`, `withDocument`, `withSubmitResult`, `unreadSlots`, `pickText`, `slotsForCheck`, `readSummary` |
| `api/client` (changed) | `FormData` bodies; typed `ConsentRequiredError` (403) and `ExtractionUnavailableError` (503) |
| `JourneyStepper` | Advisor → Quote → KYC → Track on the Quote and KYC pages; `aria-current="step"`, ticks + screen-reader text |

**Phase 5 (frontend)**

| Name | What it does |
|---|---|
| `api/client.ts` | `request<T>()`; maps 503 `AI_UNAVAILABLE` → `AiUnavailableError`, 409 `QUOTE_CHANGED` → `QuoteChangedError` (carries `quote`), connection failure / 502 / 504 → `NetworkError`, other → `ApiError(status, code, fieldErrors)`; `isAbort()` |
| `api/types.ts` | TS mirrors of all Spring JSON used by the UI (Quote, Recommendation, ResolvedProfile, SessionView, AdvisorReply, OrderCreated/OrderView, ApiErrorBody, Stage incl. `CHECKOUT_DONE`) |
| `hooks/useAdvisorSession` | Restores the session from localStorage (`udyam.sessionId`) via GET, else POST creates one (with `uiLanguage`); `send`, `retry` (re-sends the failed bubble, no duplicate), `startOver`, `dismissError`; tracks `cardAfter` = the reply that last changed the bundle (card rendered there) |
| `hooks/useQuote` | Quote editing: `setQuantity` / `removeItem` / `toggleAddOn` → POST `/api/quote` (300 ms debounce, AbortController drops stale requests, removed ids sent as `removedProductIds`); engine `removed[]` → `notices`; `replaceQuote` (used on 409). Pure helpers: `itemsFromQuote`, `withQuantity` (1..500), `withoutItem`, `withAddOn` (upgrade swap via `replaces`), `applyQuoteResponse` |
| `hooks/useSessionSnapshot` | One-time GET of the saved session for pages opened directly (`/quote`) |
| `RecommendationCard` | Bundle from JSON only: monthly (big), one-time, contract, lines (name × qty + `why`), conditional badge; CTA "View & customise quote" / prominent "Review quote & continue" at `READY_FOR_CHECKOUT`; "Order placed" at `CHECKOUT_DONE` |
| `ProfileSummary` | Chips: type · city (Tier n) · staff · sites · GSTIN masked + ✓ Active / ✕ Cancelled/Suspended / Not verified, or "GST: to verify" / "No GST" |
| `TotalsBar` | Sticky: monthly, one-time, contract, estimated activation date, price note, "Accept & continue to KYC" (disabled while repricing) |
| i18n | `react-i18next`, resources `en.json` / `hi.json` (all UI strings; product names stay English), language in localStorage `udyam.language`, sets `<html lang>`; Hindi uses Noto Sans Devanagari (Google Fonts in `index.html`) |

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
| POST | `/api/orders` | `{sessionId?: UUID, profile: BusinessProfile, items: [{productId, quantity 1..500}] (non-empty)}` (any totals in the body are ignored) → **201** `{orderId, orderRef, status: "QUOTE_ACCEPTED", quote}` | 400 `INVALID_REQUEST`/`UNKNOWN_PRODUCT`/`DUPLICATE_PRODUCT`, 404 (unknown session), **409 `QUOTE_CHANGED`** `{…, quote}` (recompute removed items; nothing saved) |
| GET | `/api/orders/{id}` | `{orderId, orderRef, status, createdAt, quote, profile (gstin masked), events[{status, note, createdAt}]}` | 400 (bad UUID), 404 |
| GET | `/api/kyc/consent-notice?lang=en\|hi` | `{version, language, title, dataFiduciary, purposes[{code, required, title, description}], dataCollected, retention, retentionDays, rights[], contact}` (default `en`) | 400 `INVALID_LANGUAGE` |
| POST | `/api/orders/{id}/kyc/consent` | body `{noticeVersion, language: en\|hi, purposes: {KYC_VERIFICATION: true, SERVICE_INSTALLATION: true, WHATSAPP_UPDATES: bool}}` → `KycView` | 400 `REQUIRED_CONSENT_MISSING` (+`errors` = missing codes), `NOTICE_VERSION_MISMATCH`, `INVALID_LANGUAGE`, `UNKNOWN_PURPOSE`, `INVALID_REQUEST`; 404 |
| POST | `/api/orders/{id}/kyc/consent/withdraw` | → `KycView` (consent inactive, `documents` and `checks` empty, `overall` null, status KYC_PENDING_CONSENT); idempotent | 404 |
| POST | `/api/orders/{id}/kyc/documents` | multipart `docType` (GST_CERTIFICATE \| UDYAM_CERTIFICATE \| SIGNATORY_ID \| ADDRESS_PROOF) + `file` (PNG/JPEG ≤ 5 MB) → `DocumentView {docType, status OK\|UNREADABLE\|WRONG_DOC_TYPE, detectedDocType, quality{blurScore, skewDeg, deskewed}, fields (masked; null unless OK), fixHintEn, fixHintHi (UNREADABLE/WRONG_DOC_TYPE only), model, fromFixtureCache, uploadedAt}`. Same slot again = replace | **403 `CONSENT_REQUIRED`**, 400 `INVALID_DOC_TYPE` / `EMPTY_FILE` / `INVALID_IMAGE` / `INVALID_REQUEST` (missing part), 413 `FILE_TOO_LARGE`, 415 `UNSUPPORTED_FILE_TYPE`, **503 `EXTRACTION_UNAVAILABLE`**, 404 |
| POST | `/api/orders/{id}/kyc/submit` | → `SubmitResult {orderId, orderRef, overall VERIFIED\|ACTION_REQUIRED\|REJECTED, orderStatus, blockingChecks[codes], warnings[codes], checks[17 CheckResult], estimatedActivationDate (VERIFIED only), runId}` | 403 `CONSENT_REQUIRED`, 404 |
| GET | `/api/orders/{id}/kyc` | `KycView {orderId, orderRef, orderStatus, kycStatus, consent{active, noticeVersion, language, purposes{code: bool}, givenAt}, documents[DocumentView], missingDocuments[DocType], overall (null before first submit), checks[latest run], estimatedActivationDate}` | 404 |
| POST | `/api/admin/kyc/run-specimens?applicants=K01,K03` | **dev only, `X-Internal-Token` required.** `{total, matched, fixtureCache, fixturesWritten, rows[{applicant, business, expected, actual, expectedBlocking, blockingChecks, warnings, match, orderRef, documents, error}]}` (no param = all 12) | 401 `INTERNAL_TOKEN_REQUIRED` |
| GET | `/api/admin/audit/mis-selling` | `{hasGstinFlag: Variant, registryActive: Variant}`; Variant = `{gstinSemantics, description, activatedChecked, bundlesWithViolations, misSoldPercent, totalViolations, violationsByProductAndReason[{productId, productName, reason, count}], sampleLeadIds[≤10]}` | - |

**ai-service (internal, called only by Spring)**: `GET /health` → `{status: "OK", model}`; `POST /chat` (header `X-Internal-Token`, else 401 `{detail: {code: UNAUTHORIZED}}`) body `{sessionId, messages[{role: user|assistant, content}], profile, uiLanguage, state{stage, recommendation, quote}}` → `{reply, detectedLanguage, profile, stage, recommendation, quote, toolTrace[{tool, args (GSTIN masked), ok, ms}], guard{passed, retries, fallbackUsed, violations[]}}`; 503 `LLM_UNAVAILABLE` when Gemini fails after retries; 422 on a malformed body. **Phase 6:** `GET /health` also returns `extractModel`; `POST /extract` (token required, multipart `file` + form `docType`) → `{status: OK|UNREADABLE|WRONG_DOC_TYPE, docType, detectedDocType, quality{blurScore, isBlurry, skewDeg, deskewed}, fields{…}|null, model, ms}`; 400 `{detail: {code: INVALID_IMAGE, message}}` (empty, > 5 MB, not PNG/JPEG by magic bytes, undecodable, unknown docType); 503 `LLM_UNAVAILABLE`. Blurry images return UNREADABLE **without** a model call; images are processed in memory only.

**KYC order statuses (Phase 6)**: `QUOTE_ACCEPTED` (no consent yet; the KYC view reports it as `kycStatus: KYC_PENDING_CONSENT`) → consent → `KYC_IN_PROGRESS` → submit → `KYC_VERIFIED` | `KYC_ACTION_REQUIRED` | `KYC_REJECTED`; re-upload after a verdict → `KYC_IN_PROGRESS`; withdraw → `KYC_PENDING_CONSENT`. Each transition writes an `order_event` (note e.g. `KYC ACTION_REQUIRED: C14`).

**KYC checks (Phase 6)** — SKIPPED when a document they need is missing/unreadable/wrong-slot (C01–C03 report that once):

| Code | Name | Severity / fixable | Rule |
|---|---|---|---|
| C01 | ALL_DOCUMENTS_PRESENT | blocking, fixable | all 4 slots uploaded (UNREADABLE counts as present) |
| C02 | IMAGES_READABLE | blocking, fixable | no UNREADABLE document (hint: retake in good light, flat on a table) |
| C03 | DOC_TYPES_MATCH | blocking, fixable | no WRONG_DOC_TYPE document |
| C04 | GSTIN_VALID | blocking, NOT fixable | format + checksum; else unique registry-backed OCR correction → PASS with evidence `readAs`/`correctedTo` (masked); else FAIL (possible tampering). Unreadable GSTIN field → FAIL fixable |
| C05 | GSTIN_IN_REGISTRY | blocking, not fixable | effective GSTIN found |
| C06 | GSTIN_ACTIVE | blocking, not fixable | registry status Active (Cancelled/Suspended fail) |
| C07 | GST_DOC_MATCHES_REGISTRY | blocking, not fixable | certificate legal name + trade name vs registry (NameMatcher) |
| C08 | UDYAM_NUMBER_VALID | blocking, fixable | present + format (whitespace normalized) |
| C09 | UDYAM_IN_REGISTRY | blocking, not fixable | number in registry + enterprise name matches |
| C10 | BUSINESS_NAME_CONSISTENT | blocking, not fixable | GST trade name, business part of GST legal name `(Prop. X)`, Udyam enterprise name, bill "billed to", ID "for business": every pair matches (needs ≥ 2) |
| C11 | PROPRIETOR_IS_SIGNATORY | blocking, not fixable / SOFT | Proprietorship: proprietor in legal name = signatory name; LLP / company → WARN "authorisation letter / board resolution may be required" |
| C12 | ADDRESS_CONSISTENT | blocking, not fixable | GST principal address vs bill supply address (AddressMatcher) |
| C13 | STATE_CONSISTENT | blocking, not fixable / SOFT | GSTIN state code vs GST address state (FAIL); vs Udyam state (WARN) |
| C14 | ADDRESS_PROOF_RECENT | blocking, fixable | 0 ≤ (reference date − bill date) ≤ 90 days; unreadable date → FAIL |
| C15 | ID_VALID | blocking, fixable | validTill ≥ reference date |
| C16 | UDYAM_DETAILS_SOFT | SOFT only | registration date / major activity vs registry → WARN (dataset noise) |
| C17 | ORDER_PROFILE_MATCH | blocking, fixable | only with an order profile: profile GSTIN = KYC GSTIN; GST-required products in the quote need an Active GSTIN (hint "Remove GST-only services or update your GST registration") |

**Stage values**: `COLLECTING`, `RECOMMENDED`, `CUSTOMISING`, `READY_FOR_CHECKOUT`, `OUT_OF_SCOPE` (from the ai-service), plus `CHECKOUT_DONE` (Phase 5, set by `POST /api/orders` with a sessionId; Spring-only, sticky).

**Frontend routes (Phase 7)**: `/kyc/:orderId` is now the real KYC page; `/track/:orderId` is a placeholder showing the order ref + "Tracking comes next"; `/track` (landing page link) stays, without an order. The rest as below.

**Frontend routes (Phase 5)**: `/` Landing · `/advisor` chat · `/quote` quote & checkout · `/kyc/:orderId` placeholder (shows order ref; Phase 7 replaces it) · `/track` placeholder · `*` NotFound.

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

Phase 6 added:

| Key | Default | Purpose |
|---|---|---|
| `spring.servlet.multipart.max-file-size / max-request-size` | 6MB / 7MB | Lets KycService return its own 413 for > 5 MB |
| `app.kyc.temp-dir` | `${KYC_TEMP_DIR:}` (empty → `{java.io.tmpdir}/udyam-kyc`) | Upload lives here only during the extraction call |
| `app.kyc.max-upload-bytes` | 5242880 | 5 MB (same as the ai-service) |
| `app.kyc.retention-days` | 2555 | Shown in the consent notice ("as required by law"); no deletion job yet |
| `app.kyc.notice-version` | `2026-07-v1` | Consent must quote the current version |
| `app.kyc.fixture-cache` | `${KYC_FIXTURE_CACHE:false}` | DEV ONLY specimen fixture cache |
| `app.kyc.fixture-dir` / `specimen-dir` / `expected-verdicts` | `src/test/resources/kyc/fixtures` / `../data/kyc_documents` / `src/test/resources/kyc/expected-verdicts.json` | Harness + cache (relative to `backend/`) |
| ai-service `Settings` | `gemini_extract_model=gemini-2.5-flash-lite`, `blur_threshold=100.0`, `skew_threshold_deg=1.5`, `max_upload_bytes=5 MB` | `.env` keys `GEMINI_EXTRACT_MODEL`, `BLUR_THRESHOLD`, `SKEW_THRESHOLD_DEG`, `MAX_UPLOAD_BYTES` |

Quantity rules: per-site units = sites; P19 = sites; P16 = officeUsers else prof ceil(0.7×emp), mfg ceil(0.15×emp), others emp; P20 = officeUsers else prof ceil(0.5×emp), mfg ceil(0.1×emp), others 2; P07/P08 = mobileLines else micro 2, prof max(2, ceil(emp/5)), multi max(2, ceil(0.6×sites)), mfg 4; P23 = cameras else micro 2, multi 2×sites, mfg 4, others 2; P24 = machines else 5; P25 = vehicles else 2; P12 = max(2, ceil(emp/5)); everything else 1.

## 7. Database (Neon PostgreSQL 18.6)

Flyway versions applied: **V1** `V1__init_reference_data.sql` (Phases 2 and 3 added no migrations; Phase 3 templates live in YAML, not the DB), **V2** `V2__advisor_sessions.sql` (Phase 4), **V3** `V3__orders.sql` (Phase 5), **V4** `V4__kyc.sql` (Phase 6, applied to Neon 2026-10-30).

| Table (V2) | Key columns / types |
|---|---|
| `advisor_session` | `id UUID` PK, `created_at`/`updated_at TIMESTAMPTZ`, `ui_language`, `detected_language`, `stage` (CHECK: the 5 stage values, default COLLECTING), `profile_json JSONB NOT NULL DEFAULT '{}'`, `last_recommendation_json JSONB`, `last_quote_json JSONB`; index on `stage` |
| `advisor_message` | `id BIGSERIAL` PK, `session_id UUID` FK → advisor_session ON DELETE CASCADE, `role` (CHECK user/assistant), `content TEXT`, `tool_trace_json JSONB`, `guard_json JSONB`, `created_at`; index `(session_id, id)` |

**V3** `V3__orders.sql` (Phase 5):

| Table / change | Key columns / types |
|---|---|
| `customer_order` | `id UUID` PK, `order_ref VARCHAR(24) UNIQUE` (from sequence `order_ref_seq`), `session_id UUID` FK → advisor_session (ON DELETE SET NULL), `profile_json JSONB`, `quote_json JSONB` (server-recomputed snapshot), `monthly_total_inr`, `one_time_total_inr`, `contract_months INT`, `status VARCHAR(40)` default `QUOTE_ACCEPTED`, `created_at`/`updated_at`; indexes on `session_id`, `status` |
| `order_event` | `id BIGSERIAL` PK, `order_id UUID` FK → customer_order ON DELETE CASCADE, `status`, `note TEXT`, `created_at`; index `(order_id, id)` |
| `advisor_session` | stage CHECK now also allows `CHECKOUT_DONE`; new column `order_id UUID` FK → customer_order |

**V4** `V4__kyc.sql` (Phase 6):

| Table / change | Key columns / types |
|---|---|
| `customer_order` | new column `estimated_activation_date DATE` (set when KYC is VERIFIED) |
| `kyc_consent` | `id BIGSERIAL`, `order_id` FK (CASCADE), `purpose VARCHAR(40)`, `granted BOOLEAN`, `notice_version`, `language`, `created_at`, `withdrawn_at` (NULL = active); partial index on active rows |
| `kyc_document` | `id`, `order_id` FK, `doc_type` (CHECK 4 types), `status` (CHECK OK/UNREADABLE/WRONG_DOC_TYPE), `detected_doc_type`, `file_sha256 CHAR(64)`, `blur_score`, `skew_deg`, `deskewed`, `extracted_json JSONB` (full values; row deleted on withdrawal), `model`, `uploaded_at`; **UNIQUE (order_id, doc_type)**. No image column |
| `kyc_check` | `id`, `order_id` FK, `run_id UUID`, `check_code`, `check_name`, `status` (CHECK PASS/WARN/FAIL/SKIPPED), `severity` (CHECK BLOCKING/SOFT), `fixable`, `message_en/hi`, `fix_hint_en/hi`, `evidence_json JSONB` (identifiers masked; row deleted on withdrawal), `created_at`; index `(order_id, id)` |

Check after a withdrawal: `SELECT (SELECT count(*) FROM kyc_document WHERE order_id = '<id>') AS documents, (SELECT count(*) FROM kyc_check WHERE order_id = '<id>') AS checks;` (expect 0, 0). `kyc_consent` (with `withdrawn_at`) and `order_event` stay as the audit trail.

Back-office query for "leads the advisor could not close": `SELECT id, stage, profile_json, updated_at FROM advisor_session WHERE stage NOT IN ('READY_FOR_CHECKOUT', 'CHECKOUT_DONE') ORDER BY updated_at DESC;`

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
- **Phase 5 live checks (2026-10-30, user-confirmed)**: all 12 checklist items passed. First orders in Neon: `UD-260715-0001` (curl, client claimed ₹1 → saved ₹1,597), `UD-260715-0002` (UI). Tamper (P01 + P03 in tier 2) → 409 with P03 `TIER_NOT_AVAILABLE`. Quote UI math verified: CCTV on ₹1,995 / off ₹1,597; P07 ×3 ₹1,896; + Static IP ₹1,797; remove fibre → ₹598 + notice "Static IP removed because Biz Fibre 100 was removed".
- **Phase 6 image calibration** (all 47 specimens, OpenCV only; `scripts/calibrate_quality.py`): blur score (Laplacian variance at width 1240) K04 GST certificate **1.0**; every other image **732.2–1282.9** (lowest: K02 GST 732.2, signatory IDs 785–849, Udyam 937–1005, bills 952–1023, GST 1178–1283) → **threshold 100** (≥ 7× margin both sides). Skew (Hough on Canny edges, red watermark masked) K02 GST **6.96°** (0.00° after deskew); all 46 others **0.00°** → **threshold 1.5°**.
- **Phase 6 specimen verdicts** (golden fixtures, unit tests + live harness with the fixture cache, 2026-10-30): **12/12 match** — K01 REJECTED [C06, C08] · K02 VERIFIED (C04 corrected `19E0G…` → `19EOG…`) · K03 VERIFIED · K04 ACTION_REQUIRED [C02] · K05 ACTION_REQUIRED [C01] (warn C11) · K06 REJECTED [C04] · K07 REJECTED [C07, C10] · K08 VERIFIED (C04 corrected) · K09 VERIFIED · K10 VERIFIED (warn C11 LLP) · K11 ACTION_REQUIRED [C14, 257 days] (warn C11 Pvt Ltd) · K12 VERIFIED. C16 warns for 11 of 12 (every applicant with a registry Udyam: certificate dates/activities differ from the registry).
- **Specimen facts found while transcribing:** K01 Udyam number printed blank; K02 and K08 GSTINs printed with digit 0 for letter O; K06 GSTIN printed `08YKZXS8189L1Z0` (tampered); K07 legal name `Neha Banerjee (Prop. Shri Physio Clinic)` while trade name / Udyam / bill / ID / registry say Neelgiri; K10 GSTIN printed correctly with J (`27QHJXK0658U1ZL`); K11 bill dated 2025-10-31; all GST addresses equal the bill supply addresses and the registry addresses; every printed Udyam number exists in the registry.
- **Reference scenarios (MIN pricing):** kirana tier 2 no GSTIN → P01 + P07×2 = ₹1,597/mo; dental tier 1 14 emp GSTIN → P02 + P16×10 + P08×3 + P20×7 + P17 = ₹5,738/mo; restaurant chain 8 sites 60 emp GSTIN → ₹46,955/mo + ₹1,00,800 one-time, 24 months, activation 2026-07-29; textile 85 emp GSTIN → P05 primary ₹12,912/mo; factory tier 2 8 emp → P05 (emp) → P03 (tier) → P02.

## 9. Testing

All tests are pure unit tests (no Spring context, no DB, no network). **JUnit total: 218** (41 after Phase 2, +76 in Phase 3, +9 in Phase 4, +9 in Phase 5, +77 in Phase 6, +6 in Phase 7). **pytest (ai-service): 146** (42 + 104 in Phase 6). **Vitest (frontend): 74** (18 + 56 in Phase 7).

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
| `advisor.AdvisorServiceTest` (Phase 5 additions) | +2 (=9) | retry does not duplicate the user message; CHECKOUT_DONE stays sticky |
| `orders.OrderServiceTest` | 7 | recompute ignores client totals (tampered JSON), ineligible → QUOTE_CHANGED (nothing saved), GST-only without GSTIN rejected, ref format/uniqueness `UD-260715-0042/0043`, event row + session checked out, unknown session, GET masks GSTIN (uses `RulesFixture.QUOTES` + `InMemoryOrderStore`) |
| `kyc.checks.SpecimenVerdictTest` | 22 | 12 parameterized: verdict + exact blocking codes per applicant from `expected-verdicts.json`; K02/K08 OCR correction evidence (masked), K06 no correction + C05–C07 skipped, K04 GST-dependent checks skipped and no fields, K01, K07 Shri vs Neelgiri, LLP/Pvt Ltd soft warning, K11 257 days + bilingual hint, every check has EN + HI and FAILs have hints, evidence never holds a full GSTIN/Udyam |
| `kyc.checks.MatcherTest` | 23 | 14 name pairs (Shri ≠ Neelgiri, LLP casing, `&`/and, Pvt Ltd forms, OCR typos ≥ 6 letters only), proprietor split, address: identical / formatting / pincode differs / street differs / no pincode / state parsing |
| `kyc.checks.DateAndOrderChecksTest` | 17 | bill age 0/89/90 PASS, 91/257/future FAIL; unreadable date; ID validTill −1/0/+1; proprietor ≠ signatory; moved address; Udyam state soft; WRONG_DOC_TYPE → C03 only; C17 GSTIN mismatch, GST-only products with Cancelled GSTIN, skipped without profile |
| `kyc.KycServiceTest` | 14 | 403 before consent (no extractor call), required purposes / notice version / language / unknown purpose, consent rows + status + events, EN/HI notice, magic bytes / empty / > 5 MB / bad docType, temp file exists during the call and is deleted after, **temp file deleted when the extractor fails** (503 and unexpected error), UNREADABLE stores no fields + hint, fixture cache (no extractor call; wrong slot not served), K03 VERIFIED + events, activation date from the quote, K11 re-upload reopens, K06 REJECTED, withdraw purges + 403 after |
| `common.privacy.MaskingTest` (Phase 6) | +1 (=4) | `keepLast3` for ID / consumer numbers |
| `kyc.KycServiceTest` (Phase 7 additions) | +5 (=19) | **regressions:** withdraw + new consent + submit can't verify without documents (K07 stays out of VERIFIED, C01 only); no names left in check messages after a withdrawal. Fixture cache: specimen in another slot → WRONG_DOC_TYPE with a readable EN/HI hint, K04 stays UNREADABLE in any slot, non-specimens go to the extractor |
| `kyc.checks.DateAndOrderChecksTest` (Phase 7) | +1 (=18) | an OK document without fields counts as missing (old purged rows in Neon) |

Vitest (`frontend/src`, run `npm run test`): `lib/format.test.ts` (6: ₹1,23,456, dates, GSTIN mask), `hooks/useQuote.test.ts` (6: add-on payload, OFF, upgrade swap, clamp, remove, removed[] → notice), `components/RecommendationCard.test.tsx` (3: totals/items from JSON, customised JSON, CTA), `api/client.test.ts` (5: success, 503 → AiUnavailableError, 409 → QuoteChangedError with quote, 400 field errors, NetworkError). **Phase 7:** `components/kyc/ConsentStep.test.tsx` (6: nothing pre-ticked, disabled until required ticked, payload, optional true, labels + full notice, save error), `components/kyc/UploadSlot.test.tsx` (10: rejects non-image / > 5 MB, uploads, masked summary, UNREADABLE hint, Hindi hint, WRONG_DOC_TYPE, blank Udyam flagged, network retry keeps the saved document, busy reader), `components/kyc/ResultsStep.test.tsx` (7: K03, K02 OCR note, K11 re-upload, K06 no re-upload + call back, K01-like, Hindi, warnings + text statuses), `hooks/useKyc.test.ts` (7: concurrent uploads, **stale GET regression**, lost consent, failed upload, submit error, 404), `lib/kyc.test.ts` (13), `lib/image.test.ts` (8: target sizes, types, 5 MB, byte-identical), `api/kyc.test.ts` (5: 403 typed, other 403s, 503 typed, multipart, JSON consent). Fixtures in `src/test/fixtures/`: real backend JSON captured with the fixture cache (`kyc-k02/k03/k06/k11.json`, `doc-unreadable.json`, `doc-wrong-type.json`, `notice-en.json`).

pytest (`ai-service/tests`, run `.venv\Scripts\python -m pytest -q`): `test_tools.py` (12: endpoint/JSON per tool, save_profile validation, auto lookup + auto recommend), `test_advisor_loop.py` (10: one-shot = 2 model calls, prompt state, loop limit 6, Spring 500/timeout → friendly reply + stage unchanged, stages, language), `test_guard.py` (13: amount formats incl. ₹1,23,456 and Devanagari, invented ₹499, products/reasons, retry, template fallback), `test_api.py` (7: health, 401 missing/wrong/empty token, camelCase contract, 503, 422). Fixtures in `tests/conftest.py` (`FakeLlm`, `kirana_recommendation()`, respx `spring`). **Phase 6** `test_kyc_extract.py` (104): blur + skew calibration parameterized over all 47 specimens (only K04 GST blurry, only K02 GST tilted, < 0.5° after deskew), magic bytes / size / undecodable, UNREADABLE returns no fields and makes no model call, deskew before extraction, WRONG_DOC_TYPE, schema contract per docType (nullable, all required, detectedDocType enum), `clean_fields`, `/extract` 401 / contract / 400 (uses `FakeVision`, no Gemini).

KYC test data: `backend/src/test/resources/kyc/fixtures/*.json` (47 golden fixtures, **hand-transcribed from the images** by `write_kyc_fixtures.py` because no Gemini quota was available; `"model": "hand-transcribed"`; a live `run-specimens` overwrites them with real extractions) and `kyc/expected-verdicts.json` (answer key: verdict + blocking codes). Helpers: `kyc.KycFixtures` (fixtures, specimen bytes, hand-wired `KycValidationService` on CSV registries), `kyc.InMemoryKycStore`, `orders.InMemoryOrderStore` (now public).

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
- Phase 5: order refs use the **reference date** (2026-07-15) for the date part (`UD-260715-NNNN`), consistent with "treat 2026-07-15 as today"; the number is a global sequence (no daily reset).
- Phase 5: closed sessions are marked with BOTH `stage = CHECKOUT_DONE` and `advisor_session.order_id`.
- Phase 5: React Router 6.30 (not v7) and React 18.3.1 pinned; `@testing-library/jest-dom` pinned to 6.9.1 (6.10.0 is a broken release).
- Phase 5: product descriptions on the Quote page come from `GET /api/products` (the quote JSON has none); prices never come from there.
- Phase 5: add-ons the user switches on stay in the "Add-ons" section (as ON toggles) instead of moving to "Included"; recommended add-ons are listed first, the rest behind "Show N more".
- Phase 5: edits on the Quote page call `/api/quote` directly and are NOT written back to the advisor session (the order is created from the page's items).
- Phase 6: order creation still sets `QUOTE_ACCEPTED` (Phase 5 unchanged); "consent not given" is reported as `kycStatus: KYC_PENDING_CONSENT` by `GET /api/orders/{id}/kyc`, and the order status itself becomes `KYC_PENDING_CONSENT` only after a withdrawal.
- Phase 6: checks whose document is missing/unreadable are `SKIPPED` (the prompt listed PASS/WARN/FAIL only), so one bad photo gives one fix (C01/C02/C03) instead of a cascade of failures.
- Phase 6: extraction model `gemini-2.5-flash-lite` (`GEMINI_EXTRACT_MODEL`) to save quota; the advisor stays on `gemini-2.5-flash`.
- Phase 6: dev-only fixture cache (`app.kyc.fixture-cache`) and `?applicants=` on the harness, to run the specimen set without (or with fewer) Gemini calls.
- Phase 6: golden fixtures are hand-transcribed (not Gemini output) until a live run; STOP 3 (live specimen run + fixture review) is deferred until an API key is available.
- Phase 6: the harness compares the verdict AND the exact set of blocking codes; `expected-verdicts.json` adds the blocking codes to the prompt's table (K01 C06+C08, K04 C02, K05 C01, K06 C04, K07 C07+C10, K11 C14).
- Phase 6: specimen-harness orders have `profile_json = null` and `quote_json = {}` (created by `OrderService.createBlank`), so C17 is skipped and no activation date is set.
- Phase 6: a C11 mismatch for a proprietorship is non-fixable (identity mismatch → REJECTED); C04 with an unreadable GSTIN field is fixable (re-upload).
- Phase 6: upload errors use specific statuses: 413 `FILE_TOO_LARGE`, 415 `UNSUPPORTED_FILE_TYPE`.
- Phase 7: **withdrawal deletes `kyc_document` and `kyc_check` rows** instead of nulling `extracted_json` / `evidence_json` as the Phase 6 prompt said. Nulling let a later submit pass with no data, and check messages quote names.
- Phase 7: the 5 MB limit applies after resizing (only inputs > 30 MB are refused up front), so normal camera photos are shrunk rather than rejected.
- Phase 7: an extra "Choose photo" input next to "Take photo": `capture="environment"` alone opens only the camera on Android.
- Phase 7: upload progress is a spinner + "Reading your document…", not a percentage (most of the wait is reading, not sending).
- Phase 7: the "Re-upload" flow goes to the upload step and back through "Check again" rather than re-uploading inside the results page.
- Phase 7: `/track` is kept next to the new `/track/:orderId`.

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
- Phase 5 UI: the add-on list can include an alternative connectivity plan (e.g. Biz Fibre 300 next to Biz Fibre 100) because the engine marks it eligible; harmless but can look odd.
- Phase 5 UI: Quote-page edits are not reflected in the chat or the stored session quote; reopening `/quote` after a reload starts again from the advisor's last quote.
- Phase 5 UI: no order lookup by reference yet (`/track` is a placeholder); the order id lives only in the `/kyc/:orderId` URL (and `advisor_session.order_id`).
- Phase 5 UI: Lighthouse accessibility was run by the user (≥ 90 required); no automated a11y test in CI.
- Phase 5: `.claude/launch.json` (dev-server config for the Claude desktop preview) is committed; harmless for others.
- Phase 6: **live Gemini extraction is untested end to end** (STOP 3 deferred). The 12/12 result proves the checks on hand-transcribed fields; it does not prove Gemini reads the images the same way (risks: K01 blank Udyam number must come back null, K02/K08 `0` vs `O`, K06 `8`, K07 "Shri", wrapped addresses). Run `run-specimens?applicants=K02,K07,K11` first (~12 calls), review those fixtures, then the rest.
- Phase 6: no retention job: `app.kyc.retention-days` is only shown in the notice; extracted fields stay until withdrawal or order deletion.
- Phase 6: `/api/admin/kyc/run-specimens` is protected only by the shared internal token and creates real orders in Neon (refs `UD-260715-…`, event note "KYC specimen harness: Kxx"); don't run it against a production DB.
- Phase 6: activation date = reference date + max install days (not shifted by the KYC completion date, since "today" is fixed at 2026-07-15).
- Phase 6: no Udyam OCR correction; a misread Udyam digit fails C09 (non-fixable). Not seen in the specimens.
- Phase 6: the fixture cache matches by exact sha256; a re-saved/compressed copy of a specimen goes to Gemini.
- Phase 6: when the order already has a quote, KYC does not recompute it if C17 fails; the customer must change the order (Phase 7/8 decide the UX).
- Phase 7: a C17 failure links back to `/quote`, but changing the quote there creates a **new order**; the old one stays in `KYC_ACTION_REQUIRED`.
- Phase 7: orders withdrawn before the Phase 7 fix (test data only, all synthetic) still have rows in Neon with null JSON; C01 treats an OK row without fields as missing, so they can't verify.
- Phase 7: thumbnails don't survive a reload (photos aren't kept); the slot shows its icon and the read summary.
- Phase 7: a REJECTED order has no re-upload path in the UI; withdrawing consent and starting again is possible, and the documents are simply re-checked. Backend upload itself isn't blocked after a rejection.
- Phase 7: the stepper on a real Quote page and the full advisor → quote start weren't exercised in the browser by me (no Gemini quota); the KYC screens were tested from orders created by API. "Request a call back" is only a toast (prototype).
- Phase 7: the 503 Retry state is covered by unit tests, not a live run.

### KYC consent + retention design (for the privacy note)
- **Notice before collection** (`GET /api/kyc/consent-notice?lang=en|hi`, version `2026-07-v1`): data fiduciary (Accel Telecom), the 3 purposes, exactly which fields are collected, retention, rights, grievance contact.
- **Purpose limitation**: `KYC_VERIFICATION` and `SERVICE_INSTALLATION` are required (KYC cannot start without them → 400); `WHATSAPP_UPDATES` is optional and stored as granted/declined. One row per purpose in `kyc_consent` with notice version, language and timestamp (audit trail); a new consent closes the previous rows.
- **No processing without consent**: upload and submit return 403 `CONSENT_REQUIRED`.
- **Data minimisation**: raw images are never stored. Spring writes the upload to a temp file only for the extraction call and deletes it in a `finally` block (tested, including when the ai-service fails); the ai-service processes images in memory. Only the sha256, quality metrics and the schema fields are kept; blurry images are rejected before any model call, so no fields are guessed.
- **Masking**: API responses mask GSTIN (`32TTN*****1ZW`), Udyam (`UDYAM-KL-00-****801`), ID and consumer numbers (last 3 visible); check evidence is stored masked. Full values exist only in `kyc_document.extracted_json`, which the checks need.
- **Logs**: order ref, doc type, status, blur/skew, ms and check codes only; never names, numbers, addresses or extracted values (verified by a log scan in the live checklist).
- **Withdrawal** (corrected in Phase 7): `POST …/kyc/consent/withdraw` closes consent, **deletes the order's `kyc_document` and `kyc_check` rows** (fields, hashes, quality metrics, check messages and evidence), clears the activation date, sets the order to `KYC_PENDING_CONSENT` and logs an event. Kept as the audit trail: `kyc_consent` rows and `order_event` notes, which hold no extracted values. The UI confirms with a modal and a toast.
- **Client side** (Phase 7): identifiers are shown only as the server masked them; photos above 2000 px are re-encoded in the browser before upload (which also drops their EXIF data, such as location; smaller photos are sent unchanged); nothing about the documents is written to browser storage.
- **Retention**: extracted fields are kept "for the period required by telecom rules, as required by law" (`app.kyc.retention-days`, 2555 by default); deletion job TODO.
- **Third party**: document images are sent to the Google Gemini API for extraction (same free vs paid tier caveat as the advisor above).

### Privacy (for the 1-page privacy note)
- Conversation text (the full history of each advisor session) and the collected profile (business type, city, staff, GSTIN, needs) are sent to the **Google Gemini API** on every turn. Engine JSON returned by tools is also sent back to Gemini.
- Google's Gemini API terms (to the best of our knowledge; not re-checked live in this session, verify at https://ai.google.dev/gemini-api/terms before submission): on the **free (unpaid) tier**, Google may use prompts and responses to improve its products and human reviewers may read them, so users should not submit sensitive or personal information. On the **paid tier**, prompts and responses are not used to improve products and are only kept for a limited period for abuse monitoring. → For production, use a paid key / Vertex AI and say so in the privacy note.
- Not logged in plain text: message text, names, full GSTINs (ai-service logs session id prefix, tool names, stage, guard result, latency; Spring logs ids/stages/timings and masks GSTINs in paths and tool traces).
- Stored in Neon: full conversation text (`advisor_message.content`) and profile JSON. No retention job yet (TODO: define and implement a retention period).

## 12. Notes for the next phase (Phase 8: tracker)

**Where**: replace `frontend/src/pages/TrackPlaceholder.tsx`. Two routes exist: **`/track/:orderId`** (from "Track my order" on the VERIFIED screen) and `/track` (landing page CTA, no order: needs a lookup by order ref, which doesn't exist yet).

**What the tracker page receives**
- `orderId` from the URL. `GET /api/orders/{orderId}` → `{orderId, orderRef, status, createdAt, quote (lines, totals, maxInstallDays, estimatedActivationDate), profile (GSTIN masked), events[{status, note, createdAt}]}`.
- `GET /api/orders/{orderId}/kyc` → `kycStatus`, `overall`, `estimatedActivationDate` (set only when VERIFIED: reference date + `quote.maxInstallDays`; not in `OrderView` yet, add it if the tracker should not call `/kyc`).

**Order statuses so far** (`customer_order.status`, free text, one `order_event` per transition): `QUOTE_ACCEPTED` → `KYC_IN_PROGRESS` → `KYC_VERIFIED` | `KYC_ACTION_REQUIRED` | `KYC_REJECTED`; a re-upload after a verdict → `KYC_IN_PROGRESS`; withdraw → `KYC_PENDING_CONSENT`. Event notes look like `KYC ACTION_REQUIRED: C14`. Only `KYC_VERIFIED` should start the tracker's install steps; for other statuses link back to `/kyc/:orderId`.

**Watch out when adding tracker statuses**
- `KycService.view` reports any status not starting with `KYC_` as `kycStatus: KYC_PENDING_CONSENT`, and `lib/kyc.stepFor` shows the upload step for anything that isn't a verdict. Post-KYC statuses (e.g. `INSTALL_SCHEDULED`) would send a verified order back to the upload screen, so update both.
- Don't allow new uploads once installation has started, or a re-upload will reopen the KYC.
- Reuse `JourneyStepper current="track"`, `formatDate`, `Toast`, `ErrorBanner`, and the `hooks/useKyc` pattern of dropping stale GETs if the page polls. "Today" stays `app.reference-date`; don't use `new Date()` for tracker dates.
- Testing without Gemini: create an order by curl, run K03 through `/kyc/:orderId` with `KYC_FIXTURE_CACHE=true`, then open `/track/:orderId`.

**Still deferred from Phase 6**: the live Gemini specimen run (STOP 3): `run-specimens?applicants=K02,K07,K11` first (~12 calls). Advisor evals 4–15 also still need quota.

### i18n keys added in Phase 7
`journey.*`, `kyc.consent.*`, `kyc.doc.<DocType>.{title,short,hint}`, `kyc.upload.*` (`status`, `summary`, `errors`), `kyc.results.*` (`status`, `reason.<code>`, `almostThere_one/_other`), `kyc.withdraw.*` in `en.json` + `hi.json`. Removed: `kyc.monthly`, `kyc.comingNext`, `kyc.body`. Changed: `kyc.title`, `kyc.orderRef`, `track.body`. Check messages and fix hints come from the backend in both languages, not from i18n.

### KYC frontend contract (Phase 7, implemented)

**Where**: `frontend/src/pages/Kyc.tsx` on the route **`/kyc/:orderId`** (the Quote page already navigates there after `POST /api/orders`). The browser talks only to Spring (Vite proxies `/api`); never to the ai-service.

**Screen flow**
1. Load `GET /api/orders/{orderId}/kyc` → `KycView`. Drive the page from `kycStatus` + `consent.active`:
   - `consent.active == false` → **Consent step**: `GET /api/kyc/consent-notice?lang=<ui language>`; render title, the 3 purposes (checkbox each; required ones marked and must be ticked; WhatsApp optional, unticked by default), `dataCollected`, `retention`, `rights[]`, `contact`. Submit `POST /api/orders/{id}/kyc/consent` `{noticeVersion: notice.version, language, purposes: {KYC_VERIFICATION, SERVICE_INSTALLATION, WHATSAPP_UPDATES}}` → returns the new `KycView`. Disable the button until both required boxes are ticked (server also returns 400 `REQUIRED_CONSENT_MISSING` with the missing codes in `errors`).
   - otherwise → **Documents step** (4 slots).
2. **Upload slot** per `DocType` (`GST_CERTIFICATE`, `UDYAM_CERTIFICATE`, `SIGNATORY_ID`, `ADDRESS_PROOF`): `<input type="file" accept="image/png,image/jpeg" capture="environment">`; check ≤ 5 MB client-side; `POST /api/orders/{id}/kyc/documents` as `FormData` (`docType`, `file`) → `DocumentView`. Show per slot:
   - `OK` → green tick + a short preview of masked `fields` (e.g. GSTIN `27XUI*****1ZP`, name, date); `quality.deskewed` → small "auto-straightened" note.
   - `UNREADABLE` / `WRONG_DOC_TYPE` → red + `fixHintEn`/`fixHintHi` + "Retake / upload again" (same slot replaces the document).
   - Spinner while uploading (live Gemini ~3–10 s per image; fixture cache is instant). Errors: 403 `CONSENT_REQUIRED` → back to the consent step; 413 `FILE_TOO_LARGE`, 415 `UNSUPPORTED_FILE_TYPE`, 400 `INVALID_IMAGE`/`EMPTY_FILE` → inline slot message; 503 `EXTRACTION_UNAVAILABLE` → "Document reading is busy, try again" + retry.
   - `missingDocuments[]` lists empty slots (K05-style applicants may have no Udyam certificate: allow submit anyway; C01 then explains).
3. **Submit**: `POST /api/orders/{id}/kyc/submit` → `SubmitResult`. Then (or on reload, from `KycView.overall` + `checks`) show the **result**:
   - `VERIFIED` → success + `estimatedActivationDate` (formatDate) + WARN items as "notes" (e.g. C11 "authorisation letter may be required", C16 registry differences); link to `/track` (Phase 8).
   - `ACTION_REQUIRED` → list FAIL checks with `fixable: true`: message + fix hint + a "Fix" button that scrolls to / reopens the right slot (mapping below); re-upload sets the order back to `KYC_IN_PROGRESS`; then submit again.
   - `REJECTED` → list the non-fixable FAILs with their message and hint (contact support); no re-upload CTA for those.
   - Order: FAIL (red) → WARN (amber) → PASS (collapsed "17 checks") → SKIPPED (grey, "checked after you fix the items above"; `messageEn` explains what is missing).
4. **Withdraw consent** link (ConfirmDialog): `POST /api/orders/{id}/kyc/consent/withdraw` → `KycView` with `consent.active=false` and no documents or checks; return to the consent step and say the documents and details were deleted.

**Language**: every `CheckResult` has `messageEn`/`messageHi` and `fixHintEn`/`fixHintHi` (null on PASS/SKIPPED); `DocumentView` has `fixHintEn`/`fixHintHi`; the notice is fetched per language. Pick by the UI language; no translation needed in the frontend. Check codes/names are for logic only, not display.

**Check → slot mapping for "Fix" buttons**: C01 → `evidence.missing[]` (DocType names); C02 → `evidence.unreadable[]`; C03 → keys of `evidence.detected`; C04–C07, C13 → GST_CERTIFICATE; C08, C09, C16 → UDYAM_CERTIFICATE; C11, C15 → SIGNATORY_ID; C12, C14 → ADDRESS_PROOF; C10 → `evidence.mismatch[]` keys (`gstTradeName`/`gstLegalName` → GST, `udyamEnterpriseName` → UDYAM, `billBilledTo` → ADDRESS_PROOF, `idForBusiness` → SIGNATORY_ID); C17 → back to `/quote` ("Remove GST-only services or update your GST registration").

**Types to add to `api/types.ts`** (mirror section 5): `ConsentNotice`, `ConsentPurpose`, `ConsentRequest`, `KycView`, `ConsentView`, `DocumentView`, `KycQuality`, `CheckResult` (`status: "PASS"|"WARN"|"FAIL"|"SKIPPED"`, `severity: "BLOCKING"|"SOFT"`), `SubmitResult`, `DocType`, `KycOverall = "VERIFIED"|"ACTION_REQUIRED"|"REJECTED"`. `api/client.ts` needs a `FormData` request variant (no JSON content type) and should surface 403 `CONSENT_REQUIRED` / 503 `EXTRACTION_UNAVAILABLE` as typed errors.

**Developing without Gemini**: run Spring with `KYC_FIXTURE_CACHE=true` and upload the files from `data/kyc_documents/` (each in its own slot): K03 → VERIFIED, K04 GST → UNREADABLE, K11 → ACTION_REQUIRED (C14), K06 → REJECTED (C04), K05 (3 files) → ACTION_REQUIRED (C01), K07 → REJECTED (name mismatch). No ai-service needed for those uploads.

**Phase 8 (tracker) hooks**: `GET /api/orders/{id}` `events[]` already contains every KYC transition (`KYC_IN_PROGRESS`, `KYC_ACTION_REQUIRED: C14`, `KYC_VERIFIED`, …); `estimated_activation_date` is on `customer_order` (exposed today via `GET …/kyc`; add it to `OrderView` if the tracker needs it).

### Order → KYC link (Phase 5 notes, implemented in Phase 6)

**How KYC finds the order**
- `POST /api/orders` returns `{orderId, orderRef, …}`; the UI navigates to **`/kyc/:orderId`** (route already exists: `frontend/src/pages/KycPlaceholder.tsx` reads `useParams().orderId` and calls `GET /api/orders/{orderId}`; Phase 7 replaces this page on the same route).
- KYC endpoints should hang off the order, e.g. `/api/orders/{orderId}/kyc/...`, and validate that the order exists (`OrderService.get` throws `NotFoundException` → 404).
- The order holds what KYC must match against: `customer_order.profile_json` (businessType, city, employees, sites, `gstin` or `gstinDeclared`) and `quote_json` (lines with `conditional: true` = products sold on a declared-but-unverified GSTIN; KYC must confirm an **Active** GSTIN or those lines become ineligible).
- If KYC changes eligibility (e.g. the GSTIN is Cancelled), recompute with `QuoteService.quote(profile, items, List.of())` (same pattern as `OrderService.create`) instead of editing prices.

**Order statuses so far**: `QUOTE_ACCEPTED` (initial; written to `customer_order.status` and as the first `order_event`). Phase 6 should add KYC statuses as new constants in `com.udyam.orders.OrderStatus` (e.g. `KYC_PENDING`, `KYC_SUBMITTED`, `KYC_VERIFIED`, `KYC_REJECTED`) and append an `order_event` row for every transition (`OrderStore.addEvent`); `status` is free text (no CHECK constraint), so no migration is needed just for new values. Phase 8 adds tracker statuses the same way.

**Reuse for KYC validation**: `GstinVerificationService.verify(raw)` (applies OCR correction for document text: use `effectiveGstin`, `correction`, `active`), `UdyamLookupService.validate/find`, `GstinValidator` / `UdyamValidator` (pure), `Masking` for logs, `AppProperties.referenceDate()` for document-age checks (address proof ≤ 90 days old per data/README.md). KYC images: `data/kyc_documents/` (K01–K12; K05 has no Udyam certificate). Known planted case: tampered GSTIN `08YKZXS8189L1Z0` (registry `08YKZXS5189L1ZO`) must stay flagged.

**Advisor session link**: an order created from the advisor has `customer_order.session_id` and the session has `stage = CHECKOUT_DONE`, `order_id`.

### Frontend contract (Phase 5, implemented)

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
