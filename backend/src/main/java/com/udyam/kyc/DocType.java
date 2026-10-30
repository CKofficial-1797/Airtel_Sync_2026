package com.udyam.kyc;

import com.udyam.kyc.checks.Text;
import java.util.Locale;
import java.util.Optional;

/** The four KYC document slots. fileSuffix matches the specimen file names (K03_gst_certificate.png). */
public enum DocType {
    GST_CERTIFICATE(Text.of("GST certificate", "GST प्रमाणपत्र")),
    UDYAM_CERTIFICATE(Text.of("Udyam certificate", "उद्यम प्रमाणपत्र")),
    SIGNATORY_ID(Text.of("signatory ID", "हस्ताक्षरकर्ता पहचान पत्र")),
    ADDRESS_PROOF(Text.of("address proof", "पते का प्रमाण"));

    private final Text label;

    DocType(Text label) {
        this.label = label;
    }

    /** Name shown to the customer in messages and hints. */
    public Text label() {
        return label;
    }

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
