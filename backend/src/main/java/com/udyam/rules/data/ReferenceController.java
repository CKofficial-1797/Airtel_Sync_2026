package com.udyam.rules.data;

import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Reference lists derived from smb_leads (used by the advisor and the frontend pickers). */
@RestController
@RequestMapping("/api/reference")
public class ReferenceController {

    private final RulesDataProvider data;

    public ReferenceController(RulesDataProvider data) {
        this.data = data;
    }

    @GetMapping("/cities")
    public List<CityRef> cities() {
        return data.get().cities();
    }

    @GetMapping("/business-types")
    public List<BusinessTypeRef> businessTypes() {
        return data.get().businessTypes();
    }
}
