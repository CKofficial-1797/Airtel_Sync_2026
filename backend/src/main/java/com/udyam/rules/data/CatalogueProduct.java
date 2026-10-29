package com.udyam.rules.data;

import static com.udyam.dataload.CsvParsing.ALT_LIST_SEPARATOR;
import static com.udyam.dataload.CsvParsing.LIST_SEPARATOR;

import com.udyam.catalogue.Product;
import com.udyam.dataload.CsvParsing;
import java.util.List;
import org.apache.commons.csv.CSVRecord;

/** Immutable in-memory copy of one catalogue product, used by the rules engine. */
public record CatalogueProduct(
        String productId,
        String category,
        String productName,
        String unit,
        int priceMinInr,
        int priceMaxInr,
        int setupFeeInr,
        int minContractMonths,
        List<Integer> cityTiers,
        int minEmployees,
        int minSites,
        boolean requiresGstin,
        List<String> requiresOneOf,
        int installDays,
        List<String> targetSegments) {

    public boolean hasDependencies() {
        return !requiresOneOf.isEmpty();
    }

    public static CatalogueProduct from(Product p) {
        return new CatalogueProduct(p.getProductId(), p.getCategory(), p.getProductName(), p.getUnit(),
                p.getPriceMinInrPerMonth(), p.getPriceMaxInrPerMonth(), p.getOneTimeSetupInr(),
                p.getMinContractMonths(), List.of(p.getAvailableCityTiers()), p.getMinEmployees(), p.getMinSites(),
                p.getRequiresGstin(), List.of(p.getRequiresOneOf()), p.getTypicalInstallDays(),
                List.of(p.getTargetSegments()));
    }

    public static CatalogueProduct from(CSVRecord r) {
        return new CatalogueProduct(
                CsvParsing.text(r.get("product_id")),
                CsvParsing.text(r.get("category")),
                CsvParsing.text(r.get("product_name")),
                CsvParsing.text(r.get("unit")),
                CsvParsing.toInt(r.get("price_min_inr_per_month")),
                CsvParsing.toInt(r.get("price_max_inr_per_month")),
                CsvParsing.toInt(r.get("one_time_setup_inr")),
                CsvParsing.toInt(r.get("min_contract_months")),
                CsvParsing.splitIntList(r.get("available_city_tiers"), LIST_SEPARATOR),
                CsvParsing.toInt(r.get("min_employees")),
                CsvParsing.toInt(r.get("min_sites")),
                CsvParsing.yesNo(r.get("requires_gstin")),
                CsvParsing.splitList(r.get("requires_one_of"), ALT_LIST_SEPARATOR),
                CsvParsing.toInt(r.get("typical_install_days")),
                CsvParsing.splitList(r.get("target_segments"), LIST_SEPARATOR));
    }
}
