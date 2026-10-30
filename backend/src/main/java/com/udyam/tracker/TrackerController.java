package com.udyam.tracker;

import com.udyam.orders.EventActor;
import jakarta.servlet.http.HttpServletRequest;
import java.time.LocalDate;
import java.util.UUID;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class TrackerController {

    private final TrackerService tracker;
    private final InstallSlotService slots;
    private final TrackLookupService lookup;

    public TrackerController(TrackerService tracker, InstallSlotService slots, TrackLookupService lookup) {
        this.tracker = tracker;
        this.slots = slots;
        this.lookup = lookup;
    }

    /** auto=1 marks the page's background refresh: it isn't the owner checking, so it isn't counted. */
    @GetMapping("/api/orders/{orderId}/tracker")
    public TrackerView tracker(@PathVariable UUID orderId, @RequestParam(defaultValue = "false") boolean auto) {
        return tracker.view(orderId, !auto);
    }

    @GetMapping("/api/orders/{orderId}/install-slots")
    public InstallSlotService.AvailableSlots installSlots(@PathVariable UUID orderId) {
        return slots.available(orderId);
    }

    public record SlotRequest(LocalDate date, String slot) {
    }

    @PostMapping("/api/orders/{orderId}/install-slot")
    public TrackerView bookSlot(@PathVariable UUID orderId, @RequestBody SlotRequest request) {
        slots.book(orderId, request.date(), request.slot(), EventActor.CUSTOMER);
        return tracker.view(orderId, false);
    }

    public record LookupRequest(String orderRef, String pincode) {
    }

    public record LookupResponse(UUID orderId) {
    }

    @PostMapping("/api/track/lookup")
    public LookupResponse lookup(@RequestBody LookupRequest request, HttpServletRequest http) {
        return new LookupResponse(lookup.lookup(request.orderRef(), request.pincode(), http.getRemoteAddr()));
    }
}
