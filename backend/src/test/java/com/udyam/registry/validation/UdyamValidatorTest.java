package com.udyam.registry.validation;

import static org.assertj.core.api.Assertions.assertThat;

import com.udyam.TestData;
import com.udyam.dataload.CsvParsing;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;

class UdyamValidatorTest {

    private static final List<CSVRecord> REGISTRY = CsvParsing.readAll(TestData.UDYAM_CSV);

    @Test
    void allRegistryUdyamNumbersPassFormat() {
        long valid = REGISTRY.stream().filter(r -> UdyamValidator.validate(r.get("udyam_number")).formatValid()).count();
        assertThat(REGISTRY).hasSize(2723);
        assertThat(valid).isEqualTo(2723);
    }

    @Test
    void derivedStateMappingHasNoConflictsAndMatchesTable() {
        Map<String, Set<String>> statesByCode = new HashMap<>();
        for (CSVRecord r : REGISTRY) {
            String code = r.get("udyam_number").substring(6, 8);
            statesByCode.computeIfAbsent(code, k -> new TreeSet<>()).add(r.get("state"));
        }

        Map<String, String> derived = new HashMap<>();
        statesByCode.forEach((code, states) -> {
            assertThat(states).as("states for Udyam code " + code).hasSize(1);
            derived.put(code, states.iterator().next());
        });
        assertThat(derived).isEqualTo(UdyamStateCodes.all());
    }

    @Test
    void normalizesOcrWhitespace() {
        assertThat(UdyamValidator.normalize("UDYAM-MH- 00-6131836")).isEqualTo("UDYAM-MH-00-6131836");
        assertThat(UdyamValidator.normalize("UDYAM- PB-00-9719478")).isEqualTo("UDYAM-PB-00-9719478");
        assertThat(UdyamValidator.normalize(" udyam-kl-00-3078801\t")).isEqualTo("UDYAM-KL-00-3078801");
        assertThat(UdyamValidator.normalize(null)).isEmpty();
    }

    @Test
    void validNumberHasDataDerivedState() {
        UdyamValidationResult v = UdyamValidator.validate("UDYAM-KR-00-1234567");
        assertThat(v.formatValid()).isTrue();
        assertThat(v.stateCode()).isEqualTo("KR");
        assertThat(v.stateName()).isEqualTo("Karnataka");
        assertThat(v.errors()).isEmpty();
    }

    @Test
    void invalidFormatsAreRejected() {
        assertThat(UdyamValidator.validate("UDYAM-MH-00-613183").formatValid()).isFalse();
        assertThat(UdyamValidator.validate("UDYAM-M1-00-6131836").formatValid()).isFalse();
        assertThat(UdyamValidator.validate("MH-00-6131836").formatValid()).isFalse();
        assertThat(UdyamValidator.validate("").errors()).containsExactly("Udyam number is required");
    }
}
