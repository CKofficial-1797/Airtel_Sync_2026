package com.udyam.advisor;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Frontend-facing advisor chat API. */
@RestController
@RequestMapping("/api/advisor/sessions")
public class AdvisorController {

    private final AdvisorService advisor;

    public AdvisorController(AdvisorService advisor) {
        this.advisor = advisor;
    }

    public record CreateSessionRequest(@Pattern(regexp = "en|hi", message = "must be en or hi") String uiLanguage) {
    }

    public record MessageRequest(@NotBlank @Size(max = 1000) String text) {
    }

    @PostMapping
    public AdvisorService.SessionCreated create(@Valid @RequestBody(required = false) CreateSessionRequest request) {
        return advisor.createSession(request == null ? null : request.uiLanguage());
    }

    @PostMapping("/{sessionId}/messages")
    public AdvisorService.AdvisorReply message(@PathVariable UUID sessionId, @Valid @RequestBody MessageRequest request) {
        return advisor.sendMessage(sessionId, request.text().trim());
    }

    @GetMapping("/{sessionId}")
    public AdvisorService.SessionView session(@PathVariable UUID sessionId) {
        return advisor.getSession(sessionId);
    }
}
