package com.udyam.kyc.harness;

import com.udyam.advisor.InternalTokenFilter;
import com.udyam.common.errors.ApiException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Arrays;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Dev-only: proves the answer key. Requires X-Internal-Token (the same secret the ai-service uses). */
@RestController
public class SpecimenHarnessController {

    private final SpecimenHarness harness;

    public SpecimenHarnessController(SpecimenHarness harness) {
        this.harness = harness;
    }

    /** ?applicants=K01,K03 runs a subset (fewer Gemini calls on a live run); no parameter runs all 12. */
    @PostMapping("/api/admin/kyc/run-specimens")
    public SpecimenHarness.Result run(@RequestParam(required = false) String applicants, HttpServletRequest request) {
        if (!InternalTokenFilter.AI_SERVICE.equals(request.getAttribute(InternalTokenFilter.CALLER_ATTRIBUTE))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "INTERNAL_TOKEN_REQUIRED",
                    "This endpoint needs a valid X-Internal-Token header");
        }
        Set<String> subset = applicants == null ? Set.of() : Arrays.stream(applicants.split(","))
                .map(s -> s.trim().toUpperCase(Locale.ROOT)).filter(s -> !s.isEmpty()).collect(Collectors.toSet());
        return harness.run(subset);
    }
}
