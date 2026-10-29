package com.udyam.rules.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/audit")
public class MisSellingAuditController {

    private final MisSellingAuditService audit;

    public MisSellingAuditController(MisSellingAuditService audit) {
        this.audit = audit;
    }

    @GetMapping("/mis-selling")
    public MisSellingReport misSelling() {
        return audit.report();
    }
}
