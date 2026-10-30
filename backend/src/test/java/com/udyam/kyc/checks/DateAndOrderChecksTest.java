package com.udyam.kyc.checks;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.udyam.kyc.DocType;
import com.udyam.kyc.KycDocument;
import com.udyam.kyc.KycFixtures;
import com.udyam.rules.RulesFixture;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** C14 / C15 date boundaries and C17 against an order profile, on top of K03's clean documents. */
class DateAndOrderChecksTest {

    private static final LocalDate TODAY = RulesFixture.REFERENCE_DATE;

    private static Map<DocType, KycDocument> k03With(DocType type, String field, String value) {
        Map<DocType, KycDocument> docs = KycFixtures.documents("K03");
        KycDocument d = docs.get(type);
        ObjectNode fields = d.fields().deepCopy();
        fields.put(field, value);
        docs.put(type, new KycDocument(d.docType(), d.status(), d.detectedDocType(), d.sha256(), d.blurScore(),
                d.skewDeg(), d.deskewed(), fields, d.model(), d.uploadedAt()));
        return docs;
    }

    private static CheckResult check(Map<DocType, KycDocument> docs, String code) {
        return KycFixtures.VALIDATOR.validate(new KycInput(docs, false, null, List.of())).checks().stream()
                .filter(c -> c.code().equals(code)).findFirst().orElseThrow();
    }

    @ParameterizedTest(name = "bill {0} days old -> {1}")
    @CsvSource({"0, PASS", "89, PASS", "90, PASS", "91, FAIL", "257, FAIL", "-1, FAIL"})
    void addressProofAgeBoundary(int daysOld, String expected) {
        String billDate = TODAY.minusDays(daysOld).toString();
        CheckResult c14 = check(k03With(DocType.ADDRESS_PROOF, "billDate", billDate), "C14");
        assertThat(c14.status()).isEqualTo(expected);
        if (expected.equals("FAIL")) {
            assertThat(c14.fixable()).isTrue();
        }
    }

    @Test
    void unreadableBillDateFailsAsFixable() {
        CheckResult c14 = check(k03With(DocType.ADDRESS_PROOF, "billDate", "31/10/2025"), "C14");
        assertThat(c14.status()).isEqualTo(CheckResult.FAIL);
        assertThat(c14.fixable()).isTrue();
    }

    @ParameterizedTest(name = "ID valid till today{0} -> {1}")
    @CsvSource({"-1, FAIL", "0, PASS", "1, PASS"})
    void idValidityBoundary(int offsetDays, String expected) {
        CheckResult c15 = check(k03With(DocType.SIGNATORY_ID, "validTill", TODAY.plusDays(offsetDays).toString()), "C15");
        assertThat(c15.status()).isEqualTo(expected);
    }

    @Test
    void differentSignatoryForAProprietorshipIsBlocking() {
        CheckResult c11 = check(k03With(DocType.SIGNATORY_ID, "name", "Ravi Kumar"), "C11");
        assertThat(c11.status()).isEqualTo(CheckResult.FAIL);
        assertThat(c11.fixable()).isFalse();
    }

    @Test
    void movedAddressFailsC12() {
        CheckResult c12 = check(k03With(DocType.ADDRESS_PROOF, "supplyAddress",
                "Plot 9, MIDC Hingna, Nagpur, Maharashtra - 440016"), "C12");
        assertThat(c12.status()).isEqualTo(CheckResult.FAIL);
        assertThat(c12.evidence()).containsEntry("billPincode", "440016");
    }

    @Test
    void udyamStateMismatchIsOnlyAWarning() {
        CheckResult c13 = check(k03With(DocType.UDYAM_CERTIFICATE, "state", "Gujarat"), "C13");
        assertThat(c13.status()).isEqualTo(CheckResult.WARN);
    }

    @Test
    void wrongDocTypeIsReportedByC03AndTheDocIsNotUsed() {
        Map<DocType, KycDocument> docs = KycFixtures.documents("K03");
        KycDocument id = docs.get(DocType.SIGNATORY_ID);
        docs.put(DocType.SIGNATORY_ID, new KycDocument(DocType.SIGNATORY_ID, "WRONG_DOC_TYPE", "ADDRESS_PROOF",
                id.sha256(), id.blurScore(), id.skewDeg(), false, null, id.model(), null));
        ValidationReport r = KycFixtures.VALIDATOR.validate(new KycInput(docs, false, null, List.of()));
        assertThat(r.overall()).isEqualTo(ValidationReport.ACTION_REQUIRED);
        assertThat(r.blockingCodes()).containsExactly("C03");
    }

    // ---- C17 ----

    private static ValidationReport withProfile(String applicant, String orderGstin, List<String> gstProducts) {
        return KycFixtures.VALIDATOR.validate(new KycInput(KycFixtures.documents(applicant), true, orderGstin,
                gstProducts));
    }

    @Test
    void orderGstinMustEqualTheKycGstin() {
        assertThat(withProfile("K03", "27XUIXG0767D1ZP", List.of()).overall()).isEqualTo(ValidationReport.VERIFIED);
        ValidationReport other = withProfile("K03", RulesFixture.ACTIVE_GSTIN, List.of());
        assertThat(other.blockingCodes()).containsExactly("C17");
        assertThat(other.overall()).isEqualTo(ValidationReport.ACTION_REQUIRED);
    }

    @Test
    void gstOnlyProductsNeedAnActiveGstin() {
        ValidationReport k01 = withProfile("K01", null, List.of("Business Leased Line"));
        CheckResult c17 = k01.checks().stream().filter(c -> c.code().equals("C17")).findFirst().orElseThrow();
        assertThat(c17.status()).isEqualTo(CheckResult.FAIL);
        assertThat(c17.fixHintEn()).isEqualTo("Remove GST-only services or update your GST registration.");
        assertThat(withProfile("K03", null, List.of("Business Leased Line")).overall())
                .isEqualTo(ValidationReport.VERIFIED);
    }

    @Test
    void c17IsSkippedWithoutAProfile() {
        CheckResult c17 = check(KycFixtures.documents("K03"), "C17");
        assertThat(c17.status()).isEqualTo(CheckResult.SKIPPED);
    }
}
