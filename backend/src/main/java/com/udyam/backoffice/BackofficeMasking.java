package com.udyam.backoffice;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.udyam.common.privacy.Masking;
import java.util.Locale;
import java.util.regex.Pattern;

/** Staff don't need full identifiers to follow up. Owners also type them into the chat, so free text is masked too. */
final class BackofficeMasking {

    private static final Pattern GSTIN = Pattern.compile("\\b\\d{2}[A-Za-z0-9]{13}\\b");
    private static final Pattern UDYAM = Pattern.compile("(?i)\\bUDYAM-[A-Z]{2}-\\d{2}-\\d{7}\\b");
    private static final Pattern PAN = Pattern.compile("(?i)\\b[A-Z]{5}\\d{4}[A-Z]\\b");
    private static final Pattern GROUPED_NUMBER = Pattern.compile("\\b(?:\\d{4}[ -]\\d{4}[ -]\\d{4}|\\d{5}[ -]\\d{5})\\b");
    private static final Pattern LONG_NUMBER = Pattern.compile("\\b\\d{10,}\\b");
    private static final Pattern QUOTED = Pattern.compile("'([^']+)'");

    private BackofficeMasking() {
    }

    static String text(String value) {
        if (value == null) {
            return null;
        }
        String masked = UDYAM.matcher(value).replaceAll(m -> Masking.udyam(m.group().toUpperCase(Locale.ROOT)));
        masked = GSTIN.matcher(masked).replaceAll(m -> Masking.gstin(m.group().toUpperCase(Locale.ROOT)));
        masked = PAN.matcher(masked).replaceAll(m -> Masking.keepLast3(m.group()));
        masked = GROUPED_NUMBER.matcher(masked).replaceAll(m -> Masking.keepLast3(m.group()));
        return LONG_NUMBER.matcher(masked).replaceAll(m -> Masking.keepLast3(m.group()));
    }

    /** KYC check messages quote identifiers and, in single quotes, the names read from the documents. */
    static String checkMessage(String value) {
        return value == null ? null : QUOTED.matcher(text(value)).replaceAll(m -> "'" + kycName(m.group(1)) + "'");
    }

    /** A copy of the advisor profile with its identifier masked. */
    static JsonNode profile(JsonNode profile) {
        if (profile instanceof ObjectNode obj && obj.hasNonNull("gstin")) {
            ObjectNode copy = obj.deepCopy();
            copy.put("gstin", Masking.gstin(obj.get("gstin").asText()));
            return copy;
        }
        return profile;
    }

    /** A name read from KYC documents: enough to recognise, not to identify ("Ganesh W********"). */
    static String kycName(String name) {
        if (name == null || name.isBlank()) {
            return name;
        }
        String[] words = name.trim().split("\\s+");
        StringBuilder out = new StringBuilder(words[0]);
        for (int i = 1; i < words.length; i++) {
            out.append(' ').append(words[i].charAt(0)).append("*".repeat(words[i].length() - 1));
        }
        return out.toString();
    }
}
