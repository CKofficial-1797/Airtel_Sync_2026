package com.udyam.backoffice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.udyam.TestData;
import com.udyam.advisor.AdvisorStore;
import com.udyam.advisor.InMemoryAdvisorStore;
import com.udyam.common.errors.ApiException;
import com.udyam.common.errors.NotFoundException;
import com.udyam.dataload.CsvParsing;
import com.udyam.kyc.InMemoryKycStore;
import com.udyam.kyc.checks.CheckResult;
import com.udyam.rules.RulesFixture;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BackofficeServiceTest {

    private static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule());
    private static final String ADDRESS_FAILURE = "The address proof is dated 2026-02-01, 164 days old; it must be at most 90 days old.";

    private final InMemoryBackofficeStore store = new InMemoryBackofficeStore();
    private final InMemoryAdvisorStore advisor = new InMemoryAdvisorStore();
    private final InMemoryKycStore kyc = new InMemoryKycStore();
    private final BackofficeService service = service(30, 24, Clock.offset(Clock.systemUTC(), Duration.ofHours(48)));

    private BackofficeService service(int idleMinutes, int stuckHours, Clock clock) {
        return new BackofficeService(store, new PropensityScorer(JSON), new LeadFeatureMapper(RulesFixture.PROVIDER, RulesFixture.GSTINS), advisor, kyc,
                RulesFixture.RESOLVER, RulesFixture.ELIGIBILITY, new BackofficeProperties("k", idleMinutes, stuckHours), JSON, clock);
    }

    private static BackofficeService.QueueQuery kind(String kind) {
        return new BackofficeService.QueueQuery(kind, null, null, null, null, null, null, 100, null);
    }

    private UUID session(String profile, String stage, String quote) throws Exception {
        UUID id = UUID.randomUUID();
        advisor.createSession(id, "en");
        advisor.updateSession(id, stage, "en", JSON.readTree(profile), null, quote == null ? null : JSON.readTree(quote));
        store.sessions.add(advisor.findSession(id).orElseThrow());
        return id;
    }

    private UUID order(String status, String profile, Map<String, JsonNode> docs, String failedEn, String holdReason) throws Exception {
        UUID id = UUID.randomUUID();
        store.orders.add(new BackofficeStore.OrderLead(id, "UD-" + id.toString().substring(0, 6), status, JSON.readTree(profile),
                JSON.readTree("{\"monthlyTotalInr\":3200,\"oneTimeTotalInr\":500,\"contractMonths\":12,\"lines\":[{\"productName\":\"Business Fibre\",\"quantity\":1}]}"),
                3200, holdReason, OffsetDateTime.now(), docs, failedEn, null));
        if (failedEn != null) {
            kyc.saveChecks(id, UUID.randomUUID(), List.of(new CheckResult("C14", "Address proof", CheckResult.FAIL, CheckResult.BLOCKING, true, failedEn, failedEn, "Upload a recent bill.", "हाल का बिल।", Map.of())));
        }
        return id;
    }

    private static String microProfile() {
        return "{\"businessType\":\"Kirana store\",\"city\":\"Nagpur\",\"employees\":3,\"sites\":1}";
    }

    private static String dentalProfile() {
        return "{\"businessType\":\"Dental clinic\",\"city\":\"Kochi\",\"employees\":8,\"sites\":1,\"gstin\":\"" + RulesFixture.ACTIVE_GSTIN + "\"}";
    }

    // ---- queue contents -------------------------------------------------------------------------------------------

    @Test
    void historyExcludesActivatedAndNotWinnableLeads() {
        BackofficeViews.QueuePage page = service.queue(new BackofficeService.QueueQuery(BackofficeService.HISTORICAL, null, null, null, null, null, 0, 25, "score"));
        assertThat(page.total()).isEqualTo(3518);
        assertThat(page.rows()).allMatch(r -> !r.stoppedAt().equals("Activated") && BackofficeService.NOT_WINNABLE_REASONS.stream().noneMatch(n -> r.stoppedAt().contains(n)));
    }

    @Test
    void microHistoryIsNeverRoutedToFieldSales() {
        for (int page = 0; page < 40; page++) {
            service.queue(new BackofficeService.QueueQuery(BackofficeService.HISTORICAL, LeadRouter.MICRO_SEGMENT, null, null, null, null, page, 100, null)).rows()
                    .forEach(r -> assertThat(r.recommendedChannel()).isNotEqualTo(LeadRouter.FIELD_SALES));
        }
    }

    @Test
    void rowsCarryScoreReasonsActionAndChannel() {
        BackofficeViews.QueueRow r = service.queue(new BackofficeService.QueueQuery(BackofficeService.HISTORICAL, null, null, "HOT", null, null, 0, 1, "score")).rows().get(0);
        assertThat(r.score()).isGreaterThanOrEqualTo(80);
        assertThat(r.band()).isEqualTo("HOT");
        assertThat(r.topReasons()).isNotEmpty().hasSizeLessThanOrEqualTo(3);
        assertThat(r.suggestedAction()).isNotBlank();
        assertThat(r.recommendedChannel()).isIn(LeadRouter.CHANNELS);
        assertThat(r.channelWhy()).isNotBlank();
        assertThat(r.status()).isEqualTo(BackofficeService.STATUS_NEW);
    }

    @Test
    void anIdleSessionWithoutBusinessFactsIsNotALead() throws Exception {
        session("{\"city\":\"Nagpur\"}", "COLLECTING", null);
        UUID real = session(microProfile(), "COLLECTING", null);
        List<BackofficeViews.QueueRow> rows = service.queue(kind(BackofficeService.SESSION)).rows();
        assertThat(rows).extracting(BackofficeViews.QueueRow::ref).containsExactly(real.toString());
    }

    @Test
    void aRecentSessionIsNotIdleYetAndZeroMinutesShowsItNow() throws Exception {
        session(microProfile(), "COLLECTING", null);
        assertThat(service(30, 24, Clock.systemUTC()).queue(kind(BackofficeService.SESSION)).total()).isZero();
        assertThat(service(0, 24, Clock.offset(Clock.systemUTC(), Duration.ofSeconds(1))).queue(kind(BackofficeService.SESSION)).total()).isEqualTo(1);
    }

    @Test
    void aRecentOrderIsNotStuckYet() throws Exception {
        order("KYC_IN_PROGRESS", dentalProfile(), Map.of(), null, null);
        assertThat(service(30, 24, Clock.systemUTC()).queue(kind(BackofficeService.ORDER)).total()).isZero();
        assertThat(service.queue(kind(BackofficeService.ORDER)).total()).isEqualTo(1);
    }

    @Test
    void onlyUnfinishedStatusesCountAsStuck() throws Exception {
        for (String status : List.of("QUOTE_ACCEPTED", "KYC_PENDING_CONSENT", "KYC_IN_PROGRESS", "KYC_ACTION_REQUIRED", "ON_HOLD")) {
            order(status, dentalProfile(), Map.of(), null, null);
        }
        for (String status : List.of("KYC_REJECTED", "KYC_VERIFIED", "ACTIVE")) {
            order(status, dentalProfile(), Map.of(), null, null);
        }
        assertThat(service.queue(kind(BackofficeService.ORDER)).total()).isEqualTo(5);
    }

    @Test
    void theThreeDemoLeadsLookSensible() throws Exception {
        UUID kirana = session(microProfile(), "COLLECTING", null);
        UUID dental = session(dentalProfile(), "COLLECTING", null);
        UUID k11 = order("KYC_ACTION_REQUIRED", dentalProfile(), Map.of(), ADDRESS_FAILURE, null);

        BackofficeViews.QueuePage all = service.queue(new BackofficeService.QueueQuery(null, null, null, null, null, null, 0, 3, null));
        assertThat(all.rows()).extracting(BackofficeViews.QueueRow::kind).doesNotContain(BackofficeService.HISTORICAL);

        BackofficeViews.QueueRow microRow = service.detail(BackofficeService.SESSION, kirana.toString()).lead();
        assertThat(microRow.segment()).isEqualTo(LeadRouter.MICRO_SEGMENT);
        assertThat(microRow.recommendedChannel()).isEqualTo(LeadRouter.DIGITAL_NUDGE);

        BackofficeViews.QueueRow dentalRow = service.detail(BackofficeService.SESSION, dental.toString()).lead();
        assertThat(dentalRow.gstinStatus()).isEqualTo(LeadProfile.GST_ACTIVE);
        assertThat(dentalRow.score()).isGreaterThan(microRow.score());

        BackofficeViews.QueueRow orderRow = service.detail(BackofficeService.ORDER, k11.toString()).lead();
        assertThat(orderRow.suggestedAction()).isEqualTo(LeadRouter.KYC_HELP);
        assertThat(orderRow.channelWhy()).contains("at most 90 days");
    }

    // ---- filters, sort, paging ------------------------------------------------------------------------------------

    @Test
    void filtersNarrowTheRows() {
        var q = new BackofficeService.QueueQuery(BackofficeService.HISTORICAL, "Professional offices", 1, "HOT", null, null, 0, 100, null);
        assertThat(service.queue(q).rows()).isNotEmpty().allMatch(r -> r.segment().equals("Professional offices") && r.tier() == 1 && r.band().equals("HOT"));
        var byChannel = new BackofficeService.QueueQuery(BackofficeService.HISTORICAL, null, null, null, LeadRouter.DIGITAL_NUDGE, null, 0, 100, null);
        assertThat(service.queue(byChannel).rows()).isNotEmpty().allMatch(r -> r.recommendedChannel().equals(LeadRouter.DIGITAL_NUDGE));
    }

    @Test
    void theStatusFilterSeesAssignments() {
        BackofficeViews.QueueRow first = service.queue(kind(BackofficeService.HISTORICAL)).rows().get(0);
        service.updateStatus(first.kind(), first.ref(), "CONTACTED", "Left a message");
        var contacted = new BackofficeService.QueueQuery(BackofficeService.HISTORICAL, null, null, null, null, "CONTACTED", 0, 100, null);
        assertThat(service.queue(contacted).rows()).extracting(BackofficeViews.QueueRow::ref).containsExactly(first.ref());
        var fresh = new BackofficeService.QueueQuery(BackofficeService.HISTORICAL, null, null, null, null, "NEW", 0, 100, null);
        assertThat(service.queue(fresh).total()).isEqualTo(3517);
    }

    @Test
    void sortByScoreRanksEverythingAndTheDefaultListsLiveLeadsFirst() throws Exception {
        UUID coldLive = session("{\"businessType\":\"Kirana store\",\"city\":\"Nagpur\"}", "COLLECTING", null);
        List<BackofficeViews.QueueRow> ranked = service.queue(new BackofficeService.QueueQuery(null, null, null, null, null, null, 0, 100, "score")).rows();
        assertThat(ranked).isSortedAccordingTo(Comparator.comparingDouble(BackofficeViews.QueueRow::probability).reversed());
        assertThat(ranked).extracting(BackofficeViews.QueueRow::ref).doesNotContain(coldLive.toString());
        assertThat(service.queue(new BackofficeService.QueueQuery(null, null, null, null, null, null, 0, 1, null)).rows().get(0).ref()).isEqualTo(coldLive.toString());
    }

    @Test
    void pagesAreStableAndTheSizeIsCapped() {
        var first = service.queue(new BackofficeService.QueueQuery(BackofficeService.HISTORICAL, null, null, null, null, null, 0, 10, "score"));
        var second = service.queue(new BackofficeService.QueueQuery(BackofficeService.HISTORICAL, null, null, null, null, null, 1, 10, "score"));
        assertThat(first.rows()).hasSize(10);
        assertThat(second.rows()).hasSize(10);
        assertThat(first.rows()).extracting(BackofficeViews.QueueRow::ref).doesNotContainAnyElementsOf(second.rows().stream().map(BackofficeViews.QueueRow::ref).toList());
        assertThat(service.queue(new BackofficeService.QueueQuery(BackofficeService.HISTORICAL, null, null, null, null, null, 0, 9999, null)).size()).isEqualTo(100);
        assertThat(service.queue(new BackofficeService.QueueQuery(BackofficeService.HISTORICAL, null, null, null, null, null, 9999, 10, null)).rows()).isEmpty();
    }

    @Test
    void partnersExistIn14Cities() {
        assertThat(BackofficeService.partnerCities(InMemoryBackofficeStore.LEADS)).hasSize(14);
    }

    // ---- masking --------------------------------------------------------------------------------------------------

    @Test
    void identifiersAreMaskedInTheQueueAndDetail() throws Exception {
        UUID id = session(dentalProfile(), "COLLECTING", null);
        advisor.addMessage(id, "user", "My GSTIN is " + RulesFixture.ACTIVE_GSTIN + ", Udyam UDYAM-KL-07-0012345, call 9876543210", null, null);
        String detail = JSON.writeValueAsString(service.detail(BackofficeService.SESSION, id.toString()));
        String queue = JSON.writeValueAsString(service.queue(kind(BackofficeService.SESSION)));
        for (String text : List.of(detail, queue)) {
            assertThat(text).doesNotContain(RulesFixture.ACTIVE_GSTIN).doesNotContain("UDYAM-KL-07-0012345").doesNotContain("9876543210");
        }
        assertThat(detail).contains("GSTIN is 32TTN");
    }

    @Test
    void aHistoricalGstinIsMaskedAndTheContactNameIsNeverSent() throws Exception {
        BackofficeStore.HistoricalLead lead = InMemoryBackofficeStore.LEADS.stream().filter(l -> l.gstinMasked() != null).findFirst().orElseThrow();
        String contactName = CsvParsing.readAll(TestData.LEADS_CSV).stream().filter(r -> r.get("lead_id").equals(lead.leadId())).findFirst().orElseThrow().get("contact_name");
        String detail = JSON.writeValueAsString(service.detail(BackofficeService.HISTORICAL, lead.leadId()));
        assertThat(detail).contains(lead.gstinMasked()).doesNotContain(contactName);
    }

    @Test
    void aNameFromKycDocumentsIsMasked() throws Exception {
        UUID id = order("KYC_ACTION_REQUIRED", dentalProfile(), Map.of("GST_CERTIFICATE", JSON.readTree("{\"tradeName\":\"Ganesh Wholesale Traders\"}")), ADDRESS_FAILURE, null);
        BackofficeViews.QueueRow row = service.detail(BackofficeService.ORDER, id.toString()).lead();
        assertThat(row.businessName()).isEqualTo("Ganesh W******** T******");
    }

    @Test
    void namesAndGstinsInCheckMessagesAreMasked() {
        assertThat(BackofficeMasking.checkMessage("The signatory 'Ramesh Kumar' is not the proprietor 'Suresh Rao Patil'."))
                .isEqualTo("The signatory 'Ramesh K****' is not the proprietor 'Suresh R** P****'.");
        assertThat(BackofficeMasking.checkMessage("GSTIN " + RulesFixture.ACTIVE_GSTIN + " is not in the GST registry.")).doesNotContain(RulesFixture.ACTIVE_GSTIN);
    }

    @Test
    void freeTextMaskingCatchesPanAadhaarAndPhones() {
        String masked = BackofficeMasking.text("PAN ABCDE1234F, Aadhaar 1234 5678 9012, phone 98765 43210, shop no 42");
        assertThat(masked).doesNotContain("ABCDE1234F").doesNotContain("1234 5678 9012").doesNotContain("98765 43210").contains("shop no 42");
    }

    // ---- detail ---------------------------------------------------------------------------------------------------

    @Test
    void sessionDetailShowsTheLastSixMessagesAndTheQuote() throws Exception {
        UUID id = session(dentalProfile(), "RECOMMENDED", "{\"monthlyTotalInr\":2499,\"oneTimeTotalInr\":0,\"contractMonths\":12,\"lines\":[{\"productName\":\"Business Fibre\",\"quantity\":1}]}");
        for (int i = 1; i <= 8; i++) {
            advisor.addMessage(id, i % 2 == 0 ? "assistant" : "user", "message " + i, null, null);
        }
        BackofficeViews.LeadDetail d = service.detail(BackofficeService.SESSION, id.toString());
        assertThat(d.recentMessages()).hasSize(6);
        assertThat(d.recentMessages().get(0).content()).isEqualTo("message 3");
        assertThat(d.quote().monthlyInr()).isEqualTo(2499);
        assertThat(d.quote().lines().get(0).productName()).isEqualTo("Business Fibre");
        assertThat(d.lead().suggestedAction()).isEqualTo(LeadRouter.FOLLOW_UP_QUOTE);
        assertThat(d.lead().quoteMonthlyInr()).isEqualTo(2499);
        assertThat(d.contactNote()).contains("aren't collected");
    }

    @Test
    void orderDetailShowsTheFailedChecks() throws Exception {
        UUID id = order("KYC_ACTION_REQUIRED", dentalProfile(), Map.of(), ADDRESS_FAILURE, null);
        BackofficeViews.LeadDetail d = service.detail(BackofficeService.ORDER, id.toString());
        assertThat(d.failedChecks()).hasSize(1);
        assertThat(d.failedChecks().get(0).code()).isEqualTo("C14");
        assertThat(d.quote().monthlyInr()).isEqualTo(3200);
        assertThat(d.recentMessages()).isEmpty();
    }

    @Test
    void aSiteHoldChecksAirFiberWithTheRulesEngine() throws Exception {
        UUID id = order("ON_HOLD", dentalProfile(), Map.of(), null, "SITE_NOT_FEASIBLE");
        BackofficeViews.QueueRow row = service.detail(BackofficeService.ORDER, id.toString()).lead();
        assertThat(row.suggestedAction()).isEqualTo(LeadRouter.TECH_FEASIBILITY);
        assertThat(row.channelWhy()).contains("AirFiber");
    }

    @Test
    void anIncompleteProfileDoesNotBreakTheHoldCheck() throws Exception {
        UUID id = order("ON_HOLD", "{\"businessType\":\"Dental clinic\"}", Map.of(), null, "SITE_NOT_FEASIBLE");
        assertThat(service.detail(BackofficeService.ORDER, id.toString()).lead().channelWhy()).contains("Check whether");
    }

    @Test
    void unknownLeadsAreNotFoundAndABadKindIsRejected() {
        assertThatThrownBy(() -> service.detail(BackofficeService.HISTORICAL, "NOPE")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.detail(BackofficeService.SESSION, "not-a-uuid")).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.detail(BackofficeService.ORDER, UUID.randomUUID().toString())).isInstanceOf(NotFoundException.class);
        assertThatThrownBy(() -> service.detail("OTHER", "x")).isInstanceOf(ApiException.class);
    }

    // ---- actions --------------------------------------------------------------------------------------------------

    @Test
    void assignAndStatusChangesPersistWithHistory() throws Exception {
        UUID id = session(dentalProfile(), "COLLECTING", null);
        String ref = id.toString();
        service.assign(BackofficeService.SESSION, ref, LeadRouter.INSIDE_SALES, "Priya", "Call after 5");
        BackofficeViews.LeadDetail after = service.updateStatus(BackofficeService.SESSION, ref, "CONTACTED", "Spoke to the owner");

        assertThat(after.lead().status()).isEqualTo("CONTACTED");
        assertThat(after.lead().assignment().assigneeLabel()).isEqualTo("Priya");
        assertThat(after.lead().assignment().channel()).isEqualTo(LeadRouter.INSIDE_SALES);
        assertThat(after.history()).hasSize(2);
        assertThat(after.history().get(0).note()).isEqualTo("Spoke to the owner");
        assertThat(after.history().get(1).status()).isEqualTo(BackofficeService.STATUS_NEW);
        assertThat(service.queue(kind(BackofficeService.SESSION)).rows().get(0).status()).isEqualTo("CONTACTED");
    }

    @Test
    void aStatusChangeWithoutAnAssignmentKeepsTheSuggestedChannel() throws Exception {
        UUID id = session(microProfile(), "COLLECTING", null);
        BackofficeViews.LeadDetail after = service.updateStatus(BackofficeService.SESSION, id.toString(), "LOST", null);
        assertThat(after.lead().assignment().channel()).isEqualTo(LeadRouter.DIGITAL_NUDGE);
    }

    @Test
    void aMicroLeadCannotBeAssignedToFieldSales() throws Exception {
        UUID id = session(microProfile(), "COLLECTING", null);
        assertThatThrownBy(() -> service.assign(BackofficeService.SESSION, id.toString(), LeadRouter.FIELD_SALES, null, null))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getCode()).isEqualTo("MICRO_NO_FIELD_SALES"));
        assertThat(store.assignments).isEmpty();
    }

    @Test
    void badChannelsAndStatusesAreRejected() throws Exception {
        UUID id = session(dentalProfile(), "COLLECTING", null);
        assertThatThrownBy(() -> service.assign(BackofficeService.SESSION, id.toString(), "PIGEON", null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.assign(BackofficeService.SESSION, id.toString(), null, null, null)).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> service.updateStatus(BackofficeService.SESSION, id.toString(), "DONE", null)).isInstanceOf(ApiException.class);
    }

    @Test
    void anUnknownLeadCannotBeAssigned() {
        assertThatThrownBy(() -> service.assign(BackofficeService.HISTORICAL, "NOPE", LeadRouter.INSIDE_SALES, null, null)).isInstanceOf(NotFoundException.class);
        assertThat(store.assignments).isEmpty();
    }

    // ---- re-engagement --------------------------------------------------------------------------------------------

    @Test
    void reengagementListsOnlyProcessReasonsRankedByScore() {
        BackofficeViews.Reengagement r = service.reengagement(0, 100);
        assertThat(r.processTotal()).isEqualTo(1291);
        assertThat(r.priceTotal()).isEqualTo(621);
        assertThat(r.leads().total()).isEqualTo(1291);
        assertThat(r.leads().rows()).allMatch(row -> BackofficeService.PROCESS_REASONS.stream().anyMatch(row.stoppedAt()::contains));
        assertThat(r.leads().rows()).isSortedAccordingTo(Comparator.comparingDouble(BackofficeViews.QueueRow::probability).reversed());
    }

    @Test
    void reengagementCountsEachReason() {
        Map<String, Long> counts = service.reengagement(0, 1).byReason().stream()
                .collect(java.util.stream.Collectors.toMap(BackofficeViews.ReasonCount::reason, BackofficeViews.ReasonCount::leads));
        assertThat(counts).containsOnly(Map.entry("Owner had no time for a visit", 531L), Map.entry("Visit not scheduled by sales", 296L),
                Map.entry("Quote too complex to understand", 206L), Map.entry("Quote took too long to arrive", 179L), Map.entry("Found paperwork too long", 79L));
    }

    @Test
    void aStoredSessionReadsBackFromTheAdvisorStore() throws Exception {
        UUID id = session(microProfile(), "COLLECTING", null);
        AdvisorStore.Session s = advisor.findSession(id).orElseThrow();
        assertThat(s.profile().get("businessType").asText()).isEqualTo("Kirana store");
    }
}
