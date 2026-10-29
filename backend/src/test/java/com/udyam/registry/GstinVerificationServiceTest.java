package com.udyam.registry;

import static org.assertj.core.api.Assertions.assertThat;

import com.udyam.TestData;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class GstinVerificationServiceTest {

    private static GstinVerificationService service;

    @BeforeAll
    static void setUp() {
        CsvGstinRegistryLookup registry = CsvGstinRegistryLookup.fromCsv(TestData.GSTIN_CSV);
        service = new GstinVerificationService(registry, new GstinCorrectionService(registry));
    }

    @Test
    void activeGstinFoundWithoutCorrection() {
        GstinVerification v = service.verify("32ttnxb7384t1zw");
        assertThat(v.found()).isTrue();
        assertThat(v.effectiveGstin()).isEqualTo("32TTNXB7384T1ZW");
        assertThat(v.active()).isTrue();
        assertThat(v.stateName()).isEqualTo("Kerala");
        assertThat(v.correction()).isNull();
        assertThat(v.errors()).isEmpty();
    }

    @Test
    void cancelledGstinIsFoundButNotActive() {
        GstinVerification v = service.verify("37IHKXV5167L1ZS");
        assertThat(v.found()).isTrue();
        assertThat(v.status()).isEqualTo("Cancelled");
        assertThat(v.active()).isFalse();
    }

    @Test
    void ocrErrorIsCorrectedAndOriginalErrorsKept() {
        GstinVerification v = service.verify("19E0GXH4115Y1ZL");
        assertThat(v.formatValid()).isFalse();
        assertThat(v.found()).isTrue();
        assertThat(v.effectiveGstin()).isEqualTo("19EOGXH4115Y1ZL");
        assertThat(v.correction()).isNotNull();
        assertThat(v.correction().correctedGstin()).isEqualTo("19EOGXH4115Y1ZL");
        assertThat(v.errors()).isNotEmpty();
    }

    @Test
    void tamperedGstinStaysInvalid() {
        GstinVerification v = service.verify("08YKZXS8189L1Z0");
        assertThat(v.checksumValid()).isFalse();
        assertThat(v.found()).isFalse();
        assertThat(v.effectiveGstin()).isNull();
        assertThat(v.active()).isFalse();
        assertThat(v.correction()).isNull();
    }
}
