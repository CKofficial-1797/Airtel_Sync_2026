package com.udyam.rules.profile;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/profile")
public class ProfileController {

    private final ProfileResolver resolver;

    public ProfileController(ProfileResolver resolver) {
        this.resolver = resolver;
    }

    /** Body: a BusinessProfile. Used by the advisor for GSTIN autofill and to check what it has collected. */
    @PostMapping("/resolve")
    public ResolvedProfile resolve(@Valid @RequestBody BusinessProfile profile) {
        return resolver.resolve(profile);
    }
}
