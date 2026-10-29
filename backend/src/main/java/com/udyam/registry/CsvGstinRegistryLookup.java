package com.udyam.registry;

import static com.udyam.dataload.CsvParsing.text;
import static com.udyam.dataload.CsvParsing.toDate;

import com.udyam.dataload.CsvParsing;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.csv.CSVRecord;

/** In-memory GstinRegistryLookup loaded from mock_gstin_registry.csv. For unit tests (no DB); not a Spring bean. */
public class CsvGstinRegistryLookup implements GstinRegistryLookup {

    private final Map<String, GstinDetails> byGstin;

    private CsvGstinRegistryLookup(Map<String, GstinDetails> byGstin) {
        this.byGstin = byGstin;
    }

    public static CsvGstinRegistryLookup fromCsv(Path file) {
        Map<String, GstinDetails> map = new HashMap<>();
        for (CSVRecord r : CsvParsing.readAll(file)) {
            GstinDetails d = new GstinDetails(
                    text(r.get("gstin")),
                    text(r.get("legal_name")),
                    text(r.get("trade_name")),
                    text(r.get("constitution")),
                    text(r.get("principal_place_of_business")),
                    text(r.get("state")),
                    toDate(r.get("registration_date")),
                    text(r.get("status")));
            map.put(d.gstin(), d);
        }
        return new CsvGstinRegistryLookup(map);
    }

    @Override
    public Optional<GstinDetails> find(String gstin) {
        return Optional.ofNullable(gstin == null ? null : byGstin.get(gstin));
    }

    public int size() {
        return byGstin.size();
    }
}
