package com.udyam.tracker;

import com.udyam.common.errors.ApiException;
import com.udyam.common.errors.NotFoundException;
import com.udyam.kyc.KycStore;
import com.udyam.orders.EventActor;
import com.udyam.orders.OrderStatus;
import com.udyam.orders.OrderStatusMachine;
import com.udyam.orders.OrderStore;
import com.udyam.orders.TrackerNotes;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Customer self-scheduling: a slot from the overall ETA to ETA + 14 days, not on a Sunday. Booking is allowed once the
 * documents are verified, and again to reschedule. The promised activation date follows the slot chosen.
 */
@Service
public class InstallSlotService {

    public static final int WINDOW_DAYS = 14;
    public static final List<String> SLOTS = List.of("MORNING", "AFTERNOON", "EVENING");

    private static final Set<String> BOOKABLE = Set.of(OrderStatus.KYC_VERIFIED, OrderStatus.INSTALL_SCHEDULED);

    private final OrderStore orders;
    private final KycStore kyc;
    private final OrderStatusMachine machine;

    public InstallSlotService(OrderStore orders, KycStore kyc, OrderStatusMachine machine) {
        this.orders = orders;
        this.kyc = kyc;
        this.machine = machine;
    }

    public record SlotDate(LocalDate date, boolean available) {
    }

    /** Sundays are listed as unavailable so the picker can show them disabled. */
    public record AvailableSlots(boolean bookable, LocalDate etaDate, LocalDate latestDate, List<SlotDate> dates,
                                 List<String> slots, TrackerView.InstallSlot current) {
    }

    public AvailableSlots available(UUID orderId) {
        OrderStore.Order order = order(orderId);
        OrderStore.Tracking tracking = orders.tracking(orderId);
        TrackerView.InstallSlot current = tracking.slotDate() == null ? null
                : new TrackerView.InstallSlot(tracking.slotDate(), tracking.slot());
        if (!BOOKABLE.contains(order.status())) {
            return new AvailableSlots(false, null, null, List.of(), SLOTS, current);
        }
        LocalDate eta = eta(order);
        List<SlotDate> dates = new ArrayList<>();
        for (LocalDate d = eta; !d.isAfter(eta.plusDays(WINDOW_DAYS)); d = d.plusDays(1)) {
            dates.add(new SlotDate(d, d.getDayOfWeek() != DayOfWeek.SUNDAY));
        }
        return new AvailableSlots(true, eta, eta.plusDays(WINDOW_DAYS), dates, SLOTS, current);
    }

    @Transactional
    public void book(UUID orderId, LocalDate date, String slot, String actor) {
        OrderStore.Order order = order(orderId);
        if (!BOOKABLE.contains(order.status())) {
            throw new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION",
                    "An installation slot can be chosen once your documents are verified");
        }
        LocalDate eta = eta(order);
        validate(date, slot, eta);
        machine.transition(orderId, OrderStatus.INSTALL_SCHEDULED, actor, TrackerNotes.slot(date, slot));
        orders.setInstallSlot(orderId, date, slot);
        kyc.setActivationDate(orderId, date.isAfter(eta) ? date : eta);
    }

    /** The earliest bookable slot: what the demo controls take when skipping the picker. */
    @Transactional
    public LocalDate bookEarliest(UUID orderId) {
        LocalDate eta = eta(order(orderId));
        LocalDate date = eta.getDayOfWeek() == DayOfWeek.SUNDAY ? eta.plusDays(1) : eta;
        book(orderId, date, SLOTS.get(0), EventActor.DEMO);
        return date;
    }

    private static void validate(LocalDate date, String slot, LocalDate eta) {
        List<String> errors = new ArrayList<>();
        if (date == null) {
            errors.add("Choose a date");
        } else {
            if (date.isBefore(eta)) {
                errors.add("The date can't be before " + eta + ", when your services can be ready");
            }
            if (date.isAfter(eta.plusDays(WINDOW_DAYS))) {
                errors.add("The date must be on or before " + eta.plusDays(WINDOW_DAYS));
            }
            if (date.getDayOfWeek() == DayOfWeek.SUNDAY) {
                errors.add("We don't install on Sundays");
            }
        }
        if (slot == null || !SLOTS.contains(slot)) {
            errors.add("slot must be MORNING, AFTERNOON or EVENING");
        }
        if (!errors.isEmpty()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_SLOT", errors.get(0), errors);
        }
    }

    private LocalDate eta(OrderStore.Order order) {
        LocalDate verified = EtaCalculator.verifiedDate(orders.events(order.id()));
        return EtaCalculator.overall(verified, EtaCalculator.items(order.quote(), verified));
    }

    private OrderStore.Order order(UUID orderId) {
        return orders.find(orderId).orElseThrow(() -> new NotFoundException("Order not found"));
    }
}
