package com.udyam.rules.data;

import com.udyam.dataload.CsvParsing;
import com.udyam.dataload.DataLoader;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.apache.commons.csv.CSVRecord;

/**
 * Immutable snapshot the rules engine works on: the catalogue plus the city → tier/state and
 * business type → segment mappings derived from smb_leads. Fails fast if a mapping is ambiguous.
 */
public final class RulesData {

    private final Catalogue catalogue;
    private final Map<String, CityRef> citiesByKey = new LinkedHashMap<>();
    private final Map<String, BusinessTypeRef> typesByKey = new LinkedHashMap<>();

    public RulesData(Catalogue catalogue, Collection<CityRef> cityRows, Collection<BusinessTypeRef> typeRows) {
        this.catalogue = catalogue;
        cityRows.stream().sorted(Comparator.comparing(CityRef::city)).forEach(c -> {
            CityRef previous = citiesByKey.putIfAbsent(key(c.city()), c);
            if (previous != null && !previous.equals(c)) {
                throw new IllegalStateException("City " + c.city() + " maps to more than one tier/state");
            }
        });
        typeRows.stream().sorted(Comparator.comparing(BusinessTypeRef::businessType)).forEach(t -> {
            BusinessTypeRef previous = typesByKey.putIfAbsent(key(t.businessType()), t);
            if (previous != null && !previous.equals(t)) {
                throw new IllegalStateException("Business type " + t.businessType() + " maps to more than one segment");
            }
        });
    }

    /** Builds the same snapshot from the CSVs (tests, no DB). */
    public static RulesData fromCsv(Path dataDir) {
        List<CatalogueProduct> products = CsvParsing.readAll(dataDir.resolve(DataLoader.PRODUCT_FILE)).stream()
                .map(CatalogueProduct::from).toList();
        Set<CityRef> cities = new LinkedHashSet<>();
        Set<BusinessTypeRef> types = new LinkedHashSet<>();
        for (CSVRecord r : CsvParsing.readAll(dataDir.resolve(DataLoader.LEADS_FILE))) {
            cities.add(new CityRef(r.get("city"), Integer.parseInt(r.get("city_tier")), r.get("state")));
            types.add(new BusinessTypeRef(r.get("business_type"), r.get("segment")));
        }
        return new RulesData(new Catalogue(products), cities, types);
    }

    public Catalogue catalogue() {
        return catalogue;
    }

    public List<CityRef> cities() {
        return List.copyOf(citiesByKey.values());
    }

    public List<BusinessTypeRef> businessTypes() {
        return List.copyOf(typesByKey.values());
    }

    public Optional<CityRef> city(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(citiesByKey.get(key(name)));
    }

    public Optional<BusinessTypeRef> businessType(String name) {
        return name == null ? Optional.empty() : Optional.ofNullable(typesByKey.get(key(name)));
    }

    private static String key(String value) {
        return value.trim().toLowerCase(Locale.ROOT);
    }
}
