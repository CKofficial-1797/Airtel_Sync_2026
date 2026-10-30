package com.udyam.tracker;

import static com.udyam.orders.OrderStatus.ACTIVE;
import static com.udyam.orders.OrderStatus.CANCELLED;
import static com.udyam.orders.OrderStatus.INSTALLED;
import static com.udyam.orders.OrderStatus.INSTALL_SCHEDULED;
import static com.udyam.orders.OrderStatus.KYC_VERIFIED;
import static com.udyam.orders.OrderStatus.ON_HOLD;

import com.udyam.common.errors.ApiException;
import com.udyam.common.errors.NotFoundException;
import com.udyam.orders.EventActor;
import com.udyam.orders.OrderStatusMachine;
import com.udyam.orders.OrderStore;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Moves an order along for the demo video (app.demo-mode=true only), through the same status machine with actor DEMO.
 * It starts where the real KYC ends: demo controls can't produce a verification that never happened.
 */
@Service
public class DemoService {

    private static final Set<String> DEMO_TARGETS = Set.of(INSTALL_SCHEDULED, INSTALLED, ACTIVE, ON_HOLD, CANCELLED);

    private final boolean enabled;
    private final OrderStore orders;
    private final OrderStatusMachine machine;
    private final InstallSlotService slots;
    private final TrackerService tracker;

    public DemoService(@Value("${app.demo-mode:false}") boolean enabled, OrderStore orders, OrderStatusMachine machine,
                       InstallSlotService slots, TrackerService tracker) {
        this.enabled = enabled;
        this.orders = orders;
        this.machine = machine;
        this.slots = slots;
        this.tracker = tracker;
    }

    public boolean enabled() {
        return enabled;
    }

    public record AdvanceRequest(String toStatus, String onHoldReason, String note) {
    }

    /** toStatus given: that move. Otherwise a hold reason puts it on hold, and with neither it takes the next step. */
    @Transactional
    public TrackerView advance(UUID orderId, AdvanceRequest request) {
        if (!enabled) {
            throw new NotFoundException("No endpoint at this path");
        }
        AdvanceRequest r = request == null ? new AdvanceRequest(null, null, null) : request;
        String status = orders.find(orderId).orElseThrow(() -> new NotFoundException("Order not found")).status();

        if (r.onHoldReason() != null || ON_HOLD.equals(r.toStatus())) {
            hold(orderId, r);
        } else if (r.toStatus() != null) {
            moveTo(orderId, status, r.toStatus(), r.note());
        } else {
            moveTo(orderId, status, next(orderId, status), r.note());
        }
        return tracker.view(orderId, false);
    }

    private void hold(UUID orderId, AdvanceRequest r) {
        HoldReason reason = HoldReason.parse(r.onHoldReason()).orElseThrow(() -> new ApiException(HttpStatus.BAD_REQUEST,
                "INVALID_HOLD_REASON", "onHoldReason must be SITE_NOT_FEASIBLE, CUSTOMER_UNAVAILABLE or PAYMENT_PENDING"));
        machine.hold(orderId, reason.name(), r.note(), EventActor.DEMO);
    }

    private void moveTo(UUID orderId, String status, String to, String note) {
        if (ON_HOLD.equals(status) && to.equals(orders.tracking(orderId).holdPreviousStatus())) {
            machine.resume(orderId, EventActor.DEMO);
        } else if (!DEMO_TARGETS.contains(to)) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION",
                    "Demo controls can't move an order to " + to + " from " + status);
        } else if (INSTALL_SCHEDULED.equals(to) && KYC_VERIFIED.equals(status)) {
            slots.bookEarliest(orderId);
        } else {
            // An installation counts on its slot date, or the timeline would show it before the visit.
            LocalDate slotDate = orders.tracking(orderId).slotDate();
            boolean visit = INSTALLED.equals(to) || ACTIVE.equals(to);
            String text = note == null ? "Demo: " + to : note;
            if (visit && slotDate != null) {
                machine.transition(orderId, to, EventActor.DEMO, text, slotDate);
            } else {
                machine.transition(orderId, to, EventActor.DEMO, text);
            }
        }
    }

    private String next(UUID orderId, String status) {
        return switch (status) {
            case KYC_VERIFIED -> INSTALL_SCHEDULED;
            case INSTALL_SCHEDULED -> INSTALLED;
            case INSTALLED -> ACTIVE;
            case ON_HOLD -> orders.tracking(orderId).holdPreviousStatus();
            default -> throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION",
                    "No next demo step from " + status + ": demo controls run from verified documents to active");
        };
    }
}
