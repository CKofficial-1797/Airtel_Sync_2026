package com.udyam.registry;

import java.util.List;

/**
 * Result of GstinCorrectionService.correct. When corrected is false, correctedGstin is null and
 * substitutions is empty (either the input was already valid and registered, or no unique fix exists).
 */
public record GstinCorrection(
        String original,
        String correctedGstin,
        boolean corrected,
        List<Substitution> substitutions) {

    /** One OCR fix: 0-based position in the 15-character GSTIN, character read, character used. */
    public record Substitution(int position, char from, char to) {
    }

    static GstinCorrection none(String original) {
        return new GstinCorrection(original, null, false, List.of());
    }
}
