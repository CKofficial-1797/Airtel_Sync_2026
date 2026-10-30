package com.udyam.backoffice;

import com.fasterxml.jackson.databind.JsonNode;
import com.udyam.advisor.AdvisorStore;
import com.udyam.rules.audit.MisSellingReport;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Everything the back-office reads and its assignments. App: JdbcBackofficeStore (Neon); tests: in-memory, from the CSVs. */
public interface BackofficeStore {

    List<HistoricalLead> historicalLeads();

    /** Advisor sessions with no order, last active at or before the given time. */
    List<AdvisorStore.Session> idleSessions(OffsetDateTime lastActiveBefore);

    /** Orders in these statuses with a profile, last changed at or before the given time. */
    List<OrderLead> stuckOrders(Collection<String> statuses, OffsetDateTime lastActiveBefore);

    Optional<OrderLead> orderLead(UUID orderId);

    Map<String, Assignment> assignments();

    Optional<Assignment> assignment(String kind, String ref);

    /** Insert or update the lead's assignment, and add a line to its history. */
    Assignment saveAssignment(String kind, String ref, String channel, String status, String assigneeLabel, String note);

    List<AssignmentEvent> history(String kind, String ref);

    LiveFunnel liveFunnel();

    MisSellingReport misSelling();

    static String key(String kind, String ref) {
        return kind + "/" + ref;
    }

    /** One smb_lead row, without the contact's name. gstinMasked is already masked. */
    record HistoricalLead(String leadId, LocalDate createdDate, LocalDate lastActivity, String leadSource, String businessName,
                          String businessType, String segment, String city, int tier, int employees, int sites,
                          boolean hasGstin, String gstinMasked, String registryStatus, boolean hasUdyam, int monthlySpendInr,
                          String currentInternet, List<String> productIds, String stageReached, String outcome,
                          String dropReason, int acquisitionCostInr, Integer monthlyBundleInr, Boolean churned) {
    }

    /** kycFields: extracted fields by doc type. failedCheckEn/Hi: first failed check of the latest KYC run. */
    record OrderLead(UUID orderId, String orderRef, String status, JsonNode profile, JsonNode quote, int monthlyTotalInr,
                     String holdReason, OffsetDateTime lastActivity, Map<String, JsonNode> kycFields, String failedCheckEn,
                     String failedCheckHi) {
    }

    record Assignment(String kind, String ref, String channel, String status, String assigneeLabel, String note,
                      OffsetDateTime createdAt, OffsetDateTime updatedAt) {
    }

    record AssignmentEvent(String channel, String status, String assigneeLabel, String note, OffsetDateTime createdAt) {
    }

    /** From our own tables, not the provided data. Follows advisor sessions only: an order placed without one isn't in it. */
    record LiveFunnel(int sessions, int recommended, int checkout, int kycVerified, int active) {
    }
}
