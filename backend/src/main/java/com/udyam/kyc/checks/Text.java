package com.udyam.kyc.checks;

/** One message in English and Hindi (the UI shows the owner's language). */
public record Text(String en, String hi) {

    public static Text of(String en, String hi) {
        return new Text(en, hi);
    }
}
