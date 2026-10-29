package com.udyam.registry.validation;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure Udyam number format validation (no DB). District code "00" is used on purpose in this
 * dataset and is accepted. State names come from UdyamStateCodes (null for codes not in the data).
 */
public final class UdyamValidator {

    private static final Pattern FORMAT = Pattern.compile("^UDYAM-[A-Z]{2}-[0-9]{2}-[0-9]{7}$");
    private static final Pattern WHITESPACE = Pattern.compile("\\s+");

    private UdyamValidator() {
    }

    /** Uppercase and remove ALL whitespace (OCR gives "UDYAM-MH- 00-6131836"); hyphens are kept. Null → "". */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return WHITESPACE.matcher(raw.toUpperCase(Locale.ROOT)).replaceAll("");
    }

    public static UdyamValidationResult validate(String raw) {
        String udyam = normalize(raw);
        if (udyam.isEmpty()) {
            return new UdyamValidationResult(udyam, false, null, null, List.of("Udyam number is required"));
        }
        if (!FORMAT.matcher(udyam).matches()) {
            return new UdyamValidationResult(udyam, false, null, null,
                    List.of("Udyam number must look like UDYAM-XX-00-1234567"));
        }
        String stateCode = udyam.substring(6, 8);
        return new UdyamValidationResult(udyam, true, stateCode, UdyamStateCodes.name(stateCode), List.of());
    }
}
