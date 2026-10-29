package com.udyam;

import com.udyam.dataload.DataLoader;
import java.nio.file.Path;

/** Paths to the provided CSVs for DB-free tests (Maven runs tests with backend/ as the working dir). */
public final class TestData {

    public static final Path DATA_DIR = DataLoader.resolveDataDir("../data");
    public static final Path GSTIN_CSV = DATA_DIR.resolve(DataLoader.GSTIN_FILE);
    public static final Path UDYAM_CSV = DATA_DIR.resolve(DataLoader.UDYAM_FILE);
    public static final Path PRODUCT_CSV = DATA_DIR.resolve(DataLoader.PRODUCT_FILE);
    public static final Path LEADS_CSV = DATA_DIR.resolve(DataLoader.LEADS_FILE);

    private TestData() {
    }
}
