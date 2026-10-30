package com.udyam.kyc.extract;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * Response of the ai-service POST /extract (also the golden fixture format).
 * fields is null unless status is OK.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ExtractResult(
        String status,
        String docType,
        String detectedDocType,
        Quality quality,
        JsonNode fields,
        String model,
        long ms) {

    public static final String OK = "OK";
    public static final String UNREADABLE = "UNREADABLE";
    public static final String WRONG_DOC_TYPE = "WRONG_DOC_TYPE";

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Quality(double blurScore, boolean isBlurry, double skewDeg, boolean deskewed) {
    }
}
