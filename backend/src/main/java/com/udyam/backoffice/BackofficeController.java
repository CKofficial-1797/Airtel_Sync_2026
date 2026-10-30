package com.udyam.backoffice;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** All of it sits behind BackofficeKeyFilter. */
@RestController
@RequestMapping("/api/backoffice")
public class BackofficeController {

    private final BackofficeService service;
    private final InsightsService insights;

    public BackofficeController(BackofficeService service, InsightsService insights) {
        this.service = service;
        this.insights = insights;
    }

    @GetMapping("/queue")
    public BackofficeViews.QueuePage queue(@RequestParam(required = false) String kind, @RequestParam(required = false) String segment,
                                           @RequestParam(required = false) Integer tier, @RequestParam(required = false) String band,
                                           @RequestParam(required = false) String channel, @RequestParam(required = false) String status,
                                           @RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size,
                                           @RequestParam(required = false) String sort) {
        return service.queue(new BackofficeService.QueueQuery(kind, segment, tier, band, channel, status, page, size, sort));
    }

    @GetMapping("/leads/{kind}/{ref}")
    public BackofficeViews.LeadDetail lead(@PathVariable String kind, @PathVariable String ref) {
        return service.detail(kind, ref);
    }

    public record AssignRequest(String channel, String assigneeLabel, String note) {
    }

    @PostMapping("/leads/{kind}/{ref}/assign")
    public BackofficeViews.LeadDetail assign(@PathVariable String kind, @PathVariable String ref, @RequestBody AssignRequest body) {
        return service.assign(kind, ref, body.channel(), body.assigneeLabel(), body.note());
    }

    public record StatusRequest(String status, String note) {
    }

    @PostMapping("/leads/{kind}/{ref}/status")
    public BackofficeViews.LeadDetail status(@PathVariable String kind, @PathVariable String ref, @RequestBody StatusRequest body) {
        return service.updateStatus(kind, ref, body.status(), body.note());
    }

    @GetMapping("/reengagement")
    public BackofficeViews.Reengagement reengagement(@RequestParam(required = false) Integer page, @RequestParam(required = false) Integer size) {
        return service.reengagement(page, size);
    }

    @GetMapping("/insights")
    public BackofficeViews.Insights insights() {
        return insights.insights();
    }

    @Configuration
    static class ClockConfig {
        @Bean
        Clock backofficeClock() {
            return Clock.systemUTC();
        }
    }
}
