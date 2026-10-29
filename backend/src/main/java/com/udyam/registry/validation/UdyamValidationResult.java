package com.udyam.registry.validation;

import java.util.List;

/** Outcome of UdyamValidator.validate. errors is empty when the format is valid. */
public record UdyamValidationResult(
        String normalized,
        boolean formatValid,
        String stateCode,
        String stateName,
        List<String> errors) {
}
