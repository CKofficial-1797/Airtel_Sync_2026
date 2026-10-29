package com.udyam.registry;

import java.util.List;

/**
 * Result of GstinVerificationService.verify (also the POST /api/mock/gstin/verify response).
 * <ul>
 *   <li>formatValid, checksumValid, errors: describe the input as given (after normalization).</li>
 *   <li>effectiveGstin: the registry GSTIN this input resolves to (the input itself, or the
 *       OCR-corrected value), or null when nothing was found.</li>
 *   <li>found, status, active, stateCode, stateName: describe effectiveGstin when found,
 *       otherwise come from the input.</li>
 *   <li>correction: non-null only when an OCR correction was applied.</li>
 * </ul>
 */
public record GstinVerification(
        String input,
        String normalized,
        boolean formatValid,
        boolean checksumValid,
        boolean found,
        String effectiveGstin,
        String status,
        boolean active,
        String stateCode,
        String stateName,
        GstinCorrection correction,
        List<String> errors) {
}
