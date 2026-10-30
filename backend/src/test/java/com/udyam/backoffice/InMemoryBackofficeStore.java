package com.udyam.backoffice;

import com.udyam.TestData;
import com.udyam.advisor.AdvisorStore;
import com.udyam.common.privacy.Masking;
import com.udyam.dataload.BundleItem;
import com.udyam.dataload.CsvParsing;
import com.udyam.registry.GstinDetails;
import com.udyam.rules.RulesFixture;
import com.udyam.rules.audit.MisSellingReport;
import com.udyam.rules.audit.SoldBundle;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.apache.commons.csv.CSVRecord;

/** BackofficeStore for unit tests: the historical leads come from the provided CSV, the rest from what a test adds. */
class InMemoryBackofficeStore implements BackofficeStore {

    static final List<HistoricalLead> LEADS = CsvParsing.readAll(TestData.LEADS_CSV).stream().map(InMemoryBackofficeStore::lead).toList();
    private static final MisSellingReport MIS_SELLING = RulesFixture.AUDITOR.report(CsvParsing.readAll(TestData.LEADS_CSV).stream()
            .filter(r -> "Activated".equals(r.get("outcome")))
            .map(r -> new SoldBundle(r.get("lead_id"), Integer.parseInt(r.get("city_tier")), Integer.parseInt(r.get("employee_count")),
                    Integer.parseInt(r.get("num_sites")), "Y".equals(r.get("has_gstin")),
                    RulesFixture.REGISTRY.find(r.get("gstin")).map(GstinDetails::status).orElse(null),
                    CsvParsing.parseBundle(r.get("bundle_products")).stream().map(BundleItem::productId).toList()))
            .toList());

    final List<AdvisorStore.Session> sessions = new ArrayList<>();
    final List<OrderLead> orders = new ArrayList<>();
    final Map<String, Assignment> assignments = new LinkedHashMap<>();
    final Map<String, List<AssignmentEvent>> history = new LinkedHashMap<>();
    LiveFunnel liveFunnel = new LiveFunnel(0, 0, 0, 0, 0);

    private static HistoricalLead lead(CSVRecord r) {
        String gstin = CsvParsing.text(r.get("gstin"));
        LocalDate last = Stream.of("created_date", "contacted_date", "needs_assessed_date", "quote_accepted_date", "kyc_complete_date", "activated_date")
                .map(c -> CsvParsing.toDate(r.get(c))).filter(Objects::nonNull).max(Comparator.naturalOrder()).orElse(null);
        return new HistoricalLead(r.get("lead_id"), CsvParsing.toDate(r.get("created_date")), last, r.get("lead_source"), r.get("business_name"),
                r.get("business_type"), r.get("segment"), r.get("city"), Integer.parseInt(r.get("city_tier")), Integer.parseInt(r.get("employee_count")),
                Integer.parseInt(r.get("num_sites")), "Y".equals(r.get("has_gstin")), gstin == null ? null : Masking.gstin(gstin),
                gstin == null ? null : RulesFixture.REGISTRY.find(gstin).map(GstinDetails::status).orElse(null), "Y".equals(r.get("has_udyam")),
                Integer.parseInt(r.get("monthly_telecom_spend_inr")), r.get("current_internet"), CsvParsing.splitList(r.get("products_of_interest"), CsvParsing.LIST_SEPARATOR),
                r.get("stage_reached"), r.get("outcome"), CsvParsing.text(r.get("drop_reason")), Integer.parseInt(r.get("acquisition_cost_inr")),
                CsvParsing.toInt(r.get("monthly_bundle_value_inr")), CsvParsing.yesNo(r.get("churned_within_6_months")));
    }

    @Override
    public List<HistoricalLead> historicalLeads() {
        return LEADS;
    }

    @Override
    public List<AdvisorStore.Session> idleSessions(OffsetDateTime lastActiveBefore) {
        return sessions.stream().filter(s -> !s.updatedAt().isAfter(lastActiveBefore)).toList();
    }

    @Override
    public List<OrderLead> stuckOrders(Collection<String> statuses, OffsetDateTime lastActiveBefore) {
        return orders.stream().filter(o -> statuses.contains(o.status()) && !o.lastActivity().isAfter(lastActiveBefore)).toList();
    }

    @Override
    public Optional<OrderLead> orderLead(UUID orderId) {
        return orders.stream().filter(o -> o.orderId().equals(orderId)).findFirst();
    }

    @Override
    public Map<String, Assignment> assignments() {
        return assignments;
    }

    @Override
    public Optional<Assignment> assignment(String kind, String ref) {
        return Optional.ofNullable(assignments.get(BackofficeStore.key(kind, ref)));
    }

    @Override
    public Assignment saveAssignment(String kind, String ref, String channel, String status, String assigneeLabel, String note) {
        OffsetDateTime now = OffsetDateTime.now();
        Assignment a = new Assignment(kind, ref, channel, status, assigneeLabel, note, now, now);
        assignments.put(BackofficeStore.key(kind, ref), a);
        history.computeIfAbsent(BackofficeStore.key(kind, ref), k -> new ArrayList<>()).add(0, new AssignmentEvent(channel, status, assigneeLabel, note, now));
        return a;
    }

    @Override
    public List<AssignmentEvent> history(String kind, String ref) {
        return history.getOrDefault(BackofficeStore.key(kind, ref), List.of());
    }

    @Override
    public LiveFunnel liveFunnel() {
        return liveFunnel;
    }

    @Override
    public MisSellingReport misSelling() {
        return MIS_SELLING;
    }
}
