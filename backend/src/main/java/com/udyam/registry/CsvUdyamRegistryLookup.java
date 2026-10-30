package com.udyam.registry;

import static com.udyam.dataload.CsvParsing.text;
import static com.udyam.dataload.CsvParsing.toDate;

import com.udyam.dataload.CsvParsing;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.apache.commons.csv.CSVRecord;

/** In-memory UdyamRegistryLookup loaded from mock_udyam_registry.csv. For unit tests (no DB); not a Spring bean. */
public class CsvUdyamRegistryLookup implements UdyamRegistryLookup {

    private final Map<String, UdyamDetails> byNumber;

    private CsvUdyamRegistryLookup(Map<String, UdyamDetails> byNumber) {
        this.byNumber = byNumber;
    }

    public static CsvUdyamRegistryLookup fromCsv(Path file) {
        Map<String, UdyamDetails> map = new HashMap<>();
        for (CSVRecord r : CsvParsing.readAll(file)) {
            UdyamDetails d = new UdyamDetails(text(r.get("udyam_number")), text(r.get("enterprise_name")),
                    text(r.get("enterprise_type")), text(r.get("major_activity")), text(r.get("nic_description")),
                    text(r.get("state")), text(r.get("city")), toDate(r.get("date_of_registration")));
            map.put(d.udyamNumber(), d);
        }
        return new CsvUdyamRegistryLookup(map);
    }

    @Override
    public Optional<UdyamDetails> find(String udyamNumber) {
        return Optional.ofNullable(udyamNumber == null ? null : byNumber.get(udyamNumber));
    }
}
