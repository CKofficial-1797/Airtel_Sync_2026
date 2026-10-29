package com.udyam.registry;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/** GstinRegistryLookup backed by the gstin_registry table. */
@Component
@Transactional(readOnly = true)
public class JpaGstinRegistryLookup implements GstinRegistryLookup {

    private final GstinRegistryRepository repository;

    public JpaGstinRegistryLookup(GstinRegistryRepository repository) {
        this.repository = repository;
    }

    @Override
    public Optional<GstinDetails> find(String gstin) {
        if (gstin == null || gstin.isEmpty()) {
            return Optional.empty();
        }
        return repository.findById(gstin).map(GstinDetails::from);
    }

    @Override
    public Set<String> findExisting(Collection<String> gstins) {
        if (gstins.isEmpty()) {
            return Set.of();
        }
        return repository.findAllById(gstins).stream().map(GstinRecord::getGstin).collect(Collectors.toSet());
    }
}
