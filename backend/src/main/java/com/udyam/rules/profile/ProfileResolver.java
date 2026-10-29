package com.udyam.rules.profile;

import com.udyam.common.errors.ApiException;
import com.udyam.registry.GstinDetails;
import com.udyam.registry.GstinVerificationService;
import com.udyam.registry.validation.GstinValidationResult;
import com.udyam.rules.data.BusinessTypeRef;
import com.udyam.rules.data.CityRef;
import com.udyam.rules.data.RulesData;
import com.udyam.rules.data.RulesDataProvider;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** Turns a BusinessProfile into a ResolvedProfile (city → tier, business type → segment, GSTIN status). */
@Service
public class ProfileResolver {

    private static final Pattern MANUFACTURING =
            Pattern.compile("\\b(unit|workshop|factory|press|moulding|processing)\\b", Pattern.CASE_INSENSITIVE);

    private final RulesDataProvider data;
    private final GstinVerificationService gstins;

    public ProfileResolver(RulesDataProvider data, GstinVerificationService gstins) {
        this.data = data;
        this.gstins = gstins;
    }

    public ResolvedProfile resolve(BusinessProfile p) {
        RulesData d = data.get();
        List<String> warnings = new ArrayList<>();

        // 1. City → tier (+ state)
        String city = null;
        String cityState = null;
        int tier;
        if (hasText(p.city())) {
            Optional<CityRef> known = d.city(p.city());
            if (known.isPresent()) {
                city = known.get().city();
                cityState = known.get().state();
                tier = known.get().tier();
                if (p.cityTier() != null && p.cityTier() != tier) {
                    warnings.add(city + " is a tier-" + tier + " city; the tier given (" + p.cityTier() + ") was ignored");
                }
            } else if (p.cityTier() != null) {
                city = p.city().trim();
                tier = p.cityTier();
                warnings.add(city + " is not in our city list; using tier " + tier + " as given");
            } else {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CITY_TIER_REQUIRED",
                        "Unknown city '" + p.city().trim() + "'. Please provide cityTier (1 or 2).");
            }
        } else if (p.cityTier() != null) {
            tier = p.cityTier();
        } else {
            throw new ApiException(HttpStatus.BAD_REQUEST, "CITY_REQUIRED", "Provide city or cityTier (1 or 2)");
        }

        // 2. Business type → segment
        String businessType = hasText(p.businessType()) ? p.businessType().trim() : null;
        Optional<BusinessTypeRef> knownType = d.businessType(businessType);
        if (knownType.isPresent()) {
            businessType = knownType.get().businessType();
        }
        Segment segment;
        String segmentSource;
        if (hasText(p.segment())) {
            segment = Segment.parse(p.segment()).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                    "INVALID_SEGMENT", "Unknown segment '" + p.segment() + "'"));
            segmentSource = "GIVEN";
        } else if (knownType.isPresent()) {
            segment = Segment.parse(knownType.get().segment()).orElseThrow();
            segmentSource = "BUSINESS_TYPE";
        } else {
            segment = inferSegment(businessType, p.employees(), p.sites());
            segmentSource = "INFERRED";
            warnings.add((businessType == null ? "No business type given" : "Business type '" + businessType
                    + "' is not in our list") + "; segment inferred as " + segment.displayName());
        }

        // 3. GSTIN status (typed input: no OCR correction)
        GstinStatus gstinStatus = GstinStatus.NONE;
        String gstin = null;
        String registryStatus = null;
        List<String> gstinErrors = List.of();
        GstinAutofill autofill = null;
        if (hasText(p.gstin())) {
            GstinValidationResult v = gstins.validate(p.gstin());
            gstin = v.normalized();
            Optional<GstinDetails> found = v.valid() ? gstins.find(gstin) : Optional.empty();
            if (!v.valid()) {
                gstinStatus = GstinStatus.INVALID;
                gstinErrors = v.errors();
            } else if (found.isEmpty()) {
                gstinStatus = GstinStatus.INVALID;
                gstinErrors = List.of("GSTIN not found in the registry");
            } else {
                GstinDetails g = found.get();
                gstinStatus = g.active() ? GstinStatus.VERIFIED_ACTIVE : GstinStatus.INACTIVE;
                registryStatus = g.status();
                autofill = new GstinAutofill(g.legalName(), g.tradeName(), g.state(), g.principalPlaceOfBusiness());
                if (cityState != null && !cityState.equals(g.state())) {
                    warnings.add("GSTIN is registered in " + g.state() + " but " + city + " is in " + cityState);
                }
                if (!g.active()) {
                    warnings.add("GSTIN status is " + g.status() + "; it counts as no GSTIN for GST-only products");
                }
            }
        } else if (Boolean.TRUE.equals(p.gstinDeclared())) {
            gstinStatus = GstinStatus.DECLARED_UNVERIFIED;
        }

        Set<Need> needs = p.needs() == null || p.needs().isEmpty() ? EnumSet.noneOf(Need.class) : EnumSet.copyOf(p.needs());
        return new ResolvedProfile(businessType, segment, segment.code(), segmentSource, city, tier, cityState,
                p.employees(), p.sites(), gstinStatus, gstin, registryStatus, gstinErrors, autofill,
                p.currentInternet(), p.monthlyTelecomSpendInr(), Boolean.TRUE.equals(p.fibreUnavailable()), needs,
                p.officeUsers(), p.mobileLines(), p.cameras(), p.vehicles(), p.machines(), List.copyOf(warnings));
    }

    /** Fallback for business types not in the data. */
    static Segment inferSegment(String businessType, int employees, int sites) {
        if (sites >= 3) {
            return Segment.MULTI;
        }
        if (businessType != null && MANUFACTURING.matcher(businessType).find()) {
            return Segment.MFG;
        }
        if (employees <= 6 && sites == 1) {
            return Segment.MICRO;
        }
        return Segment.PROF;
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
