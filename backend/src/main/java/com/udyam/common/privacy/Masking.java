package com.udyam.common.privacy;

/** Masks identifiers before they reach logs. Never log a full GSTIN or Udyam number. */
public final class Masking {

    private static final String STARS = "*****";

    private Masking() {
    }

    /** "32TTNXB7384T1ZW" → "32TTN*****1ZW" (first 5 + ***** + last 3). */
    public static String gstin(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() < 9) {
            return STARS;
        }
        return value.substring(0, 5) + STARS + value.substring(value.length() - 3);
    }

    /** ID / consumer numbers: everything but the last 3 characters replaced by '*' ("SYN-ID-346190" → "**********190"). */
    public static String keepLast3(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() <= 3) {
            return STARS;
        }
        return "*".repeat(value.length() - 3) + value.substring(value.length() - 3);
    }

    /** "UDYAM-KL-00-3078801" → "UDYAM-KL-00-****801" (prefix + last 3). */
    public static String udyam(String value) {
        if (value == null) {
            return null;
        }
        if (value.length() < 16 || !value.startsWith("UDYAM-")) {
            return STARS;
        }
        return value.substring(0, 12) + "****" + value.substring(value.length() - 3);
    }
}
