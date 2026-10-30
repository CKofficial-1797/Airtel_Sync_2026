package com.udyam.kyc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.udyam.common.config.AppProperties;
import com.udyam.common.errors.ApiException;
import com.udyam.common.errors.NotFoundException;
import com.udyam.kyc.checks.CheckResult;
import com.udyam.kyc.checks.KycInput;
import com.udyam.kyc.checks.KycValidationService;
import com.udyam.kyc.checks.Text;
import com.udyam.kyc.checks.ValidationReport;
import com.udyam.kyc.consent.ConsentNotice;
import com.udyam.kyc.consent.ConsentNotices;
import com.udyam.kyc.extract.DocumentExtractor;
import com.udyam.kyc.extract.ExtractResult;
import com.udyam.kyc.extract.FixtureCache;
import com.udyam.kyc.extract.Hashing;
import com.udyam.orders.OrderStatus;
import com.udyam.orders.OrderStore;
import com.udyam.rules.data.CatalogueProduct;
import com.udyam.rules.data.RulesDataProvider;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * KYC for one order: DPDP consent → document upload (extraction) → submit (C01..C17) → order status.
 * Nothing happens without active consent (403 CONSENT_REQUIRED). Raw images live only in a temp file for the
 * duration of the extraction call and are always deleted. Logs carry order refs, doc types and statuses only.
 */
@Service
public class KycService {

    public static final String CONSENT_REQUIRED = "CONSENT_REQUIRED";

    private static final Logger log = LoggerFactory.getLogger(KycService.class);
    private static final byte[] PNG_MAGIC = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n'};
    private static final byte[] JPEG_MAGIC = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF};
    private static final Set<String> VERDICT_STATUSES = Set.of(OrderStatus.KYC_ACTION_REQUIRED,
            OrderStatus.KYC_REJECTED, OrderStatus.KYC_VERIFIED);

    private final OrderStore orders;
    private final KycStore store;
    private final KycValidationService validator;
    private final DocumentExtractor extractor;
    private final FixtureCache fixtures;
    private final ConsentNotices notices;
    private final KycProperties props;
    private final RulesDataProvider rules;
    private final LocalDate referenceDate;

    public KycService(OrderStore orders, KycStore store, KycValidationService validator, DocumentExtractor extractor,
                      FixtureCache fixtures, ConsentNotices notices, KycProperties props, RulesDataProvider rules,
                      AppProperties app) {
        this.orders = orders;
        this.store = store;
        this.validator = validator;
        this.extractor = extractor;
        this.fixtures = fixtures;
        this.notices = notices;
        this.props = props;
        this.rules = rules;
        this.referenceDate = app.referenceDate();
    }

    // ---- consent ---------------------------------------------------------------------------------------

    public ConsentNotice notice(String language) {
        return notices.notice(language(language == null ? "en" : language));
    }

    public record ConsentRequest(String noticeVersion, String language, Map<String, Boolean> purposes) {
    }

    @Transactional
    public KycView giveConsent(UUID orderId, ConsentRequest request) {
        OrderStore.Order order = order(orderId);
        if (request == null || request.purposes() == null) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "purposes is required");
        }
        if (!notices.version().equals(request.noticeVersion())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "NOTICE_VERSION_MISMATCH",
                    "Consent must be given on the current notice version " + notices.version());
        }
        String language = language(request.language());
        List<String> unknown = request.purposes().keySet().stream().filter(p -> !ConsentNotices.ALL.contains(p))
                .sorted().toList();
        if (!unknown.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNKNOWN_PURPOSE", "Unknown consent purpose", unknown);
        }
        List<String> missing = ConsentNotices.REQUIRED.stream()
                .filter(p -> !Boolean.TRUE.equals(request.purposes().get(p))).sorted().toList();
        if (!missing.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "REQUIRED_CONSENT_MISSING",
                    "KYC cannot start without consent to the required purposes", missing);
        }

        Map<String, Boolean> rows = new LinkedHashMap<>();
        for (String p : List.of(ConsentNotices.KYC_VERIFICATION, ConsentNotices.SERVICE_INSTALLATION,
                ConsentNotices.WHATSAPP_UPDATES)) {
            rows.put(p, Boolean.TRUE.equals(request.purposes().get(p)));
        }
        store.saveConsent(orderId, request.noticeVersion(), language, rows);
        if (!order.status().startsWith("KYC_") || OrderStatus.KYC_PENDING_CONSENT.equals(order.status())) {
            transition(orderId, OrderStatus.KYC_IN_PROGRESS, "KYC consent given (notice " + request.noticeVersion()
                    + ", " + language + ")");
        }
        log.info("KYC consent given for order {} (whatsapp={})", order.orderRef(),
                rows.get(ConsentNotices.WHATSAPP_UPDATES));
        return view(orderId);
    }

    /** DPDP withdrawal: consent closed, extracted fields + check evidence deleted, order back to pending consent. */
    @Transactional
    public KycView withdrawConsent(UUID orderId) {
        OrderStore.Order order = order(orderId);
        int withdrawn = store.withdrawConsent(orderId);
        store.purgeExtractedData(orderId);
        store.setActivationDate(orderId, null);
        if (withdrawn > 0) {
            transition(orderId, OrderStatus.KYC_PENDING_CONSENT, "KYC consent withdrawn; extracted KYC data deleted");
            log.info("KYC consent withdrawn for order {}; extracted data purged", order.orderRef());
        }
        return view(orderId);
    }

    // ---- documents -------------------------------------------------------------------------------------

    public DocumentView upload(UUID orderId, String docTypeValue, byte[] data) {
        OrderStore.Order order = order(orderId);
        requireConsent(orderId);
        DocType docType = DocType.parse(docTypeValue).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                "INVALID_DOC_TYPE", "docType must be one of GST_CERTIFICATE, UDYAM_CERTIFICATE, SIGNATORY_ID,"
                + " ADDRESS_PROOF"));
        checkFile(data);

        String sha256 = Hashing.sha256(data);
        Optional<ExtractResult> cached = fixtures.find(sha256, docType);
        ExtractResult result = cached.orElseGet(() -> extractViaTempFile(data, docType));

        KycDocument doc = KycDocument.from(docType, sha256, result);
        store.saveDocument(orderId, doc);
        if (VERDICT_STATUSES.contains(order.status())) {
            store.setActivationDate(orderId, null);
            transition(orderId, OrderStatus.KYC_IN_PROGRESS, "KYC document replaced: " + docType);
        }
        log.info("KYC upload order {} {} -> {} ({} ms{})", order.orderRef(), docType, doc.status(), result.ms(),
                cached.isPresent() ? ", fixture cache" : "");
        return DocumentView.of(doc, cached.isPresent());
    }

    /** Writes the upload to app.kyc.temp-dir only for the extraction call; the file is deleted in every case. */
    ExtractResult extractViaTempFile(byte[] data, DocType docType) {
        Path temp = null;
        try {
            Path dir = props.tempPath();
            Files.createDirectories(dir);
            temp = Files.createTempFile(dir, "kyc-", ".img");
            Files.write(temp, data);
            return extractor.extract(temp, docType);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write the KYC temp file", e);
        } catch (DocumentExtractor.InvalidImageException e) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_IMAGE", e.getMessage());
        } catch (DocumentExtractor.ExtractionUnavailableException e) {
            log.warn("KYC extraction unavailable for {}: {}", docType, e.getMessage());
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "EXTRACTION_UNAVAILABLE",
                    "Document reading is unavailable right now; please try again in a minute");
        } finally {
            deleteQuietly(temp);
        }
    }

    private void checkFile(byte[] data) {
        if (data == null || data.length == 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "EMPTY_FILE", "The file is empty");
        }
        if (data.length > props.maxUploadBytes()) {
            throw new ApiException(HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE",
                    "The file is larger than " + props.maxUploadBytes() / (1024 * 1024) + " MB");
        }
        if (!startsWith(data, PNG_MAGIC) && !startsWith(data, JPEG_MAGIC)) {
            throw new ApiException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE",
                    "Only PNG or JPEG images are accepted");
        }
    }

    // ---- submit + view ---------------------------------------------------------------------------------

    @Transactional
    public SubmitResult submit(UUID orderId) {
        OrderStore.Order order = order(orderId);
        requireConsent(orderId);

        Map<DocType, KycDocument> docs = new EnumMap<>(DocType.class);
        store.documents(orderId).forEach(d -> docs.put(d.docType(), d));
        boolean hasProfile = order.profile() instanceof ObjectNode;
        String orderGstin = hasProfile && order.profile().hasNonNull("gstin") ? order.profile().get("gstin").asText()
                : null;
        ValidationReport report = validator.validate(new KycInput(docs, hasProfile, orderGstin,
                gstRequiredProducts(order.quote())));

        UUID runId = UUID.randomUUID();
        store.saveChecks(orderId, runId, report.checks());
        String status = switch (report.overall()) {
            case ValidationReport.VERIFIED -> OrderStatus.KYC_VERIFIED;
            case ValidationReport.REJECTED -> OrderStatus.KYC_REJECTED;
            default -> OrderStatus.KYC_ACTION_REQUIRED;
        };
        LocalDate activation = null;
        if (OrderStatus.KYC_VERIFIED.equals(status) && order.quote().has("maxInstallDays")) {
            activation = referenceDate.plusDays(order.quote().get("maxInstallDays").asInt());
        }
        store.setActivationDate(orderId, activation);
        transition(orderId, status, report.blockingCodes().isEmpty() ? "KYC verified"
                : "KYC " + report.overall() + ": " + String.join(", ", report.blockingCodes()));
        log.info("KYC submit order {} -> {} blocking={} warnings={}", order.orderRef(), report.overall(),
                report.blockingCodes(), report.warningCodes());
        return new SubmitResult(orderId, order.orderRef(), report.overall(), status, report.blockingCodes(),
                report.warningCodes(), report.checks(), activation, runId);
    }

    public KycView view(UUID orderId) {
        OrderStore.Order order = order(orderId);
        List<KycStore.Consent> consent = store.activeConsent(orderId);
        Map<String, Boolean> purposes = new LinkedHashMap<>();
        consent.forEach(c -> purposes.put(c.purpose(), c.granted()));
        ConsentView consentView = consent.isEmpty() ? new ConsentView(false, null, null, Map.of(), null)
                : new ConsentView(hasRequired(consent), consent.get(0).noticeVersion(), consent.get(0).language(),
                purposes, consent.get(0).createdAt());

        List<KycDocument> docs = store.documents(orderId);
        List<DocType> missing = new ArrayList<>(List.of(DocType.values()));
        docs.forEach(d -> missing.remove(d.docType()));
        List<CheckResult> checks = store.latestChecks(orderId);
        String overall = checks.isEmpty() ? null : ValidationReport.of(checks).overall();
        String orderStatus = orders.find(orderId).map(OrderStore.Order::status).orElse(order.status());
        String kycStatus = orderStatus.startsWith("KYC_") ? orderStatus : OrderStatus.KYC_PENDING_CONSENT;
        return new KycView(orderId, order.orderRef(), orderStatus, kycStatus, consentView,
                docs.stream().map(d -> DocumentView.of(d, false)).toList(), missing, overall, checks,
                store.activationDate(orderId).orElse(null));
    }

    // ---- helpers ---------------------------------------------------------------------------------------

    private OrderStore.Order order(UUID orderId) {
        return orders.find(orderId).orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
    }

    private void requireConsent(UUID orderId) {
        if (!hasRequired(store.activeConsent(orderId))) {
            throw new ApiException(HttpStatus.FORBIDDEN, CONSENT_REQUIRED,
                    "Give KYC consent (POST /api/orders/{id}/kyc/consent) before uploading or submitting documents");
        }
    }

    private static boolean hasRequired(List<KycStore.Consent> consent) {
        return ConsentNotices.REQUIRED.stream().allMatch(p -> consent.stream()
                .anyMatch(c -> c.purpose().equals(p) && c.granted()));
    }

    private void transition(UUID orderId, String status, String note) {
        orders.updateStatus(orderId, status);
        orders.addEvent(orderId, status, note);
    }

    /** Names of ordered products that need an active GSTIN (catalogue requires_gstin). */
    private List<String> gstRequiredProducts(JsonNode quote) {
        List<String> names = new ArrayList<>();
        if (quote != null && quote.has("lines")) {
            for (JsonNode line : quote.get("lines")) {
                rules.get().catalogue().find(line.path("productId").asText())
                        .filter(CatalogueProduct::requiresGstin)
                        .ifPresent(p -> names.add(p.productName()));
            }
        }
        return names;
    }

    private static String language(String value) {
        if (!ConsentNotices.LANGUAGES.contains(value)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_LANGUAGE", "language must be en or hi");
        }
        return value;
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        if (data.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (data[i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static void deleteQuietly(Path file) {
        if (file == null) {
            return;
        }
        try {
            Files.deleteIfExists(file);
        } catch (IOException e) {
            log.error("Could not delete KYC temp file {}", file.getFileName(), e);
        }
    }

    // ---- API shapes ------------------------------------------------------------------------------------

    public record Quality(Double blurScore, Double skewDeg, boolean deskewed) {
    }

    /** One document as the API shows it: identifiers masked, plus a fix hint when it must be re-uploaded. */
    public record DocumentView(DocType docType, String status, String detectedDocType, Quality quality,
                               JsonNode fields, String fixHintEn, String fixHintHi, String model,
                               boolean fromFixtureCache, OffsetDateTime uploadedAt) {

        static DocumentView of(KycDocument d, boolean fromFixtureCache) {
            Text hint = fixHint(d);
            return new DocumentView(d.docType(), d.status(), d.detectedDocType(),
                    new Quality(d.blurScore(), d.skewDeg(), d.deskewed()), KycMasking.fields(d.fields()),
                    hint == null ? null : hint.en(), hint == null ? null : hint.hi(), d.model(), fromFixtureCache,
                    d.uploadedAt());
        }

        private static Text fixHint(KycDocument d) {
            if (ExtractResult.UNREADABLE.equals(d.status())) {
                return Text.of("Photo is blurry. Retake it in good light, flat on a table, and upload it again.",
                        "फ़ोटो धुंधली है। अच्छी रोशनी में, दस्तावेज़ को मेज़ पर सीधा रखकर दोबारा फ़ोटो लें और अपलोड करें।");
            }
            if (ExtractResult.WRONG_DOC_TYPE.equals(d.status())) {
                return Text.of("This looks like a different document (" + d.detectedDocType() + "). Upload the "
                                + d.docType() + " in this slot.",
                        "यह कोई दूसरा दस्तावेज़ लगता है (" + d.detectedDocType() + ")। इस जगह पर " + d.docType()
                                + " अपलोड करें।");
            }
            return null;
        }
    }

    public record ConsentView(boolean active, String noticeVersion, String language, Map<String, Boolean> purposes,
                              OffsetDateTime givenAt) {
    }

    public record KycView(UUID orderId, String orderRef, String orderStatus, String kycStatus, ConsentView consent,
                          List<DocumentView> documents, List<DocType> missingDocuments, String overall,
                          List<CheckResult> checks, LocalDate estimatedActivationDate) {
    }

    public record SubmitResult(UUID orderId, String orderRef, String overall, String orderStatus,
                               List<String> blockingChecks, List<String> warnings, List<CheckResult> checks,
                               LocalDate estimatedActivationDate, UUID runId) {
    }
}
