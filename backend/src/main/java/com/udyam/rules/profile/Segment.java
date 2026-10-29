package com.udyam.rules.profile;

import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Arrays;
import java.util.Optional;

/** The 4 segments of the dataset. code matches the catalogue's target_segments values. */
public enum Segment {
    MICRO("micro", "Micro retail & services"),
    PROF("prof", "Professional offices"),
    MULTI("multi", "Small multi-site businesses"),
    MFG("mfg", "Small manufacturers");

    private final String code;
    private final String displayName;

    Segment(String code, String displayName) {
        this.code = code;
        this.displayName = displayName;
    }

    public String code() {
        return code;
    }

    @JsonValue
    public String displayName() {
        return displayName;
    }

    /** Accepts the display name ("Professional offices"), the code ("prof") or the enum name, case-insensitive. */
    public static Optional<Segment> parse(String text) {
        if (text == null) {
            return Optional.empty();
        }
        String t = text.trim();
        return Arrays.stream(values())
                .filter(s -> s.displayName.equalsIgnoreCase(t) || s.code.equalsIgnoreCase(t) || s.name().equalsIgnoreCase(t))
                .findFirst();
    }
}
