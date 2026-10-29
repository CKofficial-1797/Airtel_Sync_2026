package com.udyam.registry.validation;

import java.util.Map;

/**
 * Udyam state codes (letters after "UDYAM-") as used in mock_udyam_registry.csv. Derived from the
 * data, not from an official list (e.g. the dataset uses KR for Karnataka, TS for Telangana).
 * UdyamValidatorTest re-derives the mapping from the CSV and checks it equals this table.
 */
public final class UdyamStateCodes {

    private static final Map<String, String> NAMES = Map.ofEntries(
            Map.entry("AP", "Andhra Pradesh"),
            Map.entry("BR", "Bihar"),
            Map.entry("DL", "Delhi"),
            Map.entry("GJ", "Gujarat"),
            Map.entry("KL", "Kerala"),
            Map.entry("KR", "Karnataka"),
            Map.entry("MH", "Maharashtra"),
            Map.entry("MP", "Madhya Pradesh"),
            Map.entry("PB", "Punjab"),
            Map.entry("RJ", "Rajasthan"),
            Map.entry("TN", "Tamil Nadu"),
            Map.entry("TS", "Telangana"),
            Map.entry("UP", "Uttar Pradesh"),
            Map.entry("WB", "West Bengal"));

    private UdyamStateCodes() {
    }

    /** State name for a 2-letter code, or null if the code does not occur in the dataset. */
    public static String name(String code) {
        return code == null ? null : NAMES.get(code);
    }

    public static Map<String, String> all() {
        return NAMES;
    }
}
