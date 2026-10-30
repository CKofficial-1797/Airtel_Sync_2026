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
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.udyam.common.config.BusinessClock;
import com.udyam.common.errors.ApiException;
import com.udyam.rules.RulesFixture;
import java.time.LocalDate;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

class OrderStatusMachineTest {

    /** The approved table, spelled out here on purpose: a change to the machine must also change this. */
    private static final Map<String, Set<String>> TABLE = Map.ofEntries(
            Map.entry(QUOTE_ACCEPTED, Set.of(KYC_IN_PROGRESS, KYC_PENDING_CONSENT, CANCELLED)),
            Map.entry(KYC_PENDING_CONSENT, Set.of(KYC_IN_PROGRESS, CANCELLED)),
            Map.entry(KYC_IN_PROGRESS, Set.of(KYC_VERIFIED, KYC_ACTION_REQUIRED, KYC_REJECTED, KYC_PENDING_CONSENT, CANCELLED)),
            Map.entry(KYC_ACTION_REQUIRED, Set.of(KYC_IN_PROGRESS, KYC_PENDING_CONSENT, CANCELLED)),
            Map.entry(KYC_REJECTED, Set.of(KYC_PENDING_CONSENT)),
            Map.entry(KYC_VERIFIED, Set.of(INSTALL_SCHEDULED, ON_HOLD, KYC_PENDING_CONSENT, CANCELLED)),
            Map.entry(INSTALL_SCHEDULED, Set.of(INSTALL_SCHEDULED, INSTALLED, ON_HOLD, KYC_PENDING_CONSENT, CANCELLED)),
            Map.entry(INSTALLED, Set.of(ACTIVE, ON_HOLD)),
            Map.entry(ON_HOLD, Set.of(KYC_VERIFIED, INSTALL_SCHEDULED, INSTALLED, KYC_PENDING_CONSENT, CANCELLED)),
            Map.entry(ACTIVE, Set.of()),
            Map.entry(CANCELLED, Set.of()));

    private InMemoryOrderStore store;
    private OrderStatusMachine machine;
    private final LocalDate today = RulesFixture.REFERENCE_DATE;

    @BeforeEach
    void setUp() {
        store = new InMemoryOrderStore();
        machine = new OrderStatusMachine(store, new BusinessClock(RulesFixture.APP));
    }

    private UUID orderIn(String status) {
        UUID id = UUID.randomUUID();
        ObjectMapper json = new ObjectMapper();
        store.insert(new OrderStore.Order(id, "UD-" + id, null, json.nullNode(), json.createObjectNode(), 0, 0, 0, status, null));
        return id;
    }

    static Stream<Arguments> everyPair() {
        return TABLE.keySet().stream().flatMap(from -> TABLE.keySet().stream().map(to -> Arguments.of(from, to)));
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("everyPair")
    void everyPairFollowsTheTable(String from, String to) {
        boolean expected = TABLE.get(from).contains(to);
        assertThat(OrderStatusMachine.isAllowed(from, to)).isEqualTo(expected);

        // ON_HOLD is entered through hold(), and leaving a hold depends on what it paused: tested separately.
        if (ON_HOLD.equals(to) || ON_HOLD.equals(from)) {
            return;
        }
        UUID id = orderIn(from);
        if (expected) {
            machine.transition(id, to, EventActor.SYSTEM, "note");
            assertThat(store.find(id).orElseThrow().status()).isEqualTo(to);
            assertThat(store.events(id)).singleElement().satisfies(e -> {
                assertThat(e.status()).isEqualTo(to);
                assertThat(e.actor()).isEqualTo(EventActor.SYSTEM);
                assertThat(e.businessDate()).isEqualTo(today);
                assertThat(e.createdAt()).isNotNull();
            });
        } else {
            assertInvalid(() -> machine.transition(id, to, EventActor.SYSTEM, "note"));
            assertThat(store.find(id).orElseThrow().status()).isEqualTo(from);
            assertThat(store.events(id)).isEmpty();
        }
    }

    @Test
    void theTableCoversEveryStatus() {
        assertThat(OrderStatusMachine.allStatuses()).containsExactlyInAnyOrderElementsOf(TABLE.keySet());
    }

    private static void assertInvalid(Runnable call) {
        assertThatThrownBy(call::run).isInstanceOfSatisfying(ApiException.class, e -> {
            assertThat(e.getStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(e.getCode()).isEqualTo("INVALID_TRANSITION");
        });
    }

    @Test
    void specificInvalidMovesAre409() {
        assertInvalid(() -> machine.transition(orderIn(ACTIVE), KYC_VERIFIED, EventActor.DEMO, null));
        assertInvalid(() -> machine.transition(orderIn(QUOTE_ACCEPTED), INSTALLED, EventActor.DEMO, null));
        assertInvalid(() -> machine.transition(orderIn(KYC_REJECTED), KYC_VERIFIED, EventActor.SYSTEM, null));
        assertInvalid(() -> machine.transition(orderIn(CANCELLED), KYC_IN_PROGRESS, EventActor.CUSTOMER, null));
        assertInvalid(() -> machine.transition(orderIn(KYC_VERIFIED), ACTIVE, EventActor.DEMO, null));
    }

    @Test
    void anEventCanCountForAnotherBusinessDateButKeepsItsRealTime() {
        UUID id = orderIn(INSTALL_SCHEDULED);
        LocalDate slot = today.plusDays(5);
        machine.transition(id, INSTALLED, EventActor.DEMO, "installed", slot);
        OrderStore.Event e = store.events(id).get(0);
        assertThat(e.businessDate()).isEqualTo(slot);
        assertThat(e.actor()).isEqualTo(EventActor.DEMO);
        assertThat(e.createdAt().toLocalDate()).isNotEqualTo(slot);
    }

    @Test
    void holdSavesThePreviousStatusAndResumeReturnsToIt() {
        for (String paused : new String[] {KYC_VERIFIED, INSTALL_SCHEDULED, INSTALLED}) {
            UUID id = orderIn(paused);
            machine.hold(id, "SITE_NOT_FEASIBLE", "no fibre", EventActor.DEMO);
            assertThat(store.find(id).orElseThrow().status()).isEqualTo(ON_HOLD);
            assertThat(store.tracking(id).holdPreviousStatus()).isEqualTo(paused);
            assertThat(store.tracking(id).holdReason()).isEqualTo("SITE_NOT_FEASIBLE");

            assertThat(machine.resume(id, EventActor.DEMO)).isEqualTo(paused);
            assertThat(store.find(id).orElseThrow().status()).isEqualTo(paused);
            assertThat(store.tracking(id).holdReason()).isNull();
            assertThat(store.tracking(id).holdPreviousStatus()).isNull();
            assertThat(store.events(id)).extracting(OrderStore.Event::status).containsExactly(ON_HOLD, paused);
        }
    }

    @Test
    void aHoldCannotJumpToAnotherStep() {
        UUID id = orderIn(KYC_VERIFIED);
        machine.hold(id, "PAYMENT_PENDING", null, EventActor.DEMO);
        assertInvalid(() -> machine.transition(id, INSTALLED, EventActor.DEMO, null));
        assertInvalid(() -> machine.transition(id, INSTALL_SCHEDULED, EventActor.DEMO, null));
    }

    @Test
    void holdAndResumeAreRefusedWhereTheyDontApply() {
        assertInvalid(() -> machine.hold(orderIn(KYC_IN_PROGRESS), "PAYMENT_PENDING", null, EventActor.DEMO));
        assertInvalid(() -> machine.hold(orderIn(ACTIVE), "PAYMENT_PENDING", null, EventActor.DEMO));
        assertInvalid(() -> machine.resume(orderIn(KYC_VERIFIED), EventActor.DEMO));
        assertThatThrownBy(() -> machine.transition(orderIn(KYC_VERIFIED), ON_HOLD, EventActor.DEMO, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void anInstalledOrderOnHoldCantBeCancelledOrWithdrawn() {
        UUID id = orderIn(INSTALLED);
        assertThat(machine.withdrawalBlocked(id)).isTrue();
        machine.hold(id, "PAYMENT_PENDING", null, EventActor.DEMO);
        assertThat(machine.withdrawalBlocked(id)).isTrue();
        assertInvalid(() -> machine.transition(id, CANCELLED, EventActor.DEMO, null));
        assertInvalid(() -> machine.transition(id, KYC_PENDING_CONSENT, EventActor.CUSTOMER, null));
    }

    @Test
    void anOrderHeldBeforeInstallationCanBeCancelledAndTheHoldIsCleared() {
        UUID id = orderIn(KYC_VERIFIED);
        machine.hold(id, "PAYMENT_PENDING", null, EventActor.DEMO);
        assertThat(machine.withdrawalBlocked(id)).isFalse();
        machine.transition(id, CANCELLED, EventActor.DEMO, null);
        assertThat(store.tracking(id).holdReason()).isNull();
    }

    @Test
    void withdrawingOrCancellingReleasesTheBookedSlot() {
        for (String to : new String[] {KYC_PENDING_CONSENT, CANCELLED}) {
            UUID id = orderIn(INSTALL_SCHEDULED);
            store.setInstallSlot(id, today.plusDays(5), "MORNING");
            machine.transition(id, to, EventActor.CUSTOMER, null);
            assertThat(store.tracking(id).slotDate()).isNull();
        }
    }
}
