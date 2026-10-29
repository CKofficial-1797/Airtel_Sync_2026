package com.udyam.registry;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * GstinRegistryLookup backed by the gstin_registry table. The registry is read-only reference data
 * (3,534 rows), so it is loaded into memory once (on startup, after the DataLoader) and served from there:
 * each lookup would otherwise be a round trip to Neon.
 */
@Component
public class JpaGstinRegistryLookup implements GstinRegistryLookup {

    private static final Logger log = LoggerFactory.getLogger(JpaGstinRegistryLookup.class);

    private final GstinRegistryRepository repository;
    private volatile Map<String, GstinDetails> cache;

    public JpaGstinRegistryLookup(GstinRegistryRepository repository) {
        this.repository = repository;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        log.info("GST registry cached in memory: {} rows", registry().size());
    }

    @Override
    public Optional<GstinDetails> find(String gstin) {
        if (gstin == null || gstin.isEmpty()) {
            return Optional.empty();
        }
        return Optional.ofNullable(registry().get(gstin));
    }

    @Override
    public Set<String> findExisting(Collection<String> gstins) {
        Map<String, GstinDetails> registry = registry();
        return gstins.stream().filter(registry::containsKey).collect(Collectors.toSet());
    }

    private Map<String, GstinDetails> registry() {
        Map<String, GstinDetails> current = cache;
        if (current == null) {
            synchronized (this) {
                if (cache == null) {
                    Map<String, GstinDetails> loaded = repository.findAll().stream()
                            .map(GstinDetails::from)
                            .collect(Collectors.toUnmodifiableMap(GstinDetails::gstin, Function.identity()));
                    if (loaded.isEmpty()) {
                        return loaded; // table not loaded yet: don't cache an empty registry
                    }
                    cache = loaded;
                }
                current = cache;
            }
        }
        return current;
    }
}
