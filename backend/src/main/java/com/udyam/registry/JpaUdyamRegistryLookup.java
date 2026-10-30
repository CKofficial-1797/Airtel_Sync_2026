package com.udyam.registry;

import java.util.Optional;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** UdyamRegistryLookup backed by the udyam_registry table. */
@Component
@Transactional(readOnly = true)
public class JpaUdyamRegistryLookup implements UdyamRegistryLookup {

    private final UdyamRegistryRepository repository;

    public JpaUdyamRegistryLookup(UdyamRegistryRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<UdyamDetails> find(String udyamNumber) {
        if (udyamNumber == null || udyamNumber.isBlank()) {
            return Optional.empty();
        }
        return repository.findById(udyamNumber).map(UdyamDetails::from);
    }
}
