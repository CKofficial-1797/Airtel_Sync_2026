package com.udyam.registry;

import java.util.Optional;

/** Read access to the Udyam registry by normalized number. App: JpaUdyamRegistryLookup; tests: CsvUdyamRegistryLookup. */
public interface UdyamRegistryLookup {

    Optional<UdyamDetails> find(String udyamNumber);
}
