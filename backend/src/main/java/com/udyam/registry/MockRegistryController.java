package com.udyam.registry;

import com.udyam.common.errors.ApiException;
import com.udyam.common.privacy.Masking;
import com.udyam.registry.validation.GstinValidationResult;
import com.udyam.registry.validation.UdyamValidationResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * MOCK government registry APIs, backed by the provided synthetic CSVs (not real GSTN/Udyam services).
 * Error codes: INVALID_FORMAT, INVALID_CHECKSUM (400), NOT_FOUND (404).
 */
@RestController
@RequestMapping("/api/mock")
public class MockRegistryController {

    private static final Logger log = LoggerFactory.getLogger(MockRegistryController.class);

    private final GstinVerificationService gstinService;
    private final UdyamLookupService udyamService;

    public MockRegistryController(GstinVerificationService gstinService, UdyamLookupService udyamService) {
        this.gstinService = gstinService;
        this.udyamService = udyamService;
    }

    public record VerifyGstinRequest(@NotBlank String gstin) {
    }

    @GetMapping("/gstin/{gstin}")
    public GstinLookupResponse gstin(@PathVariable String gstin) {
        GstinValidationResult v = gstinService.validate(gstin);
        if (!v.formatValid()) {
            log.info("Mock GSTIN lookup {} -> INVALID_FORMAT", Masking.gstin(v.normalized()));
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FORMAT", "GSTIN format is invalid", v.errors());
        }
        if (!v.checksumValid()) {
            log.info("Mock GSTIN lookup {} -> INVALID_CHECKSUM", Masking.gstin(v.normalized()));
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_CHECKSUM", "GSTIN checksum is invalid", v.errors());
        }
        GstinDetails details = gstinService.find(v.normalized()).orElseThrow(() -> {
            log.info("Mock GSTIN lookup {} -> NOT_FOUND", Masking.gstin(v.normalized()));
            return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "GSTIN not found in the registry");
        });
        log.info("Mock GSTIN lookup {} -> found ({})", Masking.gstin(v.normalized()), details.status());
        return GstinLookupResponse.from(details);
    }

    @PostMapping("/gstin/verify")
    public GstinVerification verifyGstin(@Valid @RequestBody VerifyGstinRequest request) {
        GstinVerification result = gstinService.verify(request.gstin());
        log.info("Mock GSTIN verify {} -> found={} corrected={}", Masking.gstin(result.normalized()),
                result.found(), result.correction() != null);
        return result;
    }

    @GetMapping("/udyam/{udyamNumber}")
    public UdyamDetails udyam(@PathVariable String udyamNumber) {
        UdyamValidationResult v = udyamService.validate(udyamNumber);
        if (!v.formatValid()) {
            log.info("Mock Udyam lookup {} -> INVALID_FORMAT", Masking.udyam(v.normalized()));
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_FORMAT", "Udyam number format is invalid",
                    v.errors());
        }
        UdyamDetails details = udyamService.find(v.normalized()).orElseThrow(() -> {
            log.info("Mock Udyam lookup {} -> NOT_FOUND", Masking.udyam(v.normalized()));
            return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Udyam number not found in the registry");
        });
        log.info("Mock Udyam lookup {} -> found", Masking.udyam(v.normalized()));
        return details;
    }
}
