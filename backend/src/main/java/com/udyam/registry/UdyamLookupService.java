package com.udyam.registry;

import com.udyam.registry.validation.UdyamValidationResult;
import com.udyam.registry.validation.UdyamValidator;
import java.util.Optional;
import org.springframework.stereotype.Service;

/** In-process Udyam validation + registry lookup (used by the mock API and the KYC checks). */
@Service
public class UdyamLookupService {

    private final UdyamRegistryLookup registry;

    public UdyamLookupService(UdyamRegistryLookup registry) {
        this.registry = registry;
    }

    public UdyamValidationResult validate(String raw) {
        return UdyamValidator.validate(raw);
    }

    /** Exact registry lookup (normalized first). */
    public Optional<UdyamDetails> find(String raw) {
        return registry.find(UdyamValidator.normalize(raw));
    }
}
