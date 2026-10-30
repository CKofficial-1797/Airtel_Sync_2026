package com.udyam.tracker;

import com.udyam.kyc.checks.Text;
import com.udyam.orders.OrderStatus;
import com.udyam.orders.OrderStore;
import com.udyam.orders.TrackerNotes;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/** Bilingual wording for the tracker: raw event notes hold codes, the owner sees sentences. */
final class TrackerTexts {

    private static final DateTimeFormatter EN = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final DateTimeFormatter HI = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.forLanguageTag("hi-IN"));
    private static final Map<String, Text> SLOTS = Map.of(
            "MORNING", Text.of("morning", "सुबह"),
            "AFTERNOON", Text.of("afternoon", "दोपहर"),
            "EVENING", Text.of("evening", "शाम"));

    private static final Map<String, Text> EVENTS = Map.ofEntries(
            Map.entry(OrderStatus.QUOTE_ACCEPTED, Text.of("Order placed", "ऑर्डर हो गया")),
            Map.entry(OrderStatus.KYC_PENDING_CONSENT, Text.of("Consent withdrawn. Your documents and details were deleted",
                    "सहमति वापस ली गई। आपके दस्तावेज़ और जानकारी हटा दी गई")),
            Map.entry(OrderStatus.KYC_IN_PROGRESS, Text.of("Ready for your documents", "आपके दस्तावेज़ों का इंतज़ार है")),
            Map.entry(OrderStatus.KYC_ACTION_REQUIRED, Text.of("Your documents need a fix", "आपके दस्तावेज़ों में सुधार चाहिए")),
            Map.entry(OrderStatus.KYC_REJECTED, Text.of("We couldn't verify your documents", "हम आपके दस्तावेज़ सत्यापित नहीं कर सके")),
            Map.entry(OrderStatus.KYC_VERIFIED, Text.of("Documents verified", "दस्तावेज़ सत्यापित हो गए")),
            Map.entry(OrderStatus.INSTALL_SCHEDULED, Text.of("Installation scheduled", "इंस्टॉलेशन तय हो गया")),
            Map.entry(OrderStatus.INSTALLED, Text.of("Installation completed", "इंस्टॉलेशन पूरा हो गया")),
            Map.entry(OrderStatus.ACTIVE, Text.of("Your services are active", "आपकी सेवाएँ चालू हो गईं")),
            Map.entry(OrderStatus.ON_HOLD, Text.of("Your order is on hold", "आपका ऑर्डर अभी रुका हुआ है")),
            Map.entry(OrderStatus.CANCELLED, Text.of("Order cancelled", "ऑर्डर रद्द हो गया")));

    private TrackerTexts() {
    }

    static Text event(OrderStore.Event e) {
        if (TrackerNotes.RESUMED.equals(e.note())) {
            return Text.of("Your order is moving again", "आपका ऑर्डर फिर आगे बढ़ रहा है");
        }
        if (OrderStatus.INSTALL_SCHEDULED.equals(e.status())) {
            Optional<TrackerNotes.SlotNote> slot = TrackerNotes.parseSlot(e.note());
            if (slot.isPresent()) {
                Text word = SLOTS.get(slot.get().slot());
                LocalDate date = slot.get().date();
                return Text.of("Installation booked for " + EN.format(date) + " (" + word.en() + ")",
                        "इंस्टॉलेशन " + HI.format(date) + " (" + word.hi() + ") के लिए तय हुआ");
            }
        }
        if (OrderStatus.ON_HOLD.equals(e.status()) && e.note() != null) {
            Optional<HoldReason> reason = HoldReason.parse(e.note().split(":", 2)[0]);
            if (reason.isPresent()) {
                Text m = reason.get().message();
                return Text.of("On hold: " + m.en(), "रुका हुआ: " + m.hi());
            }
        }
        return EVENTS.getOrDefault(e.status(), Text.of("Order updated", "ऑर्डर अपडेट हुआ"));
    }

    static TrackerView.NextAction nextAction(String status, String orderId) {
        String kyc = "/kyc/" + orderId;
        return switch (status) {
            case OrderStatus.QUOTE_ACCEPTED, OrderStatus.KYC_PENDING_CONSENT ->
                    new TrackerView.NextAction("GIVE_CONSENT", "Give consent to start KYC", "KYC शुरू करने के लिए सहमति दें", kyc);
            case OrderStatus.KYC_IN_PROGRESS ->
                    new TrackerView.NextAction("UPLOAD_DOCS", "Upload your documents", "अपने दस्तावेज़ अपलोड करें", kyc);
            case OrderStatus.KYC_ACTION_REQUIRED ->
                    new TrackerView.NextAction("FIX_DOCS", "Fix documents", "दस्तावेज़ ठीक करें", kyc);
            case OrderStatus.KYC_VERIFIED ->
                    new TrackerView.NextAction("PICK_SLOT", "Pick your installation slot", "इंस्टॉलेशन का समय चुनें", null);
            case OrderStatus.KYC_REJECTED, OrderStatus.CANCELLED ->
                    new TrackerView.NextAction("NONE", "See the details above.", "ऊपर दी गई जानकारी देखें।", null);
            default -> new TrackerView.NextAction("NONE", "Nothing to do. We'll update this page as we progress.",
                    "आपको कुछ नहीं करना है। काम आगे बढ़ते ही हम यह पेज अपडेट करेंगे।", null);
        };
    }
}
