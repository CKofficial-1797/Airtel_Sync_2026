package com.udyam.registry.validation;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Pure GSTIN format + checksum validation (no DB).
 * The 4th PAN character is always 'X' in this dataset (fictional); it is accepted on purpose.
 */
public final class GstinValidator {

    public static final int LENGTH = 15;

    private static final String CHARS = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZ";
    private static final Pattern FORMAT = Pattern.compile("^[0-9]{2}[A-Z]{5}[0-9]{4}[A-Z][1-9A-Z]Z[0-9A-Z]$");
    private static final Pattern ALPHANUMERIC = Pattern.compile("^[0-9A-Z]+$");
    private static final Pattern SEPARATORS = Pattern.compile("[\\s.\\-]");

    private GstinValidator() {
    }

    /** Uppercase, trim, remove spaces, hyphens and dots. Null → "". */
    public static String normalize(String raw) {
        if (raw == null) {
            return "";
        }
        return SEPARATORS.matcher(raw.trim().toUpperCase(Locale.ROOT)).replaceAll("");
    }

    public static GstinValidationResult validate(String raw) {
        String gstin = normalize(raw);
        List<String> errors = new ArrayList<>();

        if (gstin.isEmpty()) {
            errors.add("GSTIN is required");
            return new GstinValidationResult(gstin, false, false, null, null, List.copyOf(errors));
        }

        boolean rightLength = gstin.length() == LENGTH;
        boolean alphanumeric = ALPHANUMERIC.matcher(gstin).matches();
        if (!rightLength) {
            errors.add("GSTIN must be 15 characters (got " + gstin.length() + ")");
        }
        if (!alphanumeric) {
            errors.add("GSTIN may contain only letters and digits");
        }

        boolean formatValid = FORMAT.matcher(gstin).matches();
        if (rightLength && alphanumeric && !formatValid) {
            errors.add("GSTIN does not match the pattern: 2-digit state code, 10-character PAN "
                    + "(5 letters, 4 digits, 1 letter), entity number, 'Z', check character");
        }

        String stateCode = gstin.length() >= 2 && Character.isDigit(gstin.charAt(0))
                && Character.isDigit(gstin.charAt(1)) ? gstin.substring(0, 2) : null;
        String stateName = GstStateCodes.name(stateCode);
        if (formatValid && stateName == null) {
            formatValid = false;
            errors.add("Unknown GST state code " + stateCode);
        }

        boolean checksumValid = false;
        if (rightLength && alphanumeric) {
            checksumValid = checksumChar(gstin) == gstin.charAt(LENGTH - 1);
            if (!checksumValid) {
                errors.add("Checksum digit is wrong; the GSTIN may be mistyped or tampered");
            }
        }

        return new GstinValidationResult(gstin, formatValid, checksumValid, stateCode, stateName, List.copyOf(errors));
    }

    /** True when format and checksum both pass. */
    public static boolean isValid(String raw) {
        return validate(raw).valid();
    }

    /**
     * Official GSTIN mod-36 check character, computed over the first 14 characters.
     * Expects uppercase [0-9A-Z]; the 15th character, if present, is ignored.
     */
    public static char checksumChar(String gstin) {
        int sum = 0;
        for (int i = 0; i < LENGTH - 1; i++) {
            int v = CHARS.indexOf(gstin.charAt(i)) * (i % 2 == 0 ? 1 : 2);
            sum += v / 36 + v % 36;
        }
        return CHARS.charAt((36 - sum % 36) % 36);
    }
}
