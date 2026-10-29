package com.udyam.registry;

import com.udyam.registry.validation.UdyamValidationResult;
import com.udyam.registry.validation.UdyamValidator;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** In-process Udyam validation + registry lookup (used by the mock API now, KYC later). */
@Service
@Transactional(readOnly = true)
public class UdyamLookupService {

    private final UdyamRegistryRepository repository;

    public UdyamLookupService(UdyamRegistryRepository repository) {
        this.repository = repository;
    }

    public UdyamValidationResult validate(String raw) {
        return UdyamValidator.validate(raw);
    }

    /** Exact registry lookup (normalized first). */
    public Optional<UdyamDetails> find(String raw) {
        String udyam = UdyamValidator.normalize(raw);
        return udyam.isEmpty() ? Optional.empty() : repository.findById(udyam).map(UdyamDetails::from);
    }
}
