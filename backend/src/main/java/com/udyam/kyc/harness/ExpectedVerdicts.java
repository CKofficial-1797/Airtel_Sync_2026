package com.udyam.kyc.harness;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.List;

/** The specimen answer key (backend/src/test/resources/kyc/expected-verdicts.json). */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExpectedVerdicts(List<Applicant> applicants) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Applicant(String applicant, String business, String expected, List<String> blocking,
                            String reason) {
    }

    public static ExpectedVerdicts load(ObjectMapper json, Path file) {
        try {
            return json.readValue(file.toFile(), ExpectedVerdicts.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read the expected verdicts " + file, e);
        }
    }
}
