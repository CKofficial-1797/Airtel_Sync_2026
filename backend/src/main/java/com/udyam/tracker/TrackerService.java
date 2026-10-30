package com.udyam.tracker;

import com.udyam.common.errors.NotFoundException;
import com.udyam.kyc.DocType;
import com.udyam.kyc.KycDocument;
import com.udyam.kyc.KycStore;
import com.udyam.kyc.checks.CheckResult;
import com.udyam.kyc.checks.Text;
import com.udyam.orders.OrderStatus;
import com.udyam.orders.OrderStore;
import com.udyam.registry.GstinDetails;
import com.udyam.registry.GstinVerificationService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Builds the customer's tracker from the order, its events and the KYC result. */
@Service
public class TrackerService {

    private static final Set<String> VERIFIED_OR_LATER = Set.of(OrderStatus.KYC_VERIFIED, OrderStatus.INSTALL_SCHEDULED,
            OrderStatus.INSTALLED, OrderStatus.ACTIVE);
    private static final Text SUPPORT = Text.of("Please contact Accel Business support with your original documents.",
            "कृपया अपने मूल दस्तावेज़ों के साथ Accel Business सहायता से संपर्क करें।");
    private static final Text FIX = Text.of("Open your documents and follow the fix shown there.",
            "अपने दस्तावेज़ खोलें और वहाँ बताया गया सुधार करें।");

    private final OrderStore orders;
    private final KycStore kyc;
    private final GstinVerificationService gstins;

    public TrackerService(OrderStore orders, KycStore kyc, GstinVerificationService gstins) {
        this.orders = orders;
        this.kyc = kyc;
        this.gstins = gstins;
    }

    /** countView: an owner opening or refreshing the page. Auto-refresh and demo actions don't count. */
    @Transactional
    public TrackerView view(UUID orderId, boolean countView) {
        OrderStore.Order order = orders.find(orderId).orElseThrow(() -> new NotFoundException("Order not found"));
        if (countView) {
            orders.incrementTrackerViews(orderId);
        }
        OrderStore.Tracking tracking = orders.tracking(orderId);
        List<OrderStore.Event> events = orders.events(orderId);
        String status = order.status();
        boolean onHold = OrderStatus.ON_HOLD.equals(status);
        String paused = onHold ? tracking.holdPreviousStatus() : null;
        String effective = onHold ? paused : status;

        LocalDate verifiedDate = VERIFIED_OR_LATER.contains(effective) ? EtaCalculator.verifiedDate(events) : null;
        List<EtaCalculator.ItemEta> items = EtaCalculator.items(order.quote(), verifiedDate);
        LocalDate expected = onHold ? null : expectedActivation(status, verifiedDate, items, tracking, events);
        Text etaMessage = EtaCalculator.message(items);

        List<TrackerView.EventLine> lines = new ArrayList<>(events.stream().map(e -> {
            Text text = TrackerTexts.event(e);
            return new TrackerView.EventLine(e.status(), text.en(), text.hi(), e.createdAt(), e.businessDate());
        }).toList());
        Collections.reverse(lines);
        OffsetDateTime lastUpdated = events.isEmpty() ? order.createdAt() : events.get(events.size() - 1).createdAt();

        return new TrackerView(orderId.toString(), order.orderRef(), businessName(order, orderId), status,
                StepMapper.customerStep(status, paused),
                StepMapper.steps(status, paused, stepDates(effective, onHold, verifiedDate, expected, tracking, events)),
                items, EtaCalculator.maxDays(items), expected, etaMessage.en(), etaMessage.hi(),
                tracking.slotDate() == null ? null : new TrackerView.InstallSlot(tracking.slotDate(), tracking.slot()),
                TrackerTexts.nextAction(status, orderId.toString()),
                onHold ? onHold(tracking) : null, blocked(status, orderId), lines, lastUpdated, order.monthlyTotalInr());
    }

    /** An item can't be live before the visit: a later slot moves the promise. Active = when it happened. */
    private static LocalDate expectedActivation(String status, LocalDate verifiedDate, List<EtaCalculator.ItemEta> items,
                                                OrderStore.Tracking tracking, List<OrderStore.Event> events) {
        if (verifiedDate == null) {
            return null;
        }
        if (OrderStatus.ACTIVE.equals(status)) {
            return latestDate(events, OrderStatus.ACTIVE);
        }
        LocalDate eta = EtaCalculator.overall(verifiedDate, items);
        return tracking.slotDate() != null && tracking.slotDate().isAfter(eta) ? tracking.slotDate() : eta;
    }

    private static Map<String, LocalDate> stepDates(String effective, boolean onHold, LocalDate verifiedDate,
                                                    LocalDate expected, OrderStore.Tracking tracking,
                                                    List<OrderStore.Event> events) {
        Map<String, LocalDate> dates = new HashMap<>();
        if (!events.isEmpty()) {
            dates.put(StepMapper.ORDER_PLACED, events.get(0).businessDate());
        }
        dates.put(StepMapper.DOCUMENTS, verifiedDate);
        if (tracking.slotDate() != null) {
            dates.put(StepMapper.SCHEDULED, latestDate(events, OrderStatus.INSTALL_SCHEDULED));
            // A planned visit that is on hold isn't a promise any more; one that happened keeps its date.
            if (!onHold || OrderStatus.INSTALLED.equals(effective)) {
                dates.put(StepMapper.INSTALL, tracking.slotDate());
            }
        }
        dates.put(StepMapper.LIVE, expected);
        return dates;
    }

    private static LocalDate latestDate(List<OrderStore.Event> events, String status) {
        LocalDate date = null;
        for (OrderStore.Event e : events) {
            if (status.equals(e.status())) {
                date = e.businessDate();
            }
        }
        return date;
    }

    private static TrackerView.OnHold onHold(OrderStore.Tracking tracking) {
        Optional<HoldReason> reason = HoldReason.parse(tracking.holdReason());
        Text message = reason.map(HoldReason::message).orElse(Text.of("Your order is on hold.", "आपका ऑर्डर अभी रुका हुआ है।"));
        Text next = reason.map(HoldReason::next).orElse(Text.of("We'll update this page as soon as it moves again.",
                "ऑर्डर आगे बढ़ते ही हम यह पेज अपडेट करेंगे।"));
        return new TrackerView.OnHold(tracking.holdReason(), message.en(), message.hi(), next.en(), next.hi());
    }

    private TrackerView.Blocked blocked(String status, UUID orderId) {
        if (OrderStatus.CANCELLED.equals(status)) {
            return new TrackerView.Blocked("This order was cancelled.", "यह ऑर्डर रद्द कर दिया गया है।", SUPPORT.en(), SUPPORT.hi());
        }
        boolean rejected = OrderStatus.KYC_REJECTED.equals(status);
        if (!rejected && !OrderStatus.KYC_ACTION_REQUIRED.equals(status)) {
            return null;
        }
        // The first failure of the kind that decided the verdict; its text is already masked by the checks.
        Optional<CheckResult> first = kyc.latestChecks(orderId).stream()
                .filter(c -> c.blockingFailure() && c.fixable() != rejected).findFirst();
        String messageEn = first.map(CheckResult::messageEn).orElse("We couldn't verify your documents.");
        String messageHi = first.map(CheckResult::messageHi).orElse("हम आपके दस्तावेज़ सत्यापित नहीं कर सके।");
        Text fallback = rejected ? SUPPORT : FIX;
        String helpEn = first.map(CheckResult::fixHintEn).orElse(fallback.en());
        String helpHi = first.map(CheckResult::fixHintHi).orElse(fallback.hi());
        return new TrackerView.Blocked(messageEn, messageHi, helpEn, helpHi);
    }

    /** From the documents when read; otherwise the registry name for the GSTIN in the profile; otherwise none. */
    private String businessName(OrderStore.Order order, UUID orderId) {
        Map<DocType, KycDocument> docs = new HashMap<>();
        kyc.documents(orderId).forEach(d -> docs.put(d.docType(), d));
        String[][] sources = {{"GST_CERTIFICATE", "tradeName"}, {"UDYAM_CERTIFICATE", "enterpriseName"},
                {"SIGNATORY_ID", "forBusiness"}, {"ADDRESS_PROOF", "billedTo"}};
        for (String[] source : sources) {
            KycDocument doc = docs.get(DocType.valueOf(source[0]));
            String name = doc == null ? null : doc.text(source[1]);
            if (name != null) {
                return name;
            }
        }
        if (order.profile() != null && order.profile().hasNonNull("gstin")) {
            return gstins.find(order.profile().get("gstin").asText()).map(GstinDetails::tradeName).orElse(null);
        }
        return null;
    }
}
