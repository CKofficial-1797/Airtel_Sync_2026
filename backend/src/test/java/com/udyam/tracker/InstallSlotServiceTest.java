package com.udyam.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udyam.common.errors.ApiException;
import com.udyam.orders.EventActor;
import com.udyam.orders.OrderStatus;
import com.udyam.orders.OrderStore;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** K03 on a P01 + P07 order: verified 2026-07-15, ETA 2026-07-20 (Monday), window to 2026-08-03. */
class InstallSlotServiceTest {

    private static final LocalDate ETA = LocalDate.of(2026, 7, 20);
    private static final LocalDate A_SUNDAY = LocalDate.of(2026, 7, 26);

    private final TrackerKit kit = new TrackerKit();

    private void assertRejected(UUID id, LocalDate date, String slot, HttpStatus status, String code, String message) {
        assertThatThrownBy(() -> kit.slots.book(id, date, slot, EventActor.CUSTOMER)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(status);
            assertThat(e.getCode()).isEqualTo(code);
            assertThat(e.getMessage()).contains(message);
        });
    }

    @Test
    void lastAllowedAndFirstAllowedDatesBook() {
        UUID id = kit.afterKyc("K03");
        kit.slots.book(id, ETA, "MORNING", EventActor.CUSTOMER);
        assertThat(kit.status(id)).isEqualTo(OrderStatus.INSTALL_SCHEDULED);

        UUID other = kit.afterKyc("K03");
        kit.slots.book(other, ETA.plusDays(14), "EVENING", EventActor.CUSTOMER);
        assertThat(kit.orders.tracking(other).slotDate()).isEqualTo(ETA.plusDays(14));
        assertThat(kit.orders.tracking(other).slot()).isEqualTo("EVENING");
    }

    @Test
    void beforeTheEtaIsRejected() {
        UUID id = kit.afterKyc("K03");
        assertRejected(id, ETA.minusDays(1), "MORNING", HttpStatus.BAD_REQUEST, "INVALID_SLOT", "can't be before 2026-07-20");
        assertThat(kit.status(id)).isEqualTo(OrderStatus.KYC_VERIFIED);
    }

    @Test
    void aSundayIsRejected() {
        assertThat(A_SUNDAY.getDayOfWeek()).isEqualTo(DayOfWeek.SUNDAY);
        assertRejected(kit.afterKyc("K03"), A_SUNDAY, "MORNING", HttpStatus.BAD_REQUEST, "INVALID_SLOT", "Sundays");
    }

    @Test
    void afterTheWindowIsRejected() {
        assertRejected(kit.afterKyc("K03"), ETA.plusDays(15), "MORNING", HttpStatus.BAD_REQUEST, "INVALID_SLOT", "on or before 2026-08-03");
    }

    @Test
    void aBadSlotOrMissingDateIsRejected() {
        UUID id = kit.afterKyc("K03");
        assertRejected(id, ETA, "NIGHT", HttpStatus.BAD_REQUEST, "INVALID_SLOT", "MORNING, AFTERNOON or EVENING");
        assertRejected(id, null, "MORNING", HttpStatus.BAD_REQUEST, "INVALID_SLOT", "Choose a date");
    }

    @Test
    void theWrongStatusIs409() {
        UUID notVerified = kit.newOrder();
        assertRejected(notVerified, ETA, "MORNING", HttpStatus.CONFLICT, "INVALID_TRANSITION", "documents are verified");
        assertRejected(kit.afterKyc("K11"), ETA, "MORNING", HttpStatus.CONFLICT, "INVALID_TRANSITION", "documents are verified");
        assertRejected(kit.afterKyc("K06"), ETA, "MORNING", HttpStatus.CONFLICT, "INVALID_TRANSITION", "documents are verified");

        UUID active = kit.afterKyc("K03");
        kit.slots.book(active, ETA, "MORNING", EventActor.CUSTOMER);
        kit.machine.transition(active, OrderStatus.INSTALLED, EventActor.DEMO, null);
        assertRejected(active, ETA, "MORNING", HttpStatus.CONFLICT, "INVALID_TRANSITION", "documents are verified");
    }

    @Test
    void bookingWritesACustomerEventAndAReschedulingWritesAnother() {
        UUID id = kit.afterKyc("K03");
        kit.slots.book(id, ETA, "MORNING", EventActor.CUSTOMER);
        kit.slots.book(id, ETA.plusDays(2), "AFTERNOON", EventActor.CUSTOMER);

        assertThat(kit.status(id)).isEqualTo(OrderStatus.INSTALL_SCHEDULED);
        assertThat(kit.orders.tracking(id).slotDate()).isEqualTo(ETA.plusDays(2));
        assertThat(kit.orders.events(id)).filteredOn(e -> OrderStatus.INSTALL_SCHEDULED.equals(e.status()))
                .extracting(OrderStore.Event::actor).containsExactly(EventActor.CUSTOMER, EventActor.CUSTOMER);
    }

    @Test
    void theBookedDateMovesThePromisedActivationDate() {
        UUID id = kit.afterKyc("K03");
        kit.slots.book(id, ETA.plusDays(3), "MORNING", EventActor.CUSTOMER);
        assertThat(kit.kyc.view(id).estimatedActivationDate()).isEqualTo(ETA.plusDays(3));
        assertThat(kit.tracker.view(id, false).expectedActivationDate()).isEqualTo(ETA.plusDays(3));

        kit.slots.book(id, ETA, "MORNING", EventActor.CUSTOMER);
        assertThat(kit.tracker.view(id, false).expectedActivationDate()).isEqualTo(ETA);
    }

    @Test
    void thePickerListsTheWindowWithSundaysDisabled() {
        InstallSlotService.AvailableSlots slots = kit.slots.available(kit.afterKyc("K03"));

        assertThat(slots.bookable()).isTrue();
        assertThat(slots.etaDate()).isEqualTo(ETA);
        assertThat(slots.latestDate()).isEqualTo(ETA.plusDays(14));
        assertThat(slots.dates()).hasSize(15);
        assertThat(slots.dates()).filteredOn(d -> !d.available()).extracting(InstallSlotService.SlotDate::date)
                .containsExactly(A_SUNDAY, A_SUNDAY.plusDays(7));
        assertThat(slots.slots()).containsExactly("MORNING", "AFTERNOON", "EVENING");
    }

    @Test
    void nothingIsOfferedBeforeVerification() {
        InstallSlotService.AvailableSlots slots = kit.slots.available(kit.afterKyc("K11"));
        assertThat(slots.bookable()).isFalse();
        assertThat(slots.dates()).isEmpty();
    }

    @Test
    void theEarliestSlotSkipsASunday() {
        UUID id = kit.afterKyc("K03");
        assertThat(kit.slots.bookEarliest(id)).isEqualTo(ETA);
        assertThat(kit.orders.tracking(id).slot()).isEqualTo("MORNING");
        assertThat(kit.orders.events(id).get(kit.orders.events(id).size() - 1).actor()).isEqualTo(EventActor.DEMO);
    }
}
