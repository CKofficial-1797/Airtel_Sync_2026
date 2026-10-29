package com.udyam.registry;

import java.util.Collection;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Read access to the GST registry by normalized GSTIN. The app uses JpaGstinRegistryLookup (Neon);
 * tests use CsvGstinRegistryLookup (in-memory, from mock_gstin_registry.csv).
 */
public interface GstinRegistryLookup {

    Optional<GstinDetails> find(String gstin);

    default boolean exists(String gstin) {
        return find(gstin).isPresent();
    }

    /** The subset of the given GSTINs that exist in the registry (one query for the JPA lookup). */
    default Set<String> findExisting(Collection<String> gstins) {
        return gstins.stream().filter(this::exists).collect(Collectors.toSet());
    }
}
