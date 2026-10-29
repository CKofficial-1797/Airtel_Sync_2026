package com.udyam.leads;

import com.udyam.catalogue.ProductRepository;
import com.udyam.registry.GstinRegistryRepository;
import com.udyam.registry.UdyamRegistryRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Computes the Phase 1 data-verification summary (row counts, funnel, KPIs, integrity checks). */
@Service
@Transactional(readOnly = true)
public class DataSummaryService {

    /** Funnel stages in order; stage_reached is the LAST stage a lead reached. */
    public static final List<String> FUNNEL_STAGES =
            List.of("Lead", "Contacted", "Needs assessed", "Quote accepted", "KYC complete", "Activated");

    private final JdbcTemplate jdbc;
    private final ProductRepository products;
    private final GstinRegistryRepository gstins;
    private final UdyamRegistryRepository udyams;

    public DataSummaryService(JdbcTemplate jdbc, ProductRepository products,
                              GstinRegistryRepository gstins, UdyamRegistryRepository udyams) {
        this.jdbc = jdbc;
        this.products = products;
        this.gstins = gstins;
        this.udyams = udyams;
    }

    public DataSummary summary() {
        var rowCounts = new DataSummary.RowCounts(
                products.count(),
                gstins.count(),
                udyams.count(),
                count("SELECT count(*) FROM smb_lead"),
                count("SELECT count(*) FROM lead_bundle_item"));

        Map<String, Object> act = jdbc.queryForMap("""
                SELECT count(*)                      AS activated,
                       avg(days_lead_to_activation)  AS avg_days,
                       avg(customer_follow_up_calls) AS avg_calls
                FROM smb_lead
                WHERE outcome = 'Activated'
                """);
        long activated = ((Number) act.get("activated")).longValue();
        long totalCac = count("SELECT coalesce(sum(acquisition_cost_inr), 0) FROM smb_lead");
        BigDecimal blendedCac = activated == 0 ? null
                : BigDecimal.valueOf(totalCac).divide(BigDecimal.valueOf(activated), 2, RoundingMode.HALF_UP);

        var integrity = new DataSummary.Integrity(
                count("""
                        SELECT count(*) FROM smb_lead l
                        WHERE l.has_gstin
                          AND NOT EXISTS (SELECT 1 FROM gstin_registry g WHERE g.gstin = l.gstin)
                        """),
                count("""
                        SELECT count(*) FROM smb_lead l
                        WHERE l.has_udyam
                          AND NOT EXISTS (SELECT 1 FROM udyam_registry u WHERE u.udyam_number = l.udyam_number)
                        """));

        return new DataSummary(
                rowCounts,
                funnel(),
                activated,
                round2(act.get("avg_days")),
                round2(act.get("avg_calls")),
                totalCac,
                blendedCac,
                gstinStatusCounts(),
                integrity);
    }

    /** Converts "last stage reached" counts into "reached at least this stage" counts. */
    private List<DataSummary.FunnelStage> funnel() {
        Map<String, Long> byStage = new HashMap<>();
        jdbc.query("SELECT stage_reached, count(*) FROM smb_lead GROUP BY stage_reached",
                (RowCallbackHandler) rs -> byStage.put(rs.getString(1), rs.getLong(2)));
        for (String stage : byStage.keySet()) {
            if (!FUNNEL_STAGES.contains(stage)) {
                throw new IllegalStateException("Unknown stage_reached value: " + stage);
            }
        }

        List<DataSummary.FunnelStage> result = new ArrayList<>();
        long atLeast = 0;
        for (int i = FUNNEL_STAGES.size() - 1; i >= 0; i--) {
            atLeast += byStage.getOrDefault(FUNNEL_STAGES.get(i), 0L);
            result.add(0, new DataSummary.FunnelStage(FUNNEL_STAGES.get(i), atLeast));
        }
        return result;
    }

    private Map<String, Long> gstinStatusCounts() {
        Map<String, Long> counts = new LinkedHashMap<>();
        for (String status : List.of("Active", "Cancelled", "Suspended")) {
            counts.put(status, 0L);
        }
        for (Object[] row : gstins.countByStatus()) {
            counts.put((String) row[0], (Long) row[1]);
        }
        return counts;
    }

    private long count(String sql) {
        Long n = jdbc.queryForObject(sql, Long.class);
        return n == null ? 0 : n;
    }

    private static BigDecimal round2(Object value) {
        return value == null ? null : new BigDecimal(value.toString()).setScale(2, RoundingMode.HALF_UP);
    }
}
