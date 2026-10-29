package com.udyam.dataload;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;

/**
 * Pure, DB-free helpers for reading the provided CSVs. Reusable from unit tests in any phase.
 * Conventions: blank cell = null, Y/N = boolean, lists use ';' (requires_one_of uses '|').
 */
public final class CsvParsing {

    public static final String LIST_SEPARATOR = ";";
    public static final String ALT_LIST_SEPARATOR = "|";

    private static final CSVFormat FORMAT = CSVFormat.DEFAULT.builder()
            .setHeader()
            .setSkipHeaderRecord(true)
            .setTrim(true)
            .get();

    private static final Pattern BUNDLE_ITEM = Pattern.compile("^(P\\d{2,})x(\\d+)$");

    private CsvParsing() {
    }

    /** Reads a whole CSV with a header row; quoted fields with commas/quotes are handled by Commons CSV. */
    public static List<CSVRecord> readAll(Path file) {
        try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            reader.mark(1);
            if (reader.read() != '﻿') {
                reader.reset();
            }
            try (CSVParser parser = FORMAT.parse(reader)) {
                return parser.getRecords();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read CSV file " + file, e);
        }
    }

    /** Blank or null → null, otherwise the trimmed value. */
    public static String text(String value) {
        return isBlank(value) ? null : value.trim();
    }

    /** Blank → null, otherwise the integer value. */
    public static Integer toInt(String value) {
        return isBlank(value) ? null : Integer.valueOf(value.trim());
    }

    /** Blank → null, otherwise an ISO date (yyyy-MM-dd). */
    public static LocalDate toDate(String value) {
        return isBlank(value) ? null : LocalDate.parse(value.trim());
    }

    /** "Y" → true, "N" → false, blank → null; anything else is rejected. */
    public static Boolean yesNo(String value) {
        if (isBlank(value)) {
            return null;
        }
        return switch (value.trim().toUpperCase()) {
            case "Y" -> true;
            case "N" -> false;
            default -> throw new IllegalArgumentException("Expected Y or N but got '" + value + "'");
        };
    }

    /** Splits "a;b" (or "a|b") into trimmed parts; blank → empty list. */
    public static List<String> splitList(String value, String separator) {
        if (isBlank(value)) {
            return List.of();
        }
        return Arrays.stream(value.split(Pattern.quote(separator)))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
    }

    /** Splits "1;2" into integers; blank → empty list. */
    public static List<Integer> splitIntList(String value, String separator) {
        return splitList(value, separator).stream().map(Integer::valueOf).toList();
    }

    /**
     * Parses bundle_products such as "P01x1;P07x2" into items. Blank → empty list.
     *
     * @throws IllegalArgumentException on a malformed entry, a zero quantity or a repeated product
     */
    public static List<BundleItem> parseBundle(String value) {
        List<BundleItem> items = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (String part : splitList(value, LIST_SEPARATOR)) {
            Matcher m = BUNDLE_ITEM.matcher(part);
            if (!m.matches()) {
                throw new IllegalArgumentException(
                        "Malformed bundle item '" + part + "' in '" + value + "'; expected format like P01x1;P07x2");
            }
            String productId = m.group(1);
            int quantity = Integer.parseInt(m.group(2));
            if (quantity <= 0) {
                throw new IllegalArgumentException("Bundle item '" + part + "' must have a quantity above 0");
            }
            if (!seen.add(productId)) {
                throw new IllegalArgumentException("Product " + productId + " appears twice in bundle '" + value + "'");
            }
            items.add(new BundleItem(productId, quantity));
        }
        return items;
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
