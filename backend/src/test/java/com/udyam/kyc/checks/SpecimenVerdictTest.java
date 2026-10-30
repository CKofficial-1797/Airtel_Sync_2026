package com.udyam.kyc.checks;

import static org.assertj.core.api.Assertions.assertThat;

import com.udyam.kyc.DocType;
import com.udyam.kyc.KycFixtures;
import com.udyam.kyc.harness.ExpectedVerdicts;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** The 12 specimen applicants (golden fixtures) give exactly the answer-key verdicts and blocking codes. */
class SpecimenVerdictTest {

    static Stream<ExpectedVerdicts.Applicant> applicants() {
        return KycFixtures.expected().applicants().stream();
    }

    static ValidationReport run(String applicant) {
        return KycFixtures.VALIDATOR.validate(new KycInput(KycFixtures.documents(applicant), false, null, List.of()));
    }

    static Map<String, CheckResult> byCode(ValidationReport r) {
        return r.checks().stream().collect(Collectors.toMap(CheckResult::code, Function.identity()));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("applicants")
    void verdictAndBlockingChecksMatchTheAnswerKey(ExpectedVerdicts.Applicant a) {
        ValidationReport report = run(a.applicant());

        assertThat(report.overall()).as(a.applicant() + " verdict").isEqualTo(a.expected());
        assertThat(report.blockingCodes()).as(a.applicant() + " blocking").containsExactlyInAnyOrderElementsOf(a.blocking());
        assertThat(report.checks()).extracting(CheckResult::code)
                .containsExactly("C01", "C02", "C03", "C04", "C05", "C06", "C07", "C08", "C09", "C10", "C11", "C12",
                        "C13", "C14", "C15", "C16", "C17");
    }

    @Test
    void answerKeyCoversAllTwelveApplicants() {
        assertThat(applicants().map(ExpectedVerdicts.Applicant::applicant))
                .containsExactly("K01", "K02", "K03", "K04", "K05", "K06", "K07", "K08", "K09", "K10", "K11", "K12");
    }

    @Test
    void k02AndK08GstinsAreOcrCorrectedWithMaskedEvidence() {
        CheckResult k02 = byCode(run("K02")).get("C04");
        assertThat(k02.status()).isEqualTo(CheckResult.PASS);
        assertThat(k02.evidence()).containsEntry("readAs", "19E0G*****1ZL").containsEntry("correctedTo", "19EOG*****1ZL");
        assertThat(k02.messageEn()).contains("corrected to");
        assertThat(byCode(run("K08")).get("C04").evidence()).containsKey("correctedTo");
    }

    @Test
    void k06TamperedGstinIsNotCorrectedAndRegistryChecksAreSkipped() {
        Map<String, CheckResult> c = byCode(run("K06"));
        assertThat(c.get("C04").status()).isEqualTo(CheckResult.FAIL);
        assertThat(c.get("C04").fixable()).isFalse();
        assertThat(c.get("C04").evidence()).doesNotContainKey("correctedTo");
        assertThat(List.of(c.get("C05"), c.get("C06"), c.get("C07"))).extracting(CheckResult::status)
                .containsOnly(CheckResult.SKIPPED);
    }

    @Test
    void k04UnreadableGstSkipsEveryGstDependentCheckAndGuessesNothing() {
        assertThat(KycFixtures.documents("K04").get(DocType.GST_CERTIFICATE).fields()).isNull();
        Map<String, CheckResult> c = byCode(run("K04"));
        assertThat(c.get("C02").fixHintEn()).contains("Retake it in good light");
        assertThat(c.get("C02").fixHintHi()).isNotBlank();
        for (String code : List.of("C04", "C05", "C06", "C07", "C11", "C12", "C13")) {
            assertThat(c.get(code).status()).as(code).isEqualTo(CheckResult.SKIPPED);
        }
        assertThat(c.get("C10").status()).isEqualTo(CheckResult.PASS); // Udyam, bill and ID still agree
    }

    @Test
    void k01CancelledGstinAndBlankUdyamNumber() {
        Map<String, CheckResult> c = byCode(run("K01"));
        assertThat(c.get("C06").evidence()).containsEntry("registryStatus", "Cancelled");
        assertThat(c.get("C08").fixable()).isTrue();
        assertThat(c.get("C09").status()).isEqualTo(CheckResult.SKIPPED);
    }

    @Test
    void k07ShriVersusNeelgiriFailsNameChecks() {
        Map<String, CheckResult> c = byCode(run("K07"));
        assertThat(c.get("C07").status()).isEqualTo(CheckResult.FAIL);
        assertThat(c.get("C10").messageEn()).contains("Shri Physio Clinic").contains("Neelgiri Physio Clinic");
        assertThat(c.get("C11").status()).isEqualTo(CheckResult.PASS); // the proprietor is still the signatory
    }

    @Test
    void llpAndPvtLtdSignatoriesGetASoftWarningOnly() {
        for (String applicant : List.of("K05", "K10", "K11")) {
            CheckResult c11 = byCode(run(applicant)).get("C11");
            assertThat(c11.status()).as(applicant).isEqualTo(CheckResult.WARN);
            assertThat(c11.severity()).isEqualTo(CheckResult.SOFT);
            assertThat(c11.fixHintEn()).contains("board resolution");
        }
        assertThat(byCode(run("K10")).get("C16").status()).isEqualTo(CheckResult.WARN); // registry noise, soft
    }

    @Test
    void k11OldBillIsFixableWithBilingualHint() {
        CheckResult c14 = byCode(run("K11")).get("C14");
        assertThat(c14.status()).isEqualTo(CheckResult.FAIL);
        assertThat(c14.fixable()).isTrue();
        assertThat(c14.evidence()).containsEntry("ageDays", 257L);
        assertThat(c14.fixHintEn()).contains("last 90 days");
        assertThat(c14.fixHintHi()).contains("90");
    }

    @Test
    void everyCheckHasEnglishAndHindiMessagesAndFailuresHaveHints() {
        applicants().forEach(a -> run(a.applicant()).checks().forEach(c -> {
            assertThat(c.messageEn()).as(a.applicant() + " " + c.code()).isNotBlank();
            assertThat(c.messageHi()).as(a.applicant() + " " + c.code()).isNotBlank().isNotEqualTo(c.messageEn());
            if (c.blockingFailure()) {
                assertThat(c.fixHintEn()).as(a.applicant() + " " + c.code()).isNotBlank();
                assertThat(c.fixHintHi()).as(a.applicant() + " " + c.code()).isNotBlank();
            }
        }));
    }

    @Test
    void evidenceNeverContainsAFullGstinOrUdyamNumber() {
        applicants().forEach(a -> run(a.applicant()).checks().forEach(c -> {
            String evidence = String.valueOf(c.evidence()) + c.messageEn() + c.messageHi();
            assertThat(evidence).as(a.applicant() + " " + c.code())
                    .doesNotContainPattern("\\d{2}[A-Z0-9]{5}\\d{4}[A-Z0-9]{4}")
                    .doesNotContainPattern("UDYAM-[A-Z]{2}-\\d{2}-\\d{7}");
        }));
    }
}
