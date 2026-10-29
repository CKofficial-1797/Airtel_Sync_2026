package com.udyam.rules.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Loads activated leads + their sold bundles from Neon and runs the MisSellingAuditor. */
@Service
@Transactional(readOnly = true)
public class MisSellingAuditService {

    private final JdbcTemplate jdbc;
    private final MisSellingAuditor auditor;

    public MisSellingAuditService(JdbcTemplate jdbc, MisSellingAuditor auditor) {
        this.jdbc = jdbc;
        this.auditor = auditor;
    }

    public MisSellingReport report() {
        return auditor.report(activatedBundles());
    }

    List<SoldBundle> activatedBundles() {
        Map<String, SoldBundle> byLead = new LinkedHashMap<>();
        Map<String, List<String>> products = new LinkedHashMap<>();
        jdbc.query("""
                SELECT l.lead_id, l.city_tier, l.employee_count, l.num_sites, l.has_gstin,
                       g.status AS registry_status, b.product_id
                FROM smb_lead l
                JOIN lead_bundle_item b ON b.lead_id = l.lead_id
                LEFT JOIN gstin_registry g ON g.gstin = l.gstin
                WHERE l.outcome = 'Activated'
                ORDER BY l.lead_id, b.product_id
                """, (RowCallbackHandler) rs -> {
            String leadId = rs.getString("lead_id");
            if (!byLead.containsKey(leadId)) {
                byLead.put(leadId, new SoldBundle(leadId, rs.getInt("city_tier"), rs.getInt("employee_count"),
                        rs.getInt("num_sites"), rs.getBoolean("has_gstin"), rs.getString("registry_status"), List.of()));
            }
            products.computeIfAbsent(leadId, k -> new ArrayList<>()).add(rs.getString("product_id"));
        });
        return byLead.values().stream()
                .map(b -> new SoldBundle(b.leadId(), b.cityTier(), b.employees(), b.sites(), b.hasGstin(),
                        b.registryStatus(), List.copyOf(products.get(b.leadId()))))
                .toList();
    }
}
