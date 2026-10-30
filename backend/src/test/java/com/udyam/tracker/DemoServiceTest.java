package com.udyam.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udyam.common.errors.ApiException;
import com.udyam.common.errors.NotFoundException;
import com.udyam.orders.EventActor;
import com.udyam.orders.OrderStatus;
import com.udyam.orders.OrderStore;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

class DemoServiceTest {

    private static final LocalDate ETA = LocalDate.of(2026, 7, 20);
    private static final DemoService.AdvanceRequest NEXT = new DemoService.AdvanceRequest(null, null, null);

    private final TrackerKit kit = new TrackerKit();
    private final DemoService demo = demo(true);

    private DemoService demo(boolean enabled) {
        return new DemoService(enabled, kit.orders, kit.machine, kit.slots, kit.tracker);
    }

    private void assertInvalid(UUID id, DemoService.AdvanceRequest request) {
        assertThatThrownBy(() -> demo.advance(id, request)).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getCode()).isEqualTo("INVALID_TRANSITION");
        });
    }

    @Test
    void itIs404WhenDemoModeIsOff() {
        UUID id = kit.afterKyc("K03");
        DemoService off = demo(false);
        assertThat(off.enabled()).isFalse();
        assertThatThrownBy(() -> off.advance(id, NEXT)).isInstanceOf(NotFoundException.class);
        assertThat(kit.status(id)).isEqualTo(OrderStatus.KYC_VERIFIED);
    }

    @Test
    void advanceWalksFromVerifiedToActiveWithActorDemo() {
        UUID id = kit.afterKyc("K03");

        TrackerView scheduled = demo.advance(id, null);
        assertThat(scheduled.currentStatus()).isEqualTo(OrderStatus.INSTALL_SCHEDULED);
        assertThat(scheduled.installSlot()).isEqualTo(new TrackerView.InstallSlot(ETA, "MORNING"));

        assertThat(demo.advance(id, NEXT).currentStatus()).isEqualTo(OrderStatus.INSTALLED);
        TrackerView active = demo.advance(id, NEXT);
        assertThat(active.currentStatus()).isEqualTo(OrderStatus.ACTIVE);
        assertThat(active.customerStep()).isEqualTo(5);

        List<OrderStore.Event> events = kit.orders.events(id);
        assertThat(events.subList(events.size() - 3, events.size())).extracting(OrderStore.Event::actor).containsOnly(EventActor.DEMO);
    }

    @Test
    void installedAndActiveCountOnTheSlotDate() {
        UUID id = kit.afterKyc("K03");
        kit.slots.book(id, ETA.plusDays(3), "EVENING", EventActor.CUSTOMER);
        demo.advance(id, NEXT);
        TrackerView active = demo.advance(id, NEXT);
        assertThat(active.steps().get(3).date()).isEqualTo(ETA.plusDays(3));
        assertThat(active.expectedActivationDate()).isEqualTo(ETA.plusDays(3));
        assertThat(kit.orders.events(id).get(kit.orders.events(id).size() - 1).businessDate()).isEqualTo(ETA.plusDays(3));
    }

    @Test
    void itCannotFakeAKycResult() {
        UUID open = kit.newOrder();
        kit.consent(open);
        assertInvalid(open, NEXT);
        assertInvalid(open, new DemoService.AdvanceRequest(OrderStatus.KYC_VERIFIED, null, null));
        assertThat(kit.status(open)).isEqualTo(OrderStatus.KYC_IN_PROGRESS);

        UUID rejected = kit.afterKyc("K06");
        assertInvalid(rejected, NEXT);
        assertInvalid(rejected, new DemoService.AdvanceRequest(OrderStatus.KYC_VERIFIED, null, null));
        assertThat(kit.status(rejected)).isEqualTo(OrderStatus.KYC_REJECTED);
    }

    @Test
    void invalidMovesAre409() {
        UUID id = kit.afterKyc("K03");
        demo.advance(id, NEXT);
        demo.advance(id, NEXT);
        demo.advance(id, NEXT);
        assertThat(kit.status(id)).isEqualTo(OrderStatus.ACTIVE);

        assertInvalid(id, new DemoService.AdvanceRequest(OrderStatus.KYC_VERIFIED, null, null));
        assertInvalid(id, new DemoService.AdvanceRequest(OrderStatus.INSTALLED, null, null));
        assertInvalid(id, NEXT);
        assertInvalid(id, new DemoService.AdvanceRequest(null, "PAYMENT_PENDING", null));
    }

    @Test
    void holdAndResumeGoThroughTheMachine() {
        UUID id = kit.afterKyc("K03");
        demo.advance(id, NEXT);

        TrackerView held = demo.advance(id, new DemoService.AdvanceRequest(null, "SITE_NOT_FEASIBLE", "survey failed"));
        assertThat(held.currentStatus()).isEqualTo(OrderStatus.ON_HOLD);
        assertThat(held.onHold().reasonCode()).isEqualTo("SITE_NOT_FEASIBLE");
        assertThat(kit.orders.tracking(id).holdNote()).isEqualTo("survey failed");

        TrackerView resumed = demo.advance(id, NEXT);
        assertThat(resumed.currentStatus()).isEqualTo(OrderStatus.INSTALL_SCHEDULED);
        assertThat(resumed.onHold()).isNull();
        assertThat(resumed.installSlot()).isNotNull();
    }

    @Test
    void anExplicitResumeToThePausedStatusWorksAndAJumpDoesNot() {
        UUID id = kit.afterKyc("K03");
        demo.advance(id, new DemoService.AdvanceRequest(OrderStatus.ON_HOLD, "PAYMENT_PENDING", null));
        assertInvalid(id, new DemoService.AdvanceRequest(OrderStatus.INSTALLED, null, null));
        assertThat(demo.advance(id, new DemoService.AdvanceRequest(OrderStatus.KYC_VERIFIED, null, null)).currentStatus())
                .isEqualTo(OrderStatus.KYC_VERIFIED);
    }

    @Test
    void anUnknownHoldReasonIs400() {
        UUID id = kit.afterKyc("K03");
        assertThatThrownBy(() -> demo.advance(id, new DemoService.AdvanceRequest(null, "BAD", null)))
                .isInstanceOfSatisfying(ApiException.class, e -> assertThat(e.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST));
        assertThatThrownBy(() -> demo.advance(id, new DemoService.AdvanceRequest(OrderStatus.ON_HOLD, null, null)))
                .isInstanceOf(ApiException.class);
    }
}
