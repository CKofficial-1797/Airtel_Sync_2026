package com.udyam.kyc;

import com.fasterxml.jackson.databind.JsonNode;
import com.udyam.kyc.extract.ExtractResult;
import java.time.OffsetDateTime;

/** One saved KYC document (no image: only its hash, quality metrics and extracted fields). */
public record KycDocument(
        DocType docType,
        String status,
        String detectedDocType,
        String sha256,
        Double blurScore,
        Double skewDeg,
        boolean deskewed,
        JsonNode fields,
        String model,
        OffsetDateTime uploadedAt) {

    public static KycDocument from(DocType slot, String sha256, ExtractResult r) {
        ExtractResult.Quality q = r.quality();
        boolean ok = ExtractResult.OK.equals(r.status());
        return new KycDocument(slot, r.status(), r.detectedDocType(), sha256, q == null ? null : q.blurScore(),
                q == null ? null : q.skewDeg(), q != null && q.deskewed(), ok ? r.fields() : null, r.model(), null);
    }

    /** Extracted and usable for the field checks. */
    public boolean usable() {
        return ExtractResult.OK.equals(status) && fields != null && fields.isObject();
    }

    /** A field as trimmed text, or null when missing / blank / not extracted. */
    public String text(String field) {
        if (!usable()) {
            return null;
        }
        JsonNode v = fields.get(field);
        if (v == null || v.isNull()) {
            return null;
        }
        String s = v.asText().trim();
        return s.isEmpty() ? null : s;
    }
}
