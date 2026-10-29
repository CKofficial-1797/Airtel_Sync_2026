package com.udyam.dataload;

import static com.udyam.dataload.CsvParsing.ALT_LIST_SEPARATOR;
import static com.udyam.dataload.CsvParsing.LIST_SEPARATOR;
import static com.udyam.dataload.CsvParsing.parseBundle;
import static com.udyam.dataload.CsvParsing.splitIntList;
import static com.udyam.dataload.CsvParsing.splitList;
import static com.udyam.dataload.CsvParsing.text;
import static com.udyam.dataload.CsvParsing.toDate;
import static com.udyam.dataload.CsvParsing.toInt;
import static com.udyam.dataload.CsvParsing.yesNo;

import com.udyam.common.config.AppProperties;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Types;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Loads the provided CSVs into Neon on startup. Each table is loaded only when it is empty,
 * in one transaction, using JDBC batch inserts. Logs counts only (no identifiers or names).
 */
@Component
public class DataLoader implements ApplicationRunner {

    public static final String PRODUCT_FILE = "product_catalogue.csv";
    public static final String GSTIN_FILE = "mock_gstin_registry.csv";
    public static final String UDYAM_FILE = "mock_udyam_registry.csv";
    public static final String LEADS_FILE = "smb_leads.csv";

    private static final int BATCH_SIZE = 500;
    private static final Logger log = LoggerFactory.getLogger(DataLoader.class);

    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final AppProperties props;

    public DataLoader(JdbcTemplate jdbc, TransactionTemplate tx, AppProperties props) {
        this.jdbc = jdbc;
        this.tx = tx;
        this.props = props;
    }

    /** Resolves app.data-dir against the working directory. */
    public static Path resolveDataDir(String dataDir) {
        return Path.of(dataDir).toAbsolutePath().normalize();
    }

    @Override
    public void run(ApplicationArguments args) {
        Path dir = resolveDataDir(props.dataDir());
        for (String file : List.of(PRODUCT_FILE, GSTIN_FILE, UDYAM_FILE, LEADS_FILE)) {
            if (!Files.isRegularFile(dir.resolve(file))) {
                throw new IllegalStateException("Required data file missing: " + dir.resolve(file)
                        + " (check app.data-dir, currently '" + props.dataDir() + "')");
            }
        }

        long start = System.currentTimeMillis();
        log.info("Data load: reading CSVs from {}", dir);
        loadIfEmpty("product", () -> loadProducts(dir.resolve(PRODUCT_FILE)));
        loadIfEmpty("gstin_registry", () -> loadGstins(dir.resolve(GSTIN_FILE)));
        loadIfEmpty("udyam_registry", () -> loadUdyams(dir.resolve(UDYAM_FILE)));
        loadIfEmpty("smb_lead", () -> loadLeads(dir.resolve(LEADS_FILE)));
        loadIfEmpty("lead_bundle_item", () -> loadBundleItems(dir.resolve(LEADS_FILE)));
        log.info("Data load: finished in {} ms", System.currentTimeMillis() - start);
    }

    private void loadIfEmpty(String table, Supplier<Integer> loader) {
        Long existing = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        if (existing != null && existing > 0) {
            log.info("Data load: {} skipped (already has {} rows)", table, existing);
            return;
        }
        long start = System.currentTimeMillis();
        Integer loaded = tx.execute(status -> loader.get());
        log.info("Data load: {} loaded {} rows in {} ms", table, loaded, System.currentTimeMillis() - start);
    }

    private int loadProducts(Path file) {
        List<Object[]> rows = new ArrayList<>();
        for (CSVRecord r : CsvParsing.readAll(file)) {
            rows.add(new Object[] {
                    text(r.get("product_id")),
                    text(r.get("category")),
                    text(r.get("product_name")),
                    text(r.get("description")),
                    text(r.get("unit")),
                    toInt(r.get("price_min_inr_per_month")),
                    toInt(r.get("price_max_inr_per_month")),
                    toInt(r.get("one_time_setup_inr")),
                    toInt(r.get("min_contract_months")),
                    splitIntList(r.get("available_city_tiers"), LIST_SEPARATOR).toArray(Integer[]::new),
                    toInt(r.get("min_employees")),
                    toInt(r.get("min_sites")),
                    yesNo(r.get("requires_gstin")),
                    splitList(r.get("requires_one_of"), ALT_LIST_SEPARATOR).toArray(String[]::new),
                    toInt(r.get("typical_install_days")),
                    splitList(r.get("target_segments"), LIST_SEPARATOR).toArray(String[]::new)
            });
        }
        return batchInsert("""
                INSERT INTO product (product_id, category, product_name, description, unit,
                    price_min_inr_per_month, price_max_inr_per_month, one_time_setup_inr, min_contract_months,
                    available_city_tiers, min_employees, min_sites, requires_gstin, requires_one_of,
                    typical_install_days, target_segments)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);
    }

    private int loadGstins(Path file) {
        List<Object[]> rows = new ArrayList<>();
        for (CSVRecord r : CsvParsing.readAll(file)) {
            rows.add(new Object[] {
                    text(r.get("gstin")),
                    text(r.get("legal_name")),
                    text(r.get("trade_name")),
                    text(r.get("constitution")),
                    text(r.get("principal_place_of_business")),
                    text(r.get("state")),
                    toDate(r.get("registration_date")),
                    text(r.get("status"))
            });
        }
        return batchInsert("""
                INSERT INTO gstin_registry (gstin, legal_name, trade_name, constitution,
                    principal_place_of_business, state, registration_date, status)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);
    }

    private int loadUdyams(Path file) {
        List<Object[]> rows = new ArrayList<>();
        for (CSVRecord r : CsvParsing.readAll(file)) {
            rows.add(new Object[] {
                    text(r.get("udyam_number")),
                    text(r.get("enterprise_name")),
                    text(r.get("enterprise_type")),
                    text(r.get("major_activity")),
                    text(r.get("nic_description")),
                    text(r.get("state")),
                    text(r.get("city")),
                    toDate(r.get("date_of_registration"))
            });
        }
        return batchInsert("""
                INSERT INTO udyam_registry (udyam_number, enterprise_name, enterprise_type, major_activity,
                    nic_description, state, city, date_of_registration)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);
    }

    private int loadLeads(Path file) {
        List<Object[]> rows = new ArrayList<>();
        for (CSVRecord r : CsvParsing.readAll(file)) {
            rows.add(new Object[] {
                    text(r.get("lead_id")),
                    toDate(r.get("created_date")),
                    text(r.get("lead_source")),
                    text(r.get("business_name")),
                    text(r.get("business_type")),
                    text(r.get("segment")),
                    text(r.get("city")),
                    toInt(r.get("city_tier")),
                    text(r.get("state")),
                    text(r.get("preferred_language")),
                    toInt(r.get("employee_count")),
                    toInt(r.get("num_sites")),
                    text(r.get("annual_turnover_band")),
                    yesNo(r.get("has_gstin")),
                    text(r.get("gstin")),
                    yesNo(r.get("has_udyam")),
                    text(r.get("udyam_number")),
                    text(r.get("current_internet")),
                    text(r.get("current_provider")),
                    toInt(r.get("monthly_telecom_spend_inr")),
                    splitList(r.get("products_of_interest"), LIST_SEPARATOR).toArray(String[]::new),
                    text(r.get("contact_name")),
                    text(r.get("contact_role")),
                    text(r.get("stage_reached")),
                    text(r.get("outcome")),
                    text(r.get("drop_reason")),
                    toDate(r.get("contacted_date")),
                    toDate(r.get("needs_assessed_date")),
                    toDate(r.get("quote_accepted_date")),
                    toDate(r.get("kyc_complete_date")),
                    toDate(r.get("activated_date")),
                    toInt(r.get("days_lead_to_activation")),
                    toInt(r.get("customer_follow_up_calls")),
                    text(r.get("bundle_products")),
                    toInt(r.get("monthly_bundle_value_inr")),
                    toInt(r.get("one_time_setup_inr")),
                    toInt(r.get("contract_months")),
                    yesNo(r.get("churned_within_6_months")),
                    toInt(r.get("nps_score")),
                    toInt(r.get("acquisition_cost_inr"))
            });
        }
        return batchInsert("""
                INSERT INTO smb_lead (lead_id, created_date, lead_source, business_name, business_type, segment,
                    city, city_tier, state, preferred_language, employee_count, num_sites, annual_turnover_band,
                    has_gstin, gstin, has_udyam, udyam_number, current_internet, current_provider,
                    monthly_telecom_spend_inr, products_of_interest, contact_name, contact_role, stage_reached,
                    outcome, drop_reason, contacted_date, needs_assessed_date, quote_accepted_date,
                    kyc_complete_date, activated_date, days_lead_to_activation, customer_follow_up_calls,
                    bundle_products, monthly_bundle_value_inr, one_time_setup_inr, contract_months,
                    churned_within_6_months, nps_score, acquisition_cost_inr)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, rows);
    }

    private int loadBundleItems(Path file) {
        Set<String> productIds = new HashSet<>(jdbc.queryForList("SELECT product_id FROM product", String.class));
        List<Object[]> rows = new ArrayList<>();
        for (CSVRecord r : CsvParsing.readAll(file)) {
            String leadId = text(r.get("lead_id"));
            for (BundleItem item : parseBundle(r.get("bundle_products"))) {
                if (!productIds.contains(item.productId())) {
                    throw new IllegalStateException(
                            "Lead " + leadId + " bundle references unknown product " + item.productId());
                }
                rows.add(new Object[] {leadId, item.productId(), item.quantity()});
            }
        }
        return batchInsert("INSERT INTO lead_bundle_item (lead_id, product_id, quantity) VALUES (?, ?, ?)", rows);
    }

    private int batchInsert(String sql, List<Object[]> rows) {
        jdbc.batchUpdate(sql, rows, BATCH_SIZE, (ps, row) -> {
            for (int i = 0; i < row.length; i++) {
                bind(ps, i + 1, row[i]);
            }
        });
        return rows.size();
    }

    private static void bind(PreparedStatement ps, int index, Object value) throws SQLException {
        Connection con = ps.getConnection();
        switch (value) {
            case null -> ps.setNull(index, Types.NULL);
            case Integer[] ints -> ps.setArray(index, con.createArrayOf("int4", ints));
            case String[] strings -> ps.setArray(index, con.createArrayOf("text", strings));
            default -> ps.setObject(index, value);
        }
    }
}
