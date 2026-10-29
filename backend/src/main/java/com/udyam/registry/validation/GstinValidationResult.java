package com.udyam.registry.validation;

import java.util.List;

/** Outcome of GstinValidator.validate. errors is empty when the GSTIN is fully valid. */
public record GstinValidationResult(
        String normalized,
        boolean formatValid,
        boolean checksumValid,
        String stateCode,
        String stateName,
        List<String> errors) {

    /** Format and checksum both pass (says nothing about the registry). */
    public boolean valid() {
        return formatValid && checksumValid;
    }
}
