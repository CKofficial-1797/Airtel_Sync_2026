package com.udyam.backoffice;

import com.fasterxml.jackson.databind.JsonNode;
import com.udyam.registry.GstinDetails;
import com.udyam.registry.GstinVerificationService;
import com.udyam.rules.data.BusinessTypeRef;
import com.udyam.rules.data.CatalogueProduct;
import com.udyam.rules.data.CityRef;
import com.udyam.rules.data.RulesData;
import com.udyam.rules.data.RulesDataProvider;
import com.udyam.rules.profile.Segment;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Turns each kind of lead into a LeadProfile. Advisor sessions and orders count as "Website form" (the model's
 * liveLeadSource): they have no lead source, and that is the closest history we have for them.
 */
@Component
public class LeadFeatureMapper {

    public static final String LIVE_LEAD_SOURCE = "Website form";

    private final RulesDataProvider rules;
    private final GstinVerificationService gstins;
    /** The registry never changes, and a lookup per queue row per request adds up over Neon. */
    private final Map<String, Optional<GstinDetails>> registry = new ConcurrentHashMap<>();

    public LeadFeatureMapper(RulesDataProvider rules, GstinVerificationService gstins) {
        this.rules = rules;
        this.gstins = gstins;
    }

    Optional<GstinDetails> registryDetails(String gstin) {
        return registry.computeIfAbsent(gstin, gstins::find);
    }

    public LeadProfile fromHistorical(BackofficeStore.HistoricalLead l) {
        RulesData data = rules.get();
        boolean gstProduct = l.productIds().stream()
                .anyMatch(id -> data.catalogue().find(id).map(CatalogueProduct::requiresGstin).orElse(false));
        return new LeadProfile(l.leadSource(), l.businessType(), l.segment(), l.city(), l.tier(), l.employees(), l.sites(),
                gstStatus(l.registryStatus(), l.hasGstin()), l.hasUdyam(), l.monthlySpendInr(), l.currentInternet(),
                l.productIds().size(), gstProduct);
    }

    /** An advisor session: only what the owner has told the advisor so far. */
    public LeadProfile fromSession(JsonNode profile) {
        return fromProfile(profile, Map.of());
    }

    /** An order: the advisor's profile, plus what its KYC documents have shown (fields by doc type). */
    public LeadProfile fromOrder(JsonNode profile, Map<String, JsonNode> kycFields) {
        return fromProfile(profile, kycFields);
    }

    private LeadProfile fromProfile(JsonNode profile, Map<String, JsonNode> kyc) {
        JsonNode p = profile == null ? com.fasterxml.jackson.databind.node.MissingNode.getInstance() : profile;
        String businessType = text(p, "businessType");
        String city = text(p, "city");
        String gstin = Optional.ofNullable(text(kyc.get("GST_CERTIFICATE"), "gstin")).orElse(text(p, "gstin"));
        Boolean hasUdyam = text(kyc.get("UDYAM_CERTIFICATE"), "udyamNumber") != null ? Boolean.TRUE : null;
        return new LeadProfile(LIVE_LEAD_SOURCE, businessType, segmentOf(p, businessType), city, tierOf(p, city),
                integer(p, "employees"), integer(p, "sites"), gstinStatus(gstin), hasUdyam,
                integer(p, "monthlyTelecomSpendInr"), text(p, "currentInternet"), null, null);
    }

    private String segmentOf(JsonNode p, String businessType) {
        return Segment.parse(text(p, "segment")).map(Segment::displayName)
                .or(() -> rules.get().businessType(businessType).map(BusinessTypeRef::segment))
                .orElse(null);
    }

    private Integer tierOf(JsonNode p, String city) {
        Integer given = integer(p, "cityTier");
        return given != null ? given : rules.get().city(city).map(CityRef::tier).orElse(null);
    }

    /**
     * Status from the registry. A GSTIN the owner only declared, or one the registry doesn't know, is unverified:
     * it counts as none, the reference level.
     */
    private String gstinStatus(String gstin) {
        if (gstin == null) {
            return LeadProfile.GST_NONE;
        }
        return registryDetails(gstin).map(d -> d.active() ? LeadProfile.GST_ACTIVE : LeadProfile.GST_INACTIVE)
                .orElse(LeadProfile.GST_NONE);
    }

    private static String gstStatus(String registryStatus, boolean hasGstin) {
        if (!hasGstin) {
            return LeadProfile.GST_NONE;
        }
        return GstinDetails.ACTIVE.equals(registryStatus) ? LeadProfile.GST_ACTIVE : LeadProfile.GST_INACTIVE;
    }

    private static String text(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) {
            return null;
        }
        String value = node.get(field).asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static Integer integer(JsonNode node, String field) {
        return node.hasNonNull(field) && node.get(field).canConvertToInt() ? node.get(field).asInt() : null;
    }
}
