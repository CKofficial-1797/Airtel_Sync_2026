package com.udyam.tracker;

import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/** Dev only. Advancing returns 404 unless app.demo-mode=true; the status endpoint lets the UI hide its panel. */
@RestController
public class DemoController {

    private final DemoService demo;

    public DemoController(DemoService demo) {
        this.demo = demo;
    }

    public record DemoStatus(boolean enabled) {
    }

    @GetMapping("/api/demo/status")
    public DemoStatus status() {
        return new DemoStatus(demo.enabled());
    }

    @PostMapping("/api/demo/orders/{orderId}/advance")
    public TrackerView advance(@PathVariable UUID orderId,
                               @RequestBody(required = false) DemoService.AdvanceRequest request) {
        return demo.advance(orderId, request);
    }
}
