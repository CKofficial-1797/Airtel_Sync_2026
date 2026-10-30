package com.udyam.kyc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udyam.common.errors.ApiException;
import com.udyam.kyc.consent.ConsentNotices;
import com.udyam.kyc.extract.DocumentExtractor;
import com.udyam.kyc.extract.ExtractResult;
import com.udyam.kyc.extract.FixtureCache;
import com.udyam.orders.InMemoryOrderStore;
import com.udyam.orders.OrderService;
import com.udyam.common.config.BusinessClock;
import com.udyam.orders.OrderStatus;
import com.udyam.orders.OrderStatusMachine;
import com.udyam.orders.OrderStore;
import com.udyam.rules.RulesFixture;
import com.udyam.rules.quote.QuoteItem;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpStatus;

/** Consent, upload, temp-file hygiene, submit and withdrawal, with in-memory stores and a fake extractor. */
class KycServiceTest {

    private static final Map<String, Boolean> ALL_REQUIRED = Map.of(ConsentNotices.KYC_VERIFICATION, true,
            ConsentNotices.SERVICE_INSTALLATION, true);

    @TempDir
    Path tempDir;

    private InMemoryOrderStore orderStore;
    private InMemoryKycStore kycStore;
    private OrderService orders;
    private FakeExtractor extractor;
    private KycService kyc;

    /** Serves the golden fixture for the applicant whose file is uploaded; records the temp files it saw. */
    static class FakeExtractor implements DocumentExtractor {
        String applicant = "K03";
        RuntimeException failWith;
        final List<Path> seen = new ArrayList<>();
        final Map<Path, Boolean> existedDuringCall = new HashMap<>();

        @Override
        public ExtractResult extract(Path image, com.udyam.kyc.DocType docType) {
            seen.add(image);
            existedDuringCall.put(image, Files.exists(image));
            if (failWith != null) {
                throw failWith;
            }
            return KycFixtures.fixture(applicant, docType);
        }
    }

    @BeforeEach
    void setUp() {
        orderStore = new InMemoryOrderStore();
        kycStore = new InMemoryKycStore();
        orders = new OrderService(orderStore, RulesFixture.QUOTES, KycFixtures.JSON, RulesFixture.APP);
        extractor = new FakeExtractor();
        kyc = service(false);
    }

    private KycService service(boolean fixtureCache) {
        KycProperties props = KycFixtures.props(tempDir, fixtureCache);
        return new KycService(orderStore, new OrderStatusMachine(orderStore, new BusinessClock(RulesFixture.APP)), kycStore,
                KycFixtures.VALIDATOR, extractor, new FixtureCache(props, KycFixtures.JSON), new ConsentNotices(props),
                props, RulesFixture.PROVIDER, RulesFixture.APP);
    }

    private UUID consentedOrder() {
        UUID id = orders.createBlank("test");
        kyc.giveConsent(id, new KycService.ConsentRequest("2026-07-v1", "en", ALL_REQUIRED));
        return id;
    }

    private void uploadAll(UUID orderId, String applicant) {
        extractor.applicant = applicant;
        for (DocType t : DocType.values()) {
            if (Files.exists(KycFixtures.SPECIMENS.resolve(applicant + "_" + t.fileSuffix() + ".png"))) {
                kyc.upload(orderId, t.name(), KycFixtures.specimen(applicant, t));
            }
        }
    }

    private long tempFiles() throws IOException {
        try (Stream<Path> files = Files.list(tempDir)) {
            return files.count();
        }
    }

    private static void assertApiError(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
        });
    }

    // ---- consent ----

    @Test
    void uploadAndSubmitWithoutConsentAre403() {
        UUID id = orders.createBlank("test");
        byte[] png = KycFixtures.specimen("K03", DocType.GST_CERTIFICATE);
        assertApiError(() -> kyc.upload(id, "GST_CERTIFICATE", png), HttpStatus.FORBIDDEN, "CONSENT_REQUIRED");
        assertApiError(() -> kyc.submit(id), HttpStatus.FORBIDDEN, "CONSENT_REQUIRED");
        assertThat(extractor.seen).isEmpty();
    }

    @Test
    void requiredPurposesMustBeGranted() {
        UUID id = orders.createBlank("test");
        assertApiError(() -> kyc.giveConsent(id, new KycService.ConsentRequest("2026-07-v1", "hi",
                        Map.of(ConsentNotices.KYC_VERIFICATION, true, ConsentNotices.SERVICE_INSTALLATION, false))),
                HttpStatus.BAD_REQUEST, "REQUIRED_CONSENT_MISSING");
        assertApiError(() -> kyc.giveConsent(id, new KycService.ConsentRequest("old", "en", ALL_REQUIRED)),
                HttpStatus.BAD_REQUEST, "NOTICE_VERSION_MISMATCH");
        assertApiError(() -> kyc.giveConsent(id, new KycService.ConsentRequest("2026-07-v1", "fr", ALL_REQUIRED)),
                HttpStatus.BAD_REQUEST, "INVALID_LANGUAGE");
        assertApiError(() -> kyc.giveConsent(id, new KycService.ConsentRequest("2026-07-v1", "en",
                Map.of("KYC_VERIFICATION", true, "SERVICE_INSTALLATION", true, "MARKETING", true))),
                HttpStatus.BAD_REQUEST, "UNKNOWN_PURPOSE");
        assertThat(kycStore.activeConsent(id)).isEmpty();
    }

    @Test
    void consentStoresAllPurposesAndStartsKyc() {
        UUID id = consentedOrder();
        KycService.KycView view = kyc.view(id);
        assertThat(view.consent().active()).isTrue();
        assertThat(view.consent().purposes()).containsEntry(ConsentNotices.WHATSAPP_UPDATES, false)
                .containsEntry(ConsentNotices.KYC_VERIFICATION, true);
        assertThat(view.orderStatus()).isEqualTo(OrderStatus.KYC_IN_PROGRESS);
        assertThat(orderStore.events.get(id)).extracting(OrderStore.Event::status)
                .containsExactly(OrderStatus.QUOTE_ACCEPTED, OrderStatus.KYC_IN_PROGRESS);
    }

    @Test
    void noticeIsAvailableInHindiAndEnglish() {
        var hi = kyc.notice("hi");
        assertThat(hi.language()).isEqualTo("hi");
        assertThat(hi.title()).contains("सहमति");
        assertThat(hi.purposes()).extracting(p -> p.code() + ":" + p.required())
                .containsExactly("KYC_VERIFICATION:true", "SERVICE_INSTALLATION:true", "WHATSAPP_UPDATES:false");
        assertThat(kyc.notice("en").retention()).contains("deleted immediately").contains("as required by law");
        assertApiError(() -> kyc.notice("ta"), HttpStatus.BAD_REQUEST, "INVALID_LANGUAGE");
    }

    // ---- upload ----

    @Test
    void fileTypeAndSizeAreCheckedByMagicBytes() {
        UUID id = consentedOrder();
        assertApiError(() -> kyc.upload(id, "GST_CERTIFICATE", "%PDF-1.7 not an image".getBytes()),
                HttpStatus.UNSUPPORTED_MEDIA_TYPE, "UNSUPPORTED_FILE_TYPE");
        assertApiError(() -> kyc.upload(id, "GST_CERTIFICATE", new byte[0]), HttpStatus.BAD_REQUEST, "EMPTY_FILE");
        byte[] big = new byte[5 * 1024 * 1024 + 1];
        big[0] = (byte) 0xFF;
        big[1] = (byte) 0xD8;
        big[2] = (byte) 0xFF;
        assertApiError(() -> kyc.upload(id, "GST_CERTIFICATE", big), HttpStatus.PAYLOAD_TOO_LARGE, "FILE_TOO_LARGE");
        assertApiError(() -> kyc.upload(id, "PAN_CARD", KycFixtures.specimen("K03", DocType.GST_CERTIFICATE)),
                HttpStatus.BAD_REQUEST, "INVALID_DOC_TYPE");
        assertThat(extractor.seen).isEmpty();
    }

    @Test
    void tempFileExistsDuringExtractionAndIsDeletedAfterwards() throws IOException {
        UUID id = consentedOrder();
        KycService.DocumentView view = kyc.upload(id, "GST_CERTIFICATE", KycFixtures.specimen("K03",
                DocType.GST_CERTIFICATE));

        assertThat(extractor.seen).hasSize(1);
        assertThat(extractor.existedDuringCall).containsValue(true);
        assertThat(extractor.seen.get(0).getParent()).isEqualTo(tempDir.toAbsolutePath().normalize());
        assertThat(tempFiles()).isZero();
        assertThat(view.fields().get("gstin").asText()).isEqualTo("27XUI*****1ZP");
        assertThat(kycStore.documents(id).get(0).sha256()).isEqualTo(KycFixtures.sha256("K03", DocType.GST_CERTIFICATE));
    }

    @Test
    void tempFileIsDeletedEvenWhenTheAiServiceFails() throws IOException {
        UUID id = consentedOrder();
        extractor.failWith = new DocumentExtractor.ExtractionUnavailableException("down", null);

        assertApiError(() -> kyc.upload(id, "GST_CERTIFICATE", KycFixtures.specimen("K03", DocType.GST_CERTIFICATE)),
                HttpStatus.SERVICE_UNAVAILABLE, "EXTRACTION_UNAVAILABLE");
        extractor.failWith = new IllegalStateException("unexpected");
        assertThatThrownBy(() -> kyc.upload(id, "SIGNATORY_ID", KycFixtures.specimen("K03", DocType.SIGNATORY_ID)))
                .isInstanceOf(IllegalStateException.class);

        assertThat(extractor.seen).hasSize(2);
        assertThat(tempFiles()).isZero();
        assertThat(kycStore.documents(id)).isEmpty();
    }

    @Test
    void unreadableUploadStoresNoFieldsAndGivesAFixHint() {
        UUID id = consentedOrder();
        extractor.applicant = "K04";
        KycService.DocumentView view = kyc.upload(id, "GST_CERTIFICATE", KycFixtures.specimen("K04",
                DocType.GST_CERTIFICATE));

        assertThat(view.status()).isEqualTo(ExtractResult.UNREADABLE);
        assertThat(view.fields()).isNull();
        assertThat(view.fixHintEn()).isEqualTo(
                "Photo is blurry. Retake it in good light, flat on a table, and upload it again.");
        assertThat(view.fixHintHi()).isNotBlank();
        assertThat(kycStore.documents(id).get(0).fields()).isNull();
    }

    @Test
    void fixtureCacheServesSpecimensWithoutCallingTheExtractor() {
        kyc = service(true);
        UUID id = consentedOrder();
        KycService.DocumentView view = kyc.upload(id, "GST_CERTIFICATE", KycFixtures.specimen("K02",
                DocType.GST_CERTIFICATE));

        assertThat(view.fromFixtureCache()).isTrue();
        assertThat(view.quality().deskewed()).isTrue();
        assertThat(extractor.seen).isEmpty();
    }

    @Test
    void fixtureCacheAnswersWrongDocTypeForASpecimenInAnotherSlot() {
        kyc = service(true);
        UUID id = consentedOrder();

        KycService.DocumentView view = kyc.upload(id, "GST_CERTIFICATE", KycFixtures.specimen("K03",
                DocType.UDYAM_CERTIFICATE));

        assertThat(view.status()).isEqualTo(ExtractResult.WRONG_DOC_TYPE);
        assertThat(view.detectedDocType()).isEqualTo("UDYAM_CERTIFICATE");
        assertThat(view.fields()).isNull();
        assertThat(view.fixHintEn()).isEqualTo(
                "This looks like the Udyam certificate, not the GST certificate. Upload the GST certificate here.");
        assertThat(view.fixHintHi()).contains("उद्यम प्रमाणपत्र").doesNotContain("UDYAM_CERTIFICATE");
        assertThat(extractor.seen).isEmpty();
    }

    @Test
    void fixtureCacheKeepsABlurrySpecimenUnreadableInAnySlot() {
        kyc = service(true);
        UUID id = consentedOrder();
        KycService.DocumentView view = kyc.upload(id, "ADDRESS_PROOF", KycFixtures.specimen("K04",
                DocType.GST_CERTIFICATE));
        assertThat(view.status()).isEqualTo(ExtractResult.UNREADABLE);
        assertThat(extractor.seen).isEmpty();
    }

    @Test
    void fixtureCacheSendsOtherImagesToTheExtractor() {
        kyc = service(true);
        UUID id = consentedOrder();
        byte[] notASpecimen = {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3};
        kyc.upload(id, "GST_CERTIFICATE", notASpecimen);
        assertThat(extractor.seen).hasSize(1);
    }

    // ---- submit ----

    @Test
    void k03SubmitVerifiesTheOrderAndLogsEvents() {
        UUID id = consentedOrder();
        uploadAll(id, "K03");

        KycService.SubmitResult r = kyc.submit(id);

        assertThat(r.overall()).isEqualTo("VERIFIED");
        assertThat(r.orderStatus()).isEqualTo(OrderStatus.KYC_VERIFIED);
        assertThat(orderStore.find(id).orElseThrow().status()).isEqualTo(OrderStatus.KYC_VERIFIED);
        assertThat(orderStore.events.get(id)).extracting(OrderStore.Event::status).endsWith(OrderStatus.KYC_VERIFIED);
        assertThat(kyc.view(id).checks()).hasSize(17);
    }

    @Test
    void verifiedOrderGetsActivationDateFromTheQuote() {
        var profile = RulesFixture.profile("Kirana store", 2, 3, 1, "NONE");
        UUID id = orders.create(null, profile, List.of(new QuoteItem("P01", 1))).orderId();
        kyc.giveConsent(id, new KycService.ConsentRequest("2026-07-v1", "en", ALL_REQUIRED));
        uploadAll(id, "K03");

        KycService.SubmitResult r = kyc.submit(id);

        int installDays = orderStore.find(id).orElseThrow().quote().get("maxInstallDays").asInt();
        assertThat(r.overall()).isEqualTo("VERIFIED");
        assertThat(r.estimatedActivationDate()).isEqualTo(RulesFixture.REFERENCE_DATE.plusDays(installDays));
        assertThat(kyc.view(id).estimatedActivationDate()).isEqualTo(r.estimatedActivationDate());
    }

    @Test
    void k11SubmitNeedsActionAndReuploadReopensKyc() {
        UUID id = consentedOrder();
        uploadAll(id, "K11");
        KycService.SubmitResult r = kyc.submit(id);
        assertThat(r.overall()).isEqualTo("ACTION_REQUIRED");
        assertThat(r.blockingChecks()).containsExactly("C14");
        assertThat(orderStore.find(id).orElseThrow().status()).isEqualTo(OrderStatus.KYC_ACTION_REQUIRED);

        kyc.upload(id, "ADDRESS_PROOF", KycFixtures.specimen("K11", DocType.ADDRESS_PROOF));
        assertThat(orderStore.find(id).orElseThrow().status()).isEqualTo(OrderStatus.KYC_IN_PROGRESS);
    }

    @Test
    void k06SubmitIsRejected() {
        UUID id = consentedOrder();
        uploadAll(id, "K06");
        KycService.SubmitResult r = kyc.submit(id);
        assertThat(r.overall()).isEqualTo("REJECTED");
        assertThat(r.blockingChecks()).containsExactly("C04");
        assertThat(r.orderStatus()).isEqualTo(OrderStatus.KYC_REJECTED);
    }

    // ---- withdraw ----

    @Test
    void withdrawDeletesDocumentsAndChecksAndBlocksFurtherProcessing() {
        UUID id = consentedOrder();
        uploadAll(id, "K03");
        kyc.submit(id);

        KycService.KycView view = kyc.withdrawConsent(id);

        assertThat(view.consent().active()).isFalse();
        assertThat(view.orderStatus()).isEqualTo(OrderStatus.KYC_PENDING_CONSENT);
        assertThat(view.estimatedActivationDate()).isNull();
        assertThat(view.documents()).isEmpty();
        assertThat(view.checks()).isEmpty();
        assertThat(view.overall()).isNull();
        assertThat(view.missingDocuments()).hasSize(4);
        assertThat(kycStore.documents(id)).isEmpty();
        assertThat(kycStore.latestChecks(id)).isEmpty();
        assertThat(orderStore.events.get(id)).extracting(OrderStore.Event::status)
                .endsWith(OrderStatus.KYC_PENDING_CONSENT);
        assertApiError(() -> kyc.submit(id), HttpStatus.FORBIDDEN, "CONSENT_REQUIRED");
    }

    /** Regression: withdraw + new consent + submit used to turn a rejected applicant into VERIFIED. */
    @Test
    void withdrawingAndConsentingAgainCannotVerifyWithoutDocuments() {
        UUID id = consentedOrder();
        uploadAll(id, "K07");
        assertThat(kyc.submit(id).overall()).isEqualTo("REJECTED");

        kyc.withdrawConsent(id);
        kyc.giveConsent(id, new KycService.ConsentRequest("2026-07-v1", "en", ALL_REQUIRED));
        KycService.SubmitResult r = kyc.submit(id);

        assertThat(r.overall()).isEqualTo("ACTION_REQUIRED");
        assertThat(r.blockingChecks()).containsExactly("C01");
        assertThat(r.estimatedActivationDate()).isNull();
    }

    // ---- rules the status machine added (Phase 8) ----

    private void assertClosed(Runnable call) {
        assertApiError(call, HttpStatus.CONFLICT, "KYC_CLOSED");
    }

    @Test
    void aVerdictThatStandsCantBeReopenedByAnUploadOrAResubmit() {
        UUID verified = consentedOrder();
        uploadAll(verified, "K03");
        kyc.submit(verified);
        assertClosed(() -> kyc.upload(verified, "GST_CERTIFICATE", KycFixtures.specimen("K03", DocType.GST_CERTIFICATE)));
        assertClosed(() -> kyc.submit(verified));

        UUID rejected = consentedOrder();
        uploadAll(rejected, "K06");
        kyc.submit(rejected);
        assertClosed(() -> kyc.upload(rejected, "GST_CERTIFICATE", KycFixtures.specimen("K03", DocType.GST_CERTIFICATE)));
        assertThat(orderStore.find(rejected).orElseThrow().status()).isEqualTo(OrderStatus.KYC_REJECTED);
    }

    @Test
    void submitNeedsANewDocumentAfterActionRequired() {
        UUID id = consentedOrder();
        uploadAll(id, "K11");
        kyc.submit(id);
        assertClosed(() -> kyc.submit(id));
        kyc.upload(id, "ADDRESS_PROOF", KycFixtures.specimen("K11", DocType.ADDRESS_PROOF));
        assertThat(kyc.submit(id).overall()).isEqualTo("ACTION_REQUIRED");
    }

    @Test
    void aRejectedOwnerCanWithdrawAndApplyAgain() {
        UUID id = consentedOrder();
        uploadAll(id, "K06");
        kyc.submit(id);

        kyc.withdrawConsent(id);
        assertThat(orderStore.find(id).orElseThrow().status()).isEqualTo(OrderStatus.KYC_PENDING_CONSENT);

        kyc.giveConsent(id, new KycService.ConsentRequest("2026-07-v1", "en", ALL_REQUIRED));
        uploadAll(id, "K03");
        assertThat(kyc.submit(id).overall()).isEqualTo("VERIFIED");
    }

    @Test
    void withdrawalIsRefusedOnceInstalled() {
        UUID id = consentedOrder();
        uploadAll(id, "K03");
        kyc.submit(id);
        OrderStatusMachine machine = new OrderStatusMachine(orderStore, new BusinessClock(RulesFixture.APP));
        machine.transition(id, OrderStatus.INSTALL_SCHEDULED, "SYSTEM", null);
        machine.transition(id, OrderStatus.INSTALLED, "SYSTEM", null);

        assertApiError(() -> kyc.withdrawConsent(id), HttpStatus.CONFLICT, "WITHDRAW_NOT_ALLOWED");
        assertThat(kycStore.documents(id)).hasSize(4);
        assertThat(kyc.view(id).kycStatus()).isEqualTo(OrderStatus.KYC_VERIFIED);
    }

    @Test
    void withdrawingWithABookedSlotReleasesIt() {
        UUID id = consentedOrder();
        uploadAll(id, "K03");
        kyc.submit(id);
        new OrderStatusMachine(orderStore, new BusinessClock(RulesFixture.APP))
                .transition(id, OrderStatus.INSTALL_SCHEDULED, "CUSTOMER", null);
        orderStore.setInstallSlot(id, RulesFixture.REFERENCE_DATE.plusDays(5), "MORNING");

        kyc.withdrawConsent(id);
        assertThat(orderStore.tracking(id).slotDate()).isNull();
        assertThat(orderStore.find(id).orElseThrow().status()).isEqualTo(OrderStatus.KYC_PENDING_CONSENT);
    }

    @Test
    void theKycStatusStaysVerifiedOnceTheOrderMovesOn() {
        UUID id = consentedOrder();
        uploadAll(id, "K03");
        kyc.submit(id);
        OrderStatusMachine machine = new OrderStatusMachine(orderStore, new BusinessClock(RulesFixture.APP));
        machine.transition(id, OrderStatus.INSTALL_SCHEDULED, "SYSTEM", null);
        assertThat(kyc.view(id).kycStatus()).isEqualTo(OrderStatus.KYC_VERIFIED);
        machine.hold(id, "PAYMENT_PENDING", null, "DEMO");
        assertThat(kyc.view(id).kycStatus()).isEqualTo(OrderStatus.KYC_VERIFIED);
        assertThat(kyc.view(orders.createBlank("new")).kycStatus()).isEqualTo(OrderStatus.KYC_PENDING_CONSENT);
    }

    /** Regression: check messages quote names, so they must not survive a withdrawal. */
    @Test
    void withdrawalLeavesNoNamesInCheckMessages() {
        UUID id = consentedOrder();
        uploadAll(id, "K07");
        KycService.SubmitResult before = kyc.submit(id);
        assertThat(before.checks()).anyMatch(c -> c.messageEn().contains("Shri Physio Clinic"));

        kyc.withdrawConsent(id);

        assertThat(kyc.view(id).checks()).isEmpty();
    }
}
