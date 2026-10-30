package com.udyam.kyc.checks;

import java.util.Locale;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Business / person name comparison that tolerates formatting and OCR noise but not a different name.
 * <ol>
 *   <li>normalize: lowercase, "&amp;" → "and", punctuation → space, collapse spaces,
 *       unify suffixes ("private limited" → "pvt ltd", "limited liability partnership" → "llp")</li>
 *   <li>same number of tokens, compared in order</li>
 *   <li>a token matches when equal, or (both ≥ 6 letters) at Levenshtein distance ≤ 1</li>
 * </ol>
 * So "Kaveri Stays LLP" = "Kaveri Stays Llp", but "Shri Physio Clinic" ≠ "Neelgiri Physio Clinic".
 */
public final class NameMatcher {

    static final int FUZZY_MIN_LENGTH = 6;

    private static final Pattern PROPRIETOR = Pattern.compile("^(.*?)\\s*\\(\\s*prop\\.?\\s*(.+?)\\s*\\)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");

    private NameMatcher() {
    }

    /** "Neha Kaur (Prop. Vindhya Tours &amp; Travels)" → proprietor "Neha Kaur", business "Vindhya Tours &amp; Travels". */
    public record ProprietorName(String proprietor, String business) {
    }

    public static Optional<ProprietorName> splitProprietor(String legalName) {
        if (legalName == null) {
            return Optional.empty();
        }
        Matcher m = PROPRIETOR.matcher(legalName.trim());
        return m.matches() && !m.group(1).isBlank()
                ? Optional.of(new ProprietorName(m.group(1).trim(), m.group(2).trim()))
                : Optional.empty();
    }

    public static String normalize(String name) {
        if (name == null) {
            return "";
        }
        String s = name.toLowerCase(Locale.ROOT).replace("&", " and ");
        s = NON_ALNUM.matcher(s).replaceAll(" ").trim().replaceAll("\\s+", " ");
        s = (" " + s + " ")
                .replace(" limited liability partnership ", " llp ")
                .replace(" private limited ", " pvt ltd ")
                .replace(" private ltd ", " pvt ltd ")
                .replace(" pvt limited ", " pvt ltd ")
                .replace(" limited ", " ltd ");
        return s.trim();
    }

    public static boolean matches(String a, String b) {
        String na = normalize(a);
        String nb = normalize(b);
        if (na.isEmpty() || nb.isEmpty()) {
            return false;
        }
        String[] ta = na.split(" ");
        String[] tb = nb.split(" ");
        if (ta.length != tb.length) {
            return false;
        }
        for (int i = 0; i < ta.length; i++) {
            if (!tokenMatches(ta[i], tb[i])) {
                return false;
            }
        }
        return true;
    }

    static boolean tokenMatches(String a, String b) {
        if (a.equals(b)) {
            return true;
        }
        return a.length() >= FUZZY_MIN_LENGTH && b.length() >= FUZZY_MIN_LENGTH && levenshtein(a, b) <= 1;
    }

    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] tmp = prev;
            prev = cur;
            cur = tmp;
        }
        return prev[b.length()];
    }
}
