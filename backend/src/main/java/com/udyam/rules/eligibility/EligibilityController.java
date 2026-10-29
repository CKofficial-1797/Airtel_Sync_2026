package com.udyam.rules.eligibility;

import com.udyam.rules.profile.BusinessProfile;
import com.udyam.rules.profile.ProfileResolver;
import com.udyam.rules.profile.ResolvedProfile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/eligibility")
public class EligibilityController {

    private final ProfileResolver resolver;
    private final EligibilityService eligibility;

    public EligibilityController(ProfileResolver resolver, EligibilityService eligibility) {
        this.resolver = resolver;
        this.eligibility = eligibility;
    }

    public record EligibilityRequest(@Valid @NotNull BusinessProfile profile, List<String> bundleProductIds) {
    }

    public record EligibilityResponse(ResolvedProfile resolvedProfile, List<String> bundleProductIds,
                                      List<EligibilityResult> results) {
    }

    /** All 25 products with eligibility + reasons; dependencies are checked relative to bundleProductIds. */
    @PostMapping
    public EligibilityResponse check(@Valid @RequestBody EligibilityRequest request) {
        ResolvedProfile profile = resolver.resolve(request.profile());
        List<String> bundle = request.bundleProductIds() == null ? List.of() : request.bundleProductIds();
        return new EligibilityResponse(profile, bundle, eligibility.checkAll(profile, bundle));
    }
}
