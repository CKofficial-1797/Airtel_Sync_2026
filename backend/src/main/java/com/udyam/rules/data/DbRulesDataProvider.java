package com.udyam.rules.data;

import com.udyam.catalogue.ProductRepository;
import com.udyam.common.errors.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Loads the rules snapshot from Neon once, after startup (ApplicationReadyEvent fires after the
 * DataLoader has run, so the tables are populated even on a fresh database).
 */
@Component
public class DbRulesDataProvider implements RulesDataProvider {

    private static final Logger log = LoggerFactory.getLogger(DbRulesDataProvider.class);

    private final ProductRepository products;
    private final JdbcTemplate jdbc;
    private volatile RulesData data;

    public DbRulesDataProvider(ProductRepository products, JdbcTemplate jdbc) {
        this.products = products;
        this.jdbc = jdbc;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Order(0) // before anything else that waits for ready and needs the rules
    public void load() {
        Catalogue catalogue = new Catalogue(products.findAll().stream().map(CatalogueProduct::from).toList());
        var cities = jdbc.query("SELECT DISTINCT city, city_tier, state FROM smb_lead",
                (rs, i) -> new CityRef(rs.getString(1), rs.getInt(2), rs.getString(3)));
        var types = jdbc.query("SELECT DISTINCT business_type, segment FROM smb_lead",
                (rs, i) -> new BusinessTypeRef(rs.getString(1), rs.getString(2)));
        data = new RulesData(catalogue, cities, types);
        log.info("Rules data loaded: {} products, {} cities, {} business types",
                catalogue.size(), data.cities().size(), data.businessTypes().size());
    }

    @Override
    public RulesData get() {
        RulesData current = data;
        if (current == null) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "RULES_DATA_NOT_READY",
                    "Rules data is still loading; retry in a few seconds");
        }
        return current;
    }
}
