package com.udyam.kyc.checks;

import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Address comparison: the 6-digit pincodes must be equal AND at least 70% of the street tokens must overlap
 * (overlap = shared tokens / tokens of the longer address, pincode excluded).
 */
public final class AddressMatcher {

    public static final double MIN_TOKEN_OVERLAP = 0.70;

    private static final Pattern PINCODE = Pattern.compile("(?<!\\d)(\\d{6})(?!\\d)");
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern TRAILING_PINCODE = Pattern.compile("\\s*-?\\s*\\d{6}\\s*$");

    private AddressMatcher() {
    }

    public record AddressMatch(boolean matches, String pincodeA, String pincodeB, double tokenOverlap) {
    }

    public static AddressMatch compare(String a, String b) {
        String pinA = pincode(a).orElse(null);
        String pinB = pincode(b).orElse(null);
        Set<String> ta = streetTokens(a);
        Set<String> tb = streetTokens(b);
        int longer = Math.max(ta.size(), tb.size());
        double overlap = 0;
        if (longer > 0) {
            Set<String> shared = new LinkedHashSet<>(ta);
            shared.retainAll(tb);
            overlap = Math.round(1000.0 * shared.size() / longer) / 1000.0;
        }
        boolean matches = pinA != null && pinA.equals(pinB) && overlap >= MIN_TOKEN_OVERLAP;
        return new AddressMatch(matches, pinA, pinB, overlap);
    }

    /** The last 6-digit number in the address. */
    public static Optional<String> pincode(String address) {
        if (address == null) {
            return Optional.empty();
        }
        Matcher m = PINCODE.matcher(address);
        String last = null;
        while (m.find()) {
            last = m.group(1);
        }
        return Optional.ofNullable(last);
    }

    /** The state is the last comma-separated part before the pincode: "..., Nagpur, Maharashtra - 411231". */
    public static Optional<String> state(String address) {
        if (address == null) {
            return Optional.empty();
        }
        String body = TRAILING_PINCODE.matcher(address.trim()).replaceAll("");
        String[] parts = body.split(",");
        String last = parts[parts.length - 1].trim();
        return last.isEmpty() || parts.length < 2 ? Optional.empty() : Optional.of(last);
    }

    static Set<String> streetTokens(String address) {
        if (address == null) {
            return Set.of();
        }
        String s = NON_ALNUM.matcher(address.toLowerCase(Locale.ROOT).replace("&", " and ")).replaceAll(" ").trim();
        Set<String> tokens = new LinkedHashSet<>(Arrays.asList(s.split("\\s+")));
        tokens.removeIf(t -> t.isEmpty() || PINCODE.matcher(t).matches());
        return tokens;
    }
}
