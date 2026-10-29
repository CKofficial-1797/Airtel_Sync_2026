package com.udyam.common.config;

import java.time.LocalDate;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Typed view of the "app.*" keys in application.yml.
 *
 * @param dataDir       folder holding the provided CSVs (relative paths resolve against the working directory)
 * @param referenceDate the date treated as "today" for all date-based checks
 * @param cors          CORS settings for the future frontend
 */
@ConfigurationProperties(prefix = "app")
public record AppProperties(String dataDir, LocalDate referenceDate, Cors cors) {

    public record Cors(List<String> allowedOrigins) {
    }
}
