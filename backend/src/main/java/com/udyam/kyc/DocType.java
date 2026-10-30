package com.udyam.kyc;

import java.util.Locale;
import java.util.Optional;

/** The four KYC document slots. fileSuffix matches the specimen file names (K03_gst_certificate.png). */
public enum DocType {
    GST_CERTIFICATE,
    UDYAM_CERTIFICATE,
    SIGNATORY_ID,
    ADDRESS_PROOF;

    public String fileSuffix() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static Optional<DocType> parse(String value) {
        for (DocType t : values()) {
            if (t.name().equalsIgnoreCase(value == null ? "" : value.trim())) {
                return Optional.of(t);
            }
        }
        return Optional.empty();
    }
}
