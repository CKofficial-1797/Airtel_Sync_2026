package com.udyam.leads;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin")
public class DataSummaryController {

    private final DataSummaryService service;

    public DataSummaryController(DataSummaryService service) {
        this.service = service;
    }

    @GetMapping("/data-summary")
    public DataSummary dataSummary() {
        return service.summary();
    }
}
