package com.udyam.backoffice;

import static com.udyam.backoffice.BackofficeViews.LeadDetail;
import static com.udyam.backoffice.BackofficeViews.QueuePage;
import static com.udyam.backoffice.BackofficeViews.QueueRow;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.udyam.advisor.AdvisorStore;
import com.udyam.common.errors.ApiException;
import com.udyam.common.errors.NotFoundException;
import com.udyam.kyc.KycStore;
import com.udyam.registry.GstinDetails;
import com.udyam.rules.eligibility.EligibilityService;
import com.udyam.rules.profile.BusinessProfile;
import com.udyam.rules.profile.ProfileResolver;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * The call queue: leads the journey didn't close, scored and routed. Three kinds: HISTORICAL (provided data), SESSION
 * (unclosed advisor chats) and ORDER (stuck orders). Nothing here changes the customer's journey.
 */
@Service
public class BackofficeService {

    public static final String HISTORICAL = "HISTORICAL";
    public static final String SESSION = "SESSION";
    public static final String ORDER = "ORDER";
    public static final String STATUS_NEW = "NEW";
    private static final Set<String> KINDS = Set.of(HISTORICAL, SESSION, ORDER);
    private static final Set<String> STATUSES = Set.of(STATUS_NEW, "CONTACTED", "CONVERTED", "LOST");

    /** Orders the customer hasn't finished. KYC_REJECTED is closed; later statuses are the installation team's. */
    public static final List<String> STUCK_STATUSES = List.of("QUOTE_ACCEPTED", "KYC_PENDING_CONSENT", "KYC_IN_PROGRESS",
            "KYC_ACTION_REQUIRED", "ON_HOLD");

    /** Lost for how we sold, not what we sold: winnable digitally. */
    public static final List<String> PROCESS_REASONS = List.of("Owner had no time for a visit", "Visit not scheduled by sales",
            "Quote took too long to arrive", "Quote too complex to understand", "Found paperwork too long");
    public static final Set<String> PRICE_REASONS = Set.of("Price too high", "Competitor offered cheaper plan");
    /** Not worth anyone's time: kept out of the queue. */
    public static final Set<String> NOT_WINNABLE_REASONS = Set.of("Not interested on first call", "Wrong or invalid number",
            "Locked in with current provider");

    /** The dataset has no partner list, so a city "has" a partner once partners have closed this many deals there. */
    static final int PARTNER_MIN_ACTIVATIONS = 3;

    private static final int DEFAULT_SIZE = 25;
    private static final int MAX_SIZE = 100;
    private static final int RECENT_MESSAGES = 6;
    private static final String NO_CONTACT = "Contact details aren't collected yet, so this lead can't be reached from here.";

    private static final Logger log = LoggerFactory.getLogger(BackofficeService.class);

    private final BackofficeStore store;
    private final PropensityScorer scorer;
    private final LeadFeatureMapper mapper;
    private final AdvisorStore advisor;
    private final KycStore kyc;
    private final ProfileResolver profiles;
    private final EligibilityService eligibility;
    private final BackofficeProperties props;
    private final ObjectMapper json;
    private final Clock clock;

    private volatile Historical historical;

    public BackofficeService(BackofficeStore store, PropensityScorer scorer, LeadFeatureMapper mapper, AdvisorStore advisor, KycStore kyc,
                             ProfileResolver profiles, EligibilityService eligibility,
                             BackofficeProperties props, ObjectMapper json, Clock clock) {
        this.store = store;
        this.scorer = scorer;
        this.mapper = mapper;
        this.advisor = advisor;
        this.kyc = kyc;
        this.profiles = profiles;
        this.eligibility = eligibility;
        this.props = props;
        this.json = json;
        this.clock = clock;
    }

    /** Provided data never changes: read and score it once. */
    private record Historical(List<BackofficeStore.HistoricalLead> leads, Set<String> partnerCities, Map<String, QueueRow> rows) {
    }

    private Historical historical() {
        Historical h = historical;
        if (h == null) {
            List<BackofficeStore.HistoricalLead> leads = store.historicalLeads();
            Set<String> partners = partnerCities(leads);
            Map<String, QueueRow> rows = new HashMap<>();
            for (BackofficeStore.HistoricalLead l : leads) {
                rows.put(l.leadId(), historicalRow(l, partners));
            }
            h = new Historical(leads, partners, rows);
            historical = h;
            log.info("Back-office: {} historical leads scored, {} cities with a channel partner", leads.size(), partners.size());
        }
        return h;
    }

    static Set<String> partnerCities(List<BackofficeStore.HistoricalLead> leads) {
        return leads.stream().filter(l -> "Channel partner".equals(l.leadSource()) && "Activated".equals(l.outcome()))
                .collect(Collectors.groupingBy(BackofficeStore.HistoricalLead::city, Collectors.counting()))
                .entrySet().stream().filter(e -> e.getValue() >= PARTNER_MIN_ACTIVATIONS).map(Map.Entry::getKey)
                .collect(Collectors.toSet());
    }

    private QueueRow historicalRow(BackofficeStore.HistoricalLead l, Set<String> partners) {
        LeadProfile p = mapper.fromHistorical(l);
        PropensityScorer.Score s = scorer.score(p.modelInput());
        LeadRouter.Route r = LeadRouter.route(new LeadRouter.Context(p.segment(), s.band(), p.gstinStatus(), p.city(), null, null, null, null,
                null, partners.contains(p.city()), null));
        String stopped = l.dropReason() == null ? l.stageReached() : l.stageReached() + ": " + l.dropReason();
        return row(HISTORICAL, l.leadId(), l.leadId(), l.businessName(), p, stopped,
                l.lastActivity() == null ? null : l.lastActivity().toString(), s, r, null);
    }

    private QueueRow sessionRow(AdvisorStore.Session session, Set<String> partners) {
        LeadProfile p = mapper.fromSession(session.profile());
        PropensityScorer.Score s = scorer.score(p.modelInput());
        Integer quote = session.quote() != null && session.quote().hasNonNull("monthlyTotalInr") ? session.quote().get("monthlyTotalInr").asInt() : null;
        LeadRouter.Route r = LeadRouter.route(new LeadRouter.Context(p.segment(), s.band(), p.gstinStatus(), p.city(), session.stage(), null, null, null,
                quote, partners.contains(p.city()), null));
        String name = registryName(session.profile()).orElse(null);
        return row(SESSION, session.id().toString(), session.id().toString().substring(0, 8), name, p, "Advisor: " + session.stage(),
                session.updatedAt().toString(), s, r, quote);
    }

    private QueueRow orderRow(BackofficeStore.OrderLead o, Set<String> partners) {
        LeadProfile p = mapper.fromOrder(o.profile(), o.kycFields());
        PropensityScorer.Score s = scorer.score(p.modelInput());
        Boolean airFiber = "SITE_NOT_FEASIBLE".equals(o.holdReason()) ? airFiberEligible(o.profile()) : null;
        LeadRouter.Route r = LeadRouter.route(new LeadRouter.Context(p.segment(), s.band(), p.gstinStatus(), p.city(), null, o.status(), o.holdReason(),
                BackofficeMasking.checkMessage(o.failedCheckEn()), o.monthlyTotalInr(), partners.contains(p.city()), airFiber));
        String stopped = "ON_HOLD".equals(o.status()) && o.holdReason() != null ? "ON_HOLD: " + o.holdReason() : o.status();
        return row(ORDER, o.orderId().toString(), o.orderRef(), orderName(o), p, stopped,
                o.lastActivity() == null ? null : o.lastActivity().toString(), s, r, o.monthlyTotalInr());
    }

    private static QueueRow row(String kind, String ref, String displayRef, String name, LeadProfile p, String stopped, String lastActivity,
                                PropensityScorer.Score s, LeadRouter.Route r, Integer quote) {
        return new QueueRow(kind, ref, displayRef, name, p.businessType(), p.segment(), p.city(), p.tier(), p.employees(), p.sites(), p.gstinStatus(),
                stopped, lastActivity, s.score0to100(), s.probability(), s.band(), s.topReasons(), r.action(), r.channel(), r.why(), quote, STATUS_NEW, null);
    }

    /** The name read from KYC is masked; without documents, the registry name for the GSTIN the owner gave. */
    private String orderName(BackofficeStore.OrderLead o) {
        for (String[] source : new String[][] {{"GST_CERTIFICATE", "tradeName"}, {"UDYAM_CERTIFICATE", "enterpriseName"},
                {"SIGNATORY_ID", "forBusiness"}, {"ADDRESS_PROOF", "billedTo"}}) {
            JsonNode doc = o.kycFields().get(source[0]);
            if (doc != null && doc.hasNonNull(source[1])) {
                return BackofficeMasking.kycName(doc.get(source[1]).asText());
            }
        }
        return registryName(o.profile()).orElse(null);
    }

    private Optional<String> registryName(JsonNode profile) {
        if (profile == null || !profile.hasNonNull("gstin")) {
            return Optional.empty();
        }
        return mapper.registryDetails(profile.get("gstin").asText()).map(GstinDetails::tradeName);
    }

    private Boolean airFiberEligible(JsonNode profile) {
        try {
            BusinessProfile p = json.treeToValue(profile, BusinessProfile.class);
            return eligibility.check(profiles.resolve(p), "P04", List.of()).eligible();
        } catch (JsonProcessingException | ApiException | IllegalArgumentException e) {
            return null; // the profile isn't complete enough to say
        }
    }

    // ---- queue ---------------------------------------------------------------------------------------------------

    public record QueueQuery(String kind, String segment, Integer tier, String band, String channel, String status, Integer page, Integer size, String sort) {
    }

    /** Live leads first, as they're the ones still in play; sort=score ranks everything purely by score. */
    public QueuePage queue(QueueQuery q) {
        Historical h = historical();
        List<QueueRow> rows = new ArrayList<>();
        if (q.kind() == null || HISTORICAL.equals(q.kind())) {
            h.leads().stream().filter(BackofficeService::inHistoricalQueue).forEach(l -> rows.add(h.rows().get(l.leadId())));
        }
        if (q.kind() == null || SESSION.equals(q.kind())) {
            rows.addAll(sessionRows(h.partnerCities()));
        }
        if (q.kind() == null || ORDER.equals(q.kind())) {
            rows.addAll(orderRows(h.partnerCities()));
        }
        Map<String, BackofficeStore.Assignment> assignments = store.assignments();
        List<QueueRow> filtered = rows.stream().map(r -> withAssignment(r, assignments)).filter(r -> matches(r, q)).sorted(order(q.sort())).toList();
        return page(filtered, q.page(), q.size());
    }

    static boolean inHistoricalQueue(BackofficeStore.HistoricalLead l) {
        return !"Activated".equals(l.outcome()) && !NOT_WINNABLE_REASONS.contains(l.dropReason());
    }

    private List<QueueRow> sessionRows(Set<String> partners) {
        OffsetDateTime before = OffsetDateTime.now(clock).minus(Duration.ofMinutes(props.sessionIdleMinutes()));
        return store.idleSessions(before).stream().filter(BackofficeService::hasBusinessFacts).map(s -> sessionRow(s, partners)).toList();
    }

    /** A chat that says nothing about the business isn't a lead yet. */
    private static boolean hasBusinessFacts(AdvisorStore.Session s) {
        JsonNode p = s.profile();
        return p != null && (p.hasNonNull("segment") || p.hasNonNull("businessType"));
    }

    private List<QueueRow> orderRows(Set<String> partners) {
        OffsetDateTime before = OffsetDateTime.now(clock).minus(Duration.ofHours(props.orderStuckHours()));
        return store.stuckOrders(STUCK_STATUSES, before).stream().map(o -> orderRow(o, partners)).toList();
    }

    private static QueueRow withAssignment(QueueRow r, Map<String, BackofficeStore.Assignment> assignments) {
        BackofficeStore.Assignment a = assignments.get(BackofficeStore.key(r.kind(), r.ref()));
        return a == null ? r : new QueueRow(r.kind(), r.ref(), r.displayRef(), r.businessName(), r.businessType(), r.segment(), r.city(), r.tier(),
                r.employees(), r.sites(), r.gstinStatus(), r.stoppedAt(), r.lastActivity(), r.score(), r.probability(), r.band(), r.topReasons(),
                r.suggestedAction(), r.recommendedChannel(), r.channelWhy(), r.quoteMonthlyInr(), a.status(), a);
    }

    private static boolean matches(QueueRow r, QueueQuery q) {
        return (q.segment() == null || q.segment().equals(r.segment()))
                && (q.tier() == null || q.tier().equals(r.tier()))
                && (q.band() == null || q.band().equals(r.band()))
                && (q.channel() == null || q.channel().equals(r.recommendedChannel()))
                && (q.status() == null || q.status().equals(r.status()));
    }

    private static Comparator<QueueRow> order(String sort) {
        Comparator<QueueRow> byScore = Comparator.comparingDouble(QueueRow::probability).reversed().thenComparing(QueueRow::ref);
        return "score".equals(sort) ? byScore : Comparator.comparing((QueueRow r) -> HISTORICAL.equals(r.kind())).thenComparing(byScore);
    }

    private static QueuePage page(List<QueueRow> rows, Integer page, Integer size) {
        int pageSize = size == null ? DEFAULT_SIZE : Math.max(1, Math.min(MAX_SIZE, size));
        int pageNumber = page == null ? 0 : Math.max(0, page);
        int from = Math.min(pageNumber * pageSize, rows.size());
        int to = Math.min(from + pageSize, rows.size());
        return new QueuePage(rows.subList(from, to), pageNumber, pageSize, rows.size());
    }

    // ---- detail + actions ----------------------------------------------------------------------------------------

    public LeadDetail detail(String kind, String ref) {
        return switch (checkKind(kind)) {
            case HISTORICAL -> historicalDetail(ref);
            case SESSION -> sessionDetail(ref);
            default -> orderDetail(ref);
        };
    }

    private LeadDetail historicalDetail(String ref) {
        Historical h = historical();
        BackofficeStore.HistoricalLead l = h.leads().stream().filter(x -> x.leadId().equals(ref)).findFirst().orElseThrow(BackofficeService::notFound);
        Map<String, Object> profile = new HashMap<>();
        profile.put("leadSource", l.leadSource());
        profile.put("businessType", l.businessType());
        profile.put("city", l.city());
        profile.put("employees", l.employees());
        profile.put("sites", l.sites());
        profile.put("gstin", l.gstinMasked());
        profile.put("registryStatus", l.registryStatus());
        profile.put("currentInternet", l.currentInternet());
        profile.put("monthlyTelecomSpendInr", l.monthlySpendInr());
        profile.put("productsOfInterest", l.productIds());
        return detail(h.rows().get(ref), json.valueToTree(profile), null, List.of(), List.of());
    }

    private LeadDetail sessionDetail(String ref) {
        UUID id = uuid(ref);
        AdvisorStore.Session s = advisor.findSession(id).orElseThrow(BackofficeService::notFound);
        List<AdvisorStore.Message> all = advisor.messages(id);
        List<BackofficeViews.ChatLine> recent = all.subList(Math.max(0, all.size() - RECENT_MESSAGES), all.size()).stream()
                .map(m -> new BackofficeViews.ChatLine(m.role(), BackofficeMasking.text(m.content()), m.createdAt() == null ? null : m.createdAt().toString()))
                .toList();
        return detail(sessionRow(s, historical().partnerCities()), BackofficeMasking.profile(s.profile()), quoteSummary(s.quote()), List.of(), recent);
    }

    private LeadDetail orderDetail(String ref) {
        UUID id = uuid(ref);
        BackofficeStore.OrderLead o = store.orderLead(id).orElseThrow(BackofficeService::notFound);
        List<BackofficeViews.FailedCheck> failed = kyc.latestChecks(id).stream().filter(c -> c.blockingFailure())
                .map(c -> new BackofficeViews.FailedCheck(c.code(), BackofficeMasking.checkMessage(c.messageEn()), BackofficeMasking.checkMessage(c.messageHi()),
                        BackofficeMasking.checkMessage(c.fixHintEn()), BackofficeMasking.checkMessage(c.fixHintHi()))).toList();
        return detail(orderRow(o, historical().partnerCities()), BackofficeMasking.profile(o.profile()), quoteSummary(o.quote()), failed, List.of());
    }

    private LeadDetail detail(QueueRow row, JsonNode profile, BackofficeViews.QuoteSummary quote, List<BackofficeViews.FailedCheck> failed, List<BackofficeViews.ChatLine> chat) {
        QueueRow current = withAssignment(row, store.assignments());
        return new LeadDetail(current, profile, quote, failed, chat, NO_CONTACT, store.history(row.kind(), row.ref()));
    }

    private static BackofficeViews.QuoteSummary quoteSummary(JsonNode q) {
        if (q == null || !q.hasNonNull("monthlyTotalInr")) {
            return null;
        }
        List<BackofficeViews.QuoteLine> lines = new ArrayList<>();
        q.path("lines").forEach(l -> lines.add(new BackofficeViews.QuoteLine(l.path("productName").asText(), l.path("quantity").asInt())));
        return new BackofficeViews.QuoteSummary(q.get("monthlyTotalInr").asInt(), q.path("oneTimeTotalInr").asInt(), q.path("contractMonths").asInt(), lines);
    }

    public LeadDetail assign(String kind, String ref, String channel, String assigneeLabel, String note) {
        LeadDetail lead = detail(kind, ref);
        if (channel == null || !LeadRouter.CHANNELS.contains(channel)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CHANNEL", "channel must be one of " + LeadRouter.CHANNELS);
        }
        if (LeadRouter.FIELD_SALES.equals(channel) && LeadRouter.MICRO_SEGMENT.equals(lead.lead().segment())) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "MICRO_NO_FIELD_SALES", "Field visits cost about ₹53k per Micro activation: use inside sales or a digital nudge");
        }
        String status = store.assignment(kind, ref).map(BackofficeStore.Assignment::status).orElse(STATUS_NEW);
        store.saveAssignment(kind, ref, channel, status, assigneeLabel, note);
        log.info("Back-office: {} lead assigned to {}", kind, channel);
        return detail(kind, ref);
    }

    public LeadDetail updateStatus(String kind, String ref, String status, String note) {
        LeadDetail lead = detail(kind, ref);
        if (status == null || !STATUSES.contains(status)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_STATUS", "status must be one of " + STATUSES);
        }
        Optional<BackofficeStore.Assignment> current = store.assignment(kind, ref);
        store.saveAssignment(kind, ref, current.map(BackofficeStore.Assignment::channel).orElse(lead.lead().recommendedChannel()), status,
                current.map(BackofficeStore.Assignment::assigneeLabel).orElse(null), note);
        log.info("Back-office: {} lead status {}", kind, status);
        return detail(kind, ref);
    }

    // ---- re-engagement -------------------------------------------------------------------------------------------

    /** Historical leads lost to how we sold, best first: the ones a digital journey could win back. */
    public BackofficeViews.Reengagement reengagement(Integer page, Integer size) {
        Historical h = historical();
        List<BackofficeStore.HistoricalLead> dropped = h.leads().stream().filter(l -> l.dropReason() != null).toList();
        List<BackofficeStore.HistoricalLead> process = dropped.stream().filter(l -> PROCESS_REASONS.contains(l.dropReason())).toList();
        Map<String, Long> counts = process.stream().collect(Collectors.groupingBy(BackofficeStore.HistoricalLead::dropReason, Collectors.counting()));
        List<BackofficeViews.ReasonCount> byReason = PROCESS_REASONS.stream().map(r -> new BackofficeViews.ReasonCount(r, counts.getOrDefault(r, 0L)))
                .sorted(Comparator.comparingLong(BackofficeViews.ReasonCount::leads).reversed()).toList();
        long price = dropped.stream().filter(l -> PRICE_REASONS.contains(l.dropReason())).count();
        Map<String, BackofficeStore.Assignment> assignments = store.assignments();
        List<QueueRow> rows = process.stream().map(l -> withAssignment(h.rows().get(l.leadId()), assignments)).sorted(order("score")).toList();
        return new BackofficeViews.Reengagement(process.size(), price, byReason, page(rows, page, size));
    }

    // ---- helpers -------------------------------------------------------------------------------------------------

    private static String checkKind(String kind) {
        if (!KINDS.contains(kind)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_KIND", "kind must be HISTORICAL, SESSION or ORDER");
        }
        return kind;
    }

    private static UUID uuid(String ref) {
        try {
            return UUID.fromString(ref);
        } catch (IllegalArgumentException e) {
            throw notFound();
        }
    }

    private static NotFoundException notFound() {
        return new NotFoundException("Lead not found");
    }
}
