package com.udyam.orders;

import static com.udyam.orders.OrderStatus.ACTIVE;
import static com.udyam.orders.OrderStatus.CANCELLED;
import static com.udyam.orders.OrderStatus.INSTALLED;
import static com.udyam.orders.OrderStatus.INSTALL_SCHEDULED;
import static com.udyam.orders.OrderStatus.KYC_ACTION_REQUIRED;
import static com.udyam.orders.OrderStatus.KYC_IN_PROGRESS;
import static com.udyam.orders.OrderStatus.KYC_PENDING_CONSENT;
import static com.udyam.orders.OrderStatus.KYC_REJECTED;
import static com.udyam.orders.OrderStatus.KYC_VERIFIED;
import static com.udyam.orders.OrderStatus.ON_HOLD;
import static com.udyam.orders.OrderStatus.QUOTE_ACCEPTED;
import static java.util.Map.entry;

import com.udyam.common.config.BusinessClock;
import com.udyam.common.errors.ApiException;
import com.udyam.common.errors.NotFoundException;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The one place order statuses change. Every transition is checked against ALLOWED and writes an order_event;
 * anything else is 409 INVALID_TRANSITION.
 *
 * Two exits exist only for DPDP withdrawal: KYC_REJECTED and the post-KYC states back to KYC_PENDING_CONSENT
 * (data must be deletable, and it is also how a rejected owner applies again after fixing the cause).
 * Once installed, the KYC records back a live connection: no withdrawal or cancel from there.
 */
@Service
public class OrderStatusMachine {

    private static final Map<String, Set<String>> ALLOWED = Map.ofEntries(
            entry(QUOTE_ACCEPTED, Set.of(KYC_IN_PROGRESS, KYC_PENDING_CONSENT, CANCELLED)),
            entry(KYC_PENDING_CONSENT, Set.of(KYC_IN_PROGRESS, CANCELLED)),
            entry(KYC_IN_PROGRESS, Set.of(KYC_VERIFIED, KYC_ACTION_REQUIRED, KYC_REJECTED, KYC_PENDING_CONSENT, CANCELLED)),
            entry(KYC_ACTION_REQUIRED, Set.of(KYC_IN_PROGRESS, KYC_PENDING_CONSENT, CANCELLED)),
            entry(KYC_REJECTED, Set.of(KYC_PENDING_CONSENT)),
            entry(KYC_VERIFIED, Set.of(INSTALL_SCHEDULED, ON_HOLD, KYC_PENDING_CONSENT, CANCELLED)),
            entry(INSTALL_SCHEDULED, Set.of(INSTALL_SCHEDULED, INSTALLED, ON_HOLD, KYC_PENDING_CONSENT, CANCELLED)),
            entry(INSTALLED, Set.of(ACTIVE, ON_HOLD)),
            entry(ON_HOLD, Set.of(KYC_VERIFIED, INSTALL_SCHEDULED, INSTALLED, KYC_PENDING_CONSENT, CANCELLED)),
            entry(ACTIVE, Set.of()),
            entry(CANCELLED, Set.of()));

    /** A hold can only return to the status it paused. */
    private static final Set<String> RESUME_TARGETS = Set.of(KYC_VERIFIED, INSTALL_SCHEDULED, INSTALLED);

    private final OrderStore store;
    private final BusinessClock clock;

    public OrderStatusMachine(OrderStore store, BusinessClock clock) {
        this.store = store;
        this.clock = clock;
    }

    /** The rule alone, for one from/to pair (ignores what a hold paused). */
    public static boolean isAllowed(String from, String to) {
        return ALLOWED.getOrDefault(from, Set.of()).contains(to);
    }

    public static Set<String> allStatuses() {
        return ALLOWED.keySet();
    }

    /** Whether this order, in its current state, may move to the given status. */
    public boolean canTransition(UUID orderId, String to) {
        String from = status(orderId);
        if (!isAllowed(from, to)) {
            return false;
        }
        if (!ON_HOLD.equals(from)) {
            return true;
        }
        String paused = store.tracking(orderId).holdPreviousStatus();
        if (RESUME_TARGETS.contains(to)) {
            return to.equals(paused);
        }
        return !INSTALLED.equals(paused); // no withdrawal or cancel of an installed order
    }

    /** Once installed (also when an installed order is on hold), the KYC records must stay. */
    public boolean withdrawalBlocked(UUID orderId) {
        String status = status(orderId);
        return INSTALLED.equals(status) || ACTIVE.equals(status)
                || (ON_HOLD.equals(status) && INSTALLED.equals(store.tracking(orderId).holdPreviousStatus()));
    }

    @Transactional
    public void transition(UUID orderId, String to, String actor, String note) {
        transition(orderId, to, actor, note, clock.today());
    }

    /** businessDate: the date the event counts for (an installation counts on its slot date, not on "today"). */
    @Transactional
    public void transition(UUID orderId, String to, String actor, String note, LocalDate businessDate) {
        if (ON_HOLD.equals(to)) {
            throw new IllegalArgumentException("Use hold(): a hold needs a reason");
        }
        String from = status(orderId);
        if (!canTransition(orderId, to)) {
            throw invalid(from, to);
        }
        if (ON_HOLD.equals(from)) {
            store.setHold(orderId, null, null, null);
        }
        if (KYC_PENDING_CONSENT.equals(to) || CANCELLED.equals(to)) {
            store.setInstallSlot(orderId, null, null); // the visit is off
        }
        apply(orderId, to, actor, note, businessDate);
    }

    @Transactional
    public void hold(UUID orderId, String reasonCode, String note, String actor) {
        String from = status(orderId);
        if (!isAllowed(from, ON_HOLD)) {
            throw invalid(from, ON_HOLD);
        }
        store.setHold(orderId, reasonCode, note, from);
        apply(orderId, ON_HOLD, actor, note == null ? reasonCode : reasonCode + ": " + note, clock.today());
    }

    /** Back to the status the hold paused. Returns that status. */
    @Transactional
    public String resume(UUID orderId, String actor) {
        String from = status(orderId);
        String paused = ON_HOLD.equals(from) ? store.tracking(orderId).holdPreviousStatus() : null;
        if (paused == null) {
            throw invalid(from, "the previous status");
        }
        store.setHold(orderId, null, null, null);
        apply(orderId, paused, actor, TrackerNotes.RESUMED, clock.today());
        return paused;
    }

    private void apply(UUID orderId, String to, String actor, String note, LocalDate businessDate) {
        store.updateStatus(orderId, to);
        store.addEvent(orderId, to, note, actor, businessDate);
    }

    private String status(UUID orderId) {
        return store.find(orderId).orElseThrow(() -> new NotFoundException("Order not found: " + orderId)).status();
    }

    private static ApiException invalid(String from, String to) {
        return new ApiException(HttpStatus.CONFLICT, "INVALID_TRANSITION", "An order in " + from + " cannot move to " + to);
    }
}
