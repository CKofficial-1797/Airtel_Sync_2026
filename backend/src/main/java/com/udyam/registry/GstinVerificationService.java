package com.udyam.registry;

import com.udyam.registry.validation.GstStateCodes;
import com.udyam.registry.validation.GstinValidationResult;
import com.udyam.registry.validation.GstinValidator;
import java.util.Optional;
import org.springframework.stereotype.Service;

/**
 * Single in-process entry point for GSTIN checks (rules engine, advisor and KYC call this directly).
 * validate → registry lookup → OCR correction when the input is not found as-is.
 */
@Service
public class GstinVerificationService {

    private final GstinRegistryLookup registry;
    private final GstinCorrectionService correctionService;

    public GstinVerificationService(GstinRegistryLookup registry, GstinCorrectionService correctionService) {
        this.registry = registry;
        this.correctionService = correctionService;
    }

    public GstinValidationResult validate(String raw) {
        return GstinValidator.validate(raw);
    }

    /** Exact registry lookup by GSTIN (normalized first, no correction). */
    public Optional<GstinDetails> find(String raw) {
        return registry.find(GstinValidator.normalize(raw));
    }

    public GstinVerification verify(String raw) {
        GstinValidationResult v = GstinValidator.validate(raw);

        Optional<GstinDetails> details = v.valid() ? registry.find(v.normalized()) : Optional.empty();
        GstinCorrection correction = null;
        if (details.isEmpty()) {
            GstinCorrection attempt = correctionService.correct(v.normalized());
            if (attempt.corrected()) {
                correction = attempt;
                details = registry.find(attempt.correctedGstin());
            }
        }

        if (details.isPresent()) {
            GstinDetails d = details.get();
            String stateCode = d.gstin().substring(0, 2);
            return new GstinVerification(raw, v.normalized(), v.formatValid(), v.checksumValid(), true, d.gstin(),
                    d.status(), d.active(), stateCode, GstStateCodes.name(stateCode), correction, v.errors());
        }
        return new GstinVerification(raw, v.normalized(), v.formatValid(), v.checksumValid(), false, null,
                null, false, v.stateCode(), v.stateName(), null, v.errors());
    }
}
