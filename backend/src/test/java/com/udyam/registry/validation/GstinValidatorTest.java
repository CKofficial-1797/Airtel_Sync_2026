package com.udyam.registry.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.udyam.TestData;
import com.udyam.dataload.CsvParsing;
import java.util.ArrayList;
import java.util.List;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;

class GstinValidatorTest {

    private static final List<CSVRecord> REGISTRY = CsvParsing.readAll(TestData.GSTIN_CSV);

    @Test
    void allRegistryGstinsPassFormatAndChecksum() {
        long valid = REGISTRY.stream().filter(r -> GstinValidator.isValid(r.get("gstin"))).count();
        assertThat(REGISTRY).hasSize(3534);
        assertThat(valid).isEqualTo(3534);
    }

    @Test
    void stateCodeTableMatchesEveryRegistryRow() {
        List<String> mismatches = new ArrayList<>();
        for (CSVRecord r : REGISTRY) {
            GstinValidationResult v = GstinValidator.validate(r.get("gstin"));
            if (!r.get("state").equals(v.stateName())) {
                mismatches.add(v.stateCode() + " -> " + v.stateName() + " vs " + r.get("state"));
            }
        }
        assertThat(mismatches).isEmpty();
    }

    @Test
    void normalizesCaseSpacesHyphensAndDots() {
        assertThat(GstinValidator.normalize(" 32ttnxb7384t1zw ")).isEqualTo("32TTNXB7384T1ZW");
        assertThat(GstinValidator.normalize("32 TTNXB-7384.T1ZW")).isEqualTo("32TTNXB7384T1ZW");
        assertThat(GstinValidator.normalize(null)).isEmpty();
        assertThat(GstinValidator.isValid(" 32ttnxb7384t1zw ")).isTrue();
    }

    @Test
    void validGstinHasStateAndNoErrors() {
        GstinValidationResult v = GstinValidator.validate("32TTNXB7384T1ZW");
        assertThat(v.formatValid()).isTrue();
        assertThat(v.checksumValid()).isTrue();
        assertThat(v.stateCode()).isEqualTo("32");
        assertThat(v.stateName()).isEqualTo("Kerala");
        assertThat(v.errors()).isEmpty();
    }

    @Test
    void shortInputFailsFormat() {
        GstinValidationResult v = GstinValidator.validate("ABC");
        assertThat(v.formatValid()).isFalse();
        assertThat(v.checksumValid()).isFalse();
        assertThat(v.errors()).anyMatch(e -> e.contains("15 characters"));
    }

    @Test
    void fourteenCharactersFailsFormat() {
        GstinValidationResult v = GstinValidator.validate("32TTNXB7384T1Z");
        assertThat(v.formatValid()).isFalse();
        assertThat(v.errors()).anyMatch(e -> e.contains("15 characters"));
    }

    @Test
    void tamperedGstinFailsChecksumOnly() {
        GstinValidationResult v = GstinValidator.validate("08YKZXS8189L1Z0");
        assertThat(v.formatValid()).isTrue();
        assertThat(v.checksumValid()).isFalse();
        assertThat(v.errors()).containsExactly("Checksum digit is wrong; the GSTIN may be mistyped or tampered");
    }

    @Test
    void unknownStateCodeFailsFormat() {
        String body = "99AAAXA0000A1Z";
        GstinValidationResult v = GstinValidator.validate(body + GstinValidator.checksumChar(body + "0"));
        assertThat(v.checksumValid()).isTrue();
        assertThat(v.formatValid()).isFalse();
        assertThat(v.errors()).anyMatch(e -> e.contains("Unknown GST state code"));
    }

    @Test
    void validChecksumButNotInRegistry() {
        String body = "27AAAXA0000A1Z";
        String gstin = body + GstinValidator.checksumChar(body + "0");
        boolean inRegistry = REGISTRY.stream().anyMatch(r -> r.get("gstin").equals(gstin));

        assertThat(GstinValidator.isValid(gstin)).isTrue();
        assertThat(inRegistry).isFalse();
        System.out.println("Valid-checksum GSTIN not in registry (for manual 404 check): " + gstin);
    }
}
