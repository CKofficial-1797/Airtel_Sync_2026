package com.udyam.dataload;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CsvParsingTest {

    @Test
    void yesNoMapsToBoolean() {
        assertThat(CsvParsing.yesNo("Y")).isTrue();
        assertThat(CsvParsing.yesNo("N")).isFalse();
        assertThat(CsvParsing.yesNo(" y ")).isTrue();
    }

    @Test
    void yesNoBlankIsNull() {
        assertThat(CsvParsing.yesNo("")).isNull();
        assertThat(CsvParsing.yesNo(null)).isNull();
    }

    @Test
    void yesNoRejectsOtherValues() {
        assertThatThrownBy(() -> CsvParsing.yesNo("Maybe"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Maybe");
    }

    @Test
    void splitsSemicolonLists() {
        assertThat(CsvParsing.splitList("micro;prof;multi", CsvParsing.LIST_SEPARATOR))
                .containsExactly("micro", "prof", "multi");
        assertThat(CsvParsing.splitIntList("1;2", CsvParsing.LIST_SEPARATOR)).containsExactly(1, 2);
        assertThat(CsvParsing.splitIntList("1", CsvParsing.LIST_SEPARATOR)).containsExactly(1);
    }

    @Test
    void splitsPipeLists() {
        assertThat(CsvParsing.splitList("P01|P02|P03", CsvParsing.ALT_LIST_SEPARATOR))
                .containsExactly("P01", "P02", "P03");
    }

    @Test
    void blankListIsEmpty() {
        assertThat(CsvParsing.splitList("", CsvParsing.ALT_LIST_SEPARATOR)).isEmpty();
        assertThat(CsvParsing.splitList(null, CsvParsing.LIST_SEPARATOR)).isEmpty();
    }

    @Test
    void blankScalarsAreNull() {
        assertThat(CsvParsing.text("  ")).isNull();
        assertThat(CsvParsing.toInt("")).isNull();
        assertThat(CsvParsing.toDate("")).isNull();
        assertThat(CsvParsing.toInt("42")).isEqualTo(42);
        assertThat(CsvParsing.toDate("2026-07-15")).isEqualTo(LocalDate.of(2026, 7, 15));
    }

    @Test
    void parsesBundle() {
        List<BundleItem> items = CsvParsing.parseBundle("P01x1;P07x2");
        assertThat(items).containsExactly(new BundleItem("P01", 1), new BundleItem("P07", 2));
        assertThat(CsvParsing.parseBundle("P02x11;P09x11")).extracting(BundleItem::quantity).containsExactly(11, 11);
    }

    @Test
    void blankBundleIsEmpty() {
        assertThat(CsvParsing.parseBundle("")).isEmpty();
        assertThat(CsvParsing.parseBundle(null)).isEmpty();
    }

    @Test
    void malformedBundleFailsClearly() {
        assertThatThrownBy(() -> CsvParsing.parseBundle("P01x1;P07"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Malformed bundle item 'P07'");
        assertThatThrownBy(() -> CsvParsing.parseBundle("P01*2"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CsvParsing.parseBundle("P01x0"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> CsvParsing.parseBundle("P01x1;P01x2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("twice");
    }

    @Test
    void readAllHandlesQuotedCommasAndBom(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("sample.csv");
        Files.writeString(file, "﻿id,address\nA1,\"Shop 1, Station Road, \"\"Main\"\" Gate\"\n",
                StandardCharsets.UTF_8);

        List<CSVRecord> records = CsvParsing.readAll(file);

        assertThat(records).hasSize(1);
        assertThat(records.get(0).get("id")).isEqualTo("A1");
        assertThat(records.get(0).get("address")).isEqualTo("Shop 1, Station Road, \"Main\" Gate");
    }
}
