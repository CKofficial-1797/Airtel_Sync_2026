package com.udyam.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.udyam.TestData;
import com.udyam.registry.GstinCorrection.Substitution;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GstinCorrectionServiceTest {

    private static CsvGstinRegistryLookup registry;
    private static GstinCorrectionService service;

    @BeforeAll
    static void loadRegistry() {
        registry = CsvGstinRegistryLookup.fromCsv(TestData.GSTIN_CSV);
        service = new GstinCorrectionService(registry);
    }

    @Test
    void registryLoaded() {
        assertThat(registry.size()).isEqualTo(3534);
    }

    @Test
    void correctsZeroReadForLetterO() {
        GstinCorrection c = service.correct("19E0GXH4115Y1ZL");
        assertThat(c.corrected()).isTrue();
        assertThat(c.original()).isEqualTo("19E0GXH4115Y1ZL");
        assertThat(c.correctedGstin()).isEqualTo("19EOGXH4115Y1ZL");
        assertThat(c.substitutions()).containsExactly(new Substitution(3, '0', 'O'));
    }

    @Test
    void correctsSecondZeroForO() {
        GstinCorrection c = service.correct("24X0RXG9365M1Z3");
        assertThat(c.corrected()).isTrue();
        assertThat(c.correctedGstin()).isEqualTo("24XORXG9365M1Z3");
    }

    @Test
    void correctsIReadForJ() {
        GstinCorrection c = service.correct("27QHIXK0658U1ZL");
        assertThat(c.corrected()).isTrue();
        assertThat(c.correctedGstin()).isEqualTo("27QHJXK0658U1ZL");
        assertThat(c.substitutions()).containsExactly(new Substitution(4, 'I', 'J'));
    }

    @Test
    void doesNotCorrectTamperedGstin() {
        GstinCorrection c = service.correct("08YKZXS8189L1Z0");
        assertThat(c.corrected()).isFalse();
        assertThat(c.correctedGstin()).isNull();
        assertThat(c.substitutions()).isEmpty();
    }

    @Test
    void leavesValidRegistryGstinUnchanged() {
        GstinCorrection c = service.correct("32TTNXB7384T1ZW");
        assertThat(c.corrected()).isFalse();
        assertThat(c.original()).isEqualTo("32TTNXB7384T1ZW");
    }

    @Test
    void normalizesBeforeCorrecting() {
        GstinCorrection c = service.correct(" 19e0gxh4115y1zl ");
        assertThat(c.correctedGstin()).isEqualTo("19EOGXH4115Y1ZL");
    }

    @Test
    void wrongLengthIsNotCorrected() {
        assertThat(service.correct("19E0GXH4115Y1Z").corrected()).isFalse();
    }

    @Test
    void neverSubstitutesFiveOrEight() {
        GstinCorrectionService.generateCandidates("08YKZXS8189L1Z0").values().stream()
                .flatMap(java.util.List::stream)
                .forEach(s -> assertThat(String.valueOf(s.from()) + s.to()).doesNotContain("5", "8", "S", "B"));
    }
}
