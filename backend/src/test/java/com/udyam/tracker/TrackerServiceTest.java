package com.udyam.tracker;

import static com.udyam.tracker.StepMapper.BLOCKED;
import static com.udyam.tracker.StepMapper.CURRENT;
import static com.udyam.tracker.StepMapper.DONE;
import static com.udyam.tracker.StepMapper.UPCOMING;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udyam.common.errors.NotFoundException;
import com.udyam.kyc.KycFixtures;
import com.udyam.orders.EventActor;
import com.udyam.orders.OrderStatus;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TrackerServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 7, 15);
    private static final LocalDate ETA = LocalDate.of(2026, 7, 20);

    private final TrackerKit kit = new TrackerKit();

    private static List<String> states(TrackerView v) {
        return v.steps().stream().map(StepMapper.Step::state).toList();
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource({
            "QUOTE_ACCEPTED,      2, DONE, CURRENT, UPCOMING, UPCOMING, UPCOMING",
            "KYC_PENDING_CONSENT, 2, DONE, CURRENT, UPCOMING, UPCOMING, UPCOMING",
            "KYC_IN_PROGRESS,     2, DONE, CURRENT, UPCOMING, UPCOMING, UPCOMING",
            "KYC_ACTION_REQUIRED, 2, DONE, BLOCKED, UPCOMING, UPCOMING, UPCOMING",
            "KYC_REJECTED,        2, DONE, BLOCKED, UPCOMING, UPCOMING, UPCOMING",
            "KYC_VERIFIED,        3, DONE, DONE,    CURRENT,  UPCOMING, UPCOMING",
            "INSTALL_SCHEDULED,   4, DONE, DONE,    DONE,     CURRENT,  UPCOMING",
            "INSTALLED,           5, DONE, DONE,    DONE,     DONE,     CURRENT",
            "ACTIVE,              5, DONE, DONE,    DONE,     DONE,     DONE",
            "CANCELLED,           2, DONE, UPCOMING, UPCOMING, UPCOMING, UPCOMING",
    })
    void statusMapsToCustomerSteps(String status, int step, String s1, String s2, String s3, String s4, String s5) {
        assertThat(StepMapper.steps(status, null, Map.of())).extracting(StepMapper.Step::state).containsExactly(s1, s2, s3, s4, s5);
        assertThat(StepMapper.customerStep(status, null)).isEqualTo(step);
    }

    @ParameterizedTest(name = "on hold from {0}")
    @CsvSource({
            "KYC_VERIFIED,      3, DONE, DONE, BLOCKED,  UPCOMING, UPCOMING",
            "INSTALL_SCHEDULED, 4, DONE, DONE, DONE,     BLOCKED,  UPCOMING",
            "INSTALLED,         5, DONE, DONE, DONE,     DONE,     BLOCKED",
    })
    void aHoldBlocksTheStepItPaused(String paused, int step, String s1, String s2, String s3, String s4, String s5) {
        assertThat(StepMapper.steps(OrderStatus.ON_HOLD, paused, Map.of())).extracting(StepMapper.Step::state)
                .containsExactly(s1, s2, s3, s4, s5);
        assertThat(StepMapper.customerStep(OrderStatus.ON_HOLD, paused)).isEqualTo(step);
    }

    @Test
    void everyStepIsBilingual() {
        assertThat(StepMapper.steps(OrderStatus.KYC_VERIFIED, null, Map.of())).allSatisfy(s -> {
            assertThat(s.labelEn()).isNotBlank();
            assertThat(s.labelHi()).isNotBlank().isNotEqualTo(s.labelEn());
        });
    }

    @Test
    void beforeConsentTheNextStepIsConsentAndThereAreNoDatesYet() {
        UUID id = kit.newOrder();
        TrackerView v = kit.tracker.view(id, false);

        assertThat(v.currentStatus()).isEqualTo(OrderStatus.QUOTE_ACCEPTED);
        assertThat(v.nextAction().type()).isEqualTo("GIVE_CONSENT");
        assertThat(v.nextAction().link()).isEqualTo("/kyc/" + id);
        assertThat(v.expectedActivationDate()).isNull();
        assertThat(v.etaDaysAfterVerification()).isEqualTo(5);
        assertThat(v.items()).extracting(EtaCalculator.ItemEta::etaDate).containsOnlyNulls();
        assertThat(v.etaMessageEn()).isEqualTo("Business Postpaid 299 in 1 day, Biz Fibre 100 in 5 days");
        assertThat(v.monthlyTotalInr()).isEqualTo(1597);
        assertThat(v.businessName()).isNull();
    }

    @Test
    void uploadingIsTheNextStepAfterConsent() {
        UUID id = kit.newOrder();
        kit.consent(id);
        assertThat(kit.tracker.view(id, false).nextAction().type()).isEqualTo("UPLOAD_DOCS");
    }

    @Test
    void verifiedShowsDatesThePickerAndTheBusinessName() {
        TrackerView v = kit.tracker.view(kit.afterKyc("K03"), false);

        assertThat(v.customerStep()).isEqualTo(3);
        assertThat(v.businessName()).isEqualTo("Ganesh Woodcraft");
        assertThat(v.nextAction().type()).isEqualTo("PICK_SLOT");
        assertThat(v.blocked()).isNull();
        assertThat(v.expectedActivationDate()).isEqualTo(ETA);
        assertThat(v.items()).extracting(EtaCalculator.ItemEta::etaDate).containsExactly(TODAY.plusDays(1), ETA);
        assertThat(v.steps().get(1).date()).isEqualTo(TODAY);
    }

    @Test
    void actionRequiredBlocksStep2WithAFixDocumentsAction() {
        UUID id = kit.afterKyc("K11");
        TrackerView v = kit.tracker.view(id, false);

        assertThat(states(v)).containsExactly(DONE, BLOCKED, UPCOMING, UPCOMING, UPCOMING);
        assertThat(v.nextAction().type()).isEqualTo("FIX_DOCS");
        assertThat(v.nextAction().link()).isEqualTo("/kyc/" + id);
        assertThat(v.blocked().messageEn()).contains("257 days old");
        assertThat(v.blocked().helpEn()).contains("last 90 days");
        assertThat(v.blocked().helpHi()).isNotBlank();
        assertThat(v.expectedActivationDate()).isNull();
    }

    @Test
    void rejectedBlocksStep2WithAnExplanationAndNoAction() {
        TrackerView v = kit.tracker.view(kit.afterKyc("K06"), false);

        assertThat(states(v)).containsExactly(DONE, BLOCKED, UPCOMING, UPCOMING, UPCOMING);
        assertThat(v.nextAction().type()).isEqualTo("NONE");
        assertThat(v.blocked().messageEn()).contains("08YKZ*****1Z0");
        assertThat(v.blocked().helpEn()).contains("support");
        assertThat(v.expectedActivationDate()).isNull();
    }

    @Test
    void aRejectionExplainsTheCheckThatDecidedItNotAFixableOne() {
        TrackerView v = kit.tracker.view(kit.afterKyc("K01"), false); // C06 not fixable, C08 fixable
        assertThat(v.blocked().messageEn()).contains("Cancelled");
    }

    @Test
    void onHoldPausesTheDateAndExplainsWhatHappensNext() {
        UUID id = kit.afterKyc("K03");
        kit.slots.book(id, ETA, "MORNING", EventActor.CUSTOMER);
        kit.machine.hold(id, HoldReason.SITE_NOT_FEASIBLE.name(), null, EventActor.DEMO);

        TrackerView v = kit.tracker.view(id, false);
        assertThat(states(v)).containsExactly(DONE, DONE, DONE, BLOCKED, UPCOMING);
        assertThat(v.onHold().reasonCode()).isEqualTo("SITE_NOT_FEASIBLE");
        assertThat(v.onHold().messageEn()).isEqualTo("Fibre isn't available at your address yet.");
        assertThat(v.onHold().messageHi()).isNotBlank();
        assertThat(v.onHold().nextEn()).contains("don't need to call");
        assertThat(v.expectedActivationDate()).isNull();
        assertThat(v.steps().get(3).date()).isNull();
        assertThat(v.steps().get(4).date()).isNull();
        assertThat(v.nextAction().type()).isEqualTo("NONE");
        assertThat(v.events().get(0).textEn()).isEqualTo("On hold: Fibre isn't available at your address yet.");

        kit.machine.resume(id, EventActor.DEMO);
        TrackerView resumed = kit.tracker.view(id, false);
        assertThat(states(resumed)).containsExactly(DONE, DONE, DONE, CURRENT, UPCOMING);
        assertThat(resumed.onHold()).isNull();
        assertThat(resumed.expectedActivationDate()).isEqualTo(ETA);
        assertThat(resumed.events().get(0).textEn()).isEqualTo("Your order is moving again");
    }

    @Test
    void anInstallationCountsOnItsSlotDateNotOnToday() {
        UUID id = kit.afterKyc("K03");
        LocalDate slot = ETA.plusDays(2);
        kit.slots.book(id, slot, "EVENING", EventActor.CUSTOMER);
        assertThat(kit.tracker.view(id, false).steps().get(3).date()).isEqualTo(slot); // planned

        kit.machine.transition(id, OrderStatus.INSTALLED, EventActor.DEMO, "installed", slot);
        kit.machine.transition(id, OrderStatus.ACTIVE, EventActor.DEMO, "active", slot);

        TrackerView v = kit.tracker.view(id, false);
        assertThat(states(v)).containsOnly(DONE);
        assertThat(v.customerStep()).isEqualTo(5);
        assertThat(v.steps().get(3).date()).isEqualTo(slot);
        assertThat(v.steps().get(4).date()).isEqualTo(slot);
        assertThat(v.expectedActivationDate()).isEqualTo(slot);
    }

    @Test
    void eventsAreLatestFirstInFriendlyWords() {
        UUID id = kit.afterKyc("K03");
        kit.slots.book(id, ETA, "MORNING", EventActor.CUSTOMER);

        TrackerView v = kit.tracker.view(id, false);
        assertThat(v.events()).extracting(TrackerView.EventLine::textEn).containsExactly(
                "Installation booked for 20 Jul 2026 (morning)", "Documents verified", "Ready for your documents", "Order placed");
        assertThat(v.events().get(0).textHi()).contains("सुबह");
        assertThat(v.lastUpdated()).isEqualTo(v.events().get(0).at());
    }

    @Test
    void cancelledReleasesTheSlotAndSaysSo() {
        UUID id = kit.afterKyc("K03");
        kit.slots.book(id, ETA, "MORNING", EventActor.CUSTOMER);
        kit.machine.transition(id, OrderStatus.CANCELLED, EventActor.DEMO, null);

        TrackerView v = kit.tracker.view(id, false);
        assertThat(v.installSlot()).isNull();
        assertThat(v.blocked().messageEn()).contains("cancelled");
        assertThat(v.expectedActivationDate()).isNull();
    }

    @Test
    void theTrackerHoldsNoIdentifiers() {
        for (String applicant : List.of("K03", "K11", "K06")) {
            String json = write(kit.tracker.view(kit.afterKyc(applicant), false));
            assertThat(json).as(applicant)
                    .doesNotContainPattern("\\d{2}[A-Z0-9]{5}\\d{4}[A-Z0-9]{4}")
                    .doesNotContainPattern("UDYAM-[A-Z]{2}-\\d{2}-\\d{7}")
                    .doesNotContain("SYN-ID");
        }
    }

    private static String write(Object value) {
        try {
            return KycFixtures.JSON.writeValueAsString(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void viewsAreCountedOnlyWhenTheOwnerChecks() {
        UUID id = kit.newOrder();
        assertThat(kit.orders.tracking(id).viewCount()).isZero();
        kit.tracker.view(id, true);
        kit.tracker.view(id, true);
        kit.tracker.view(id, false); // auto-refresh
        assertThat(kit.orders.tracking(id).viewCount()).isEqualTo(2);
    }

    @Test
    void anUnknownOrderIs404() {
        assertThatThrownBy(() -> kit.tracker.view(UUID.randomUUID(), true)).isInstanceOf(NotFoundException.class);
    }
}
