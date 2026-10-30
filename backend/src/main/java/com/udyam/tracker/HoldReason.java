package com.udyam.tracker;

import com.udyam.kyc.checks.Text;
import java.util.Optional;

/** Why an order is on hold, and what happens next, in words the owner can act on (or relax about). */
public enum HoldReason {
    SITE_NOT_FEASIBLE(
            Text.of("Fibre isn't available at your address yet.", "आपके पते पर अभी फ़ाइबर उपलब्ध नहीं है।"),
            Text.of("We're checking other options for your address and will update this page. You don't need to call us.",
                    "हम आपके पते के लिए दूसरे विकल्प देख रहे हैं और इसी पेज पर जानकारी देंगे। आपको कॉल करने की ज़रूरत नहीं है।")),
    CUSTOMER_UNAVAILABLE(
            Text.of("We couldn't reach you at your installation address.", "हम आपके इंस्टॉलेशन पते पर आपसे संपर्क नहीं कर सके।"),
            Text.of("We'll try again and update this page once a new visit is arranged.",
                    "हम फिर कोशिश करेंगे और नई विज़िट तय होते ही इसी पेज पर जानकारी देंगे।")),
    PAYMENT_PENDING(
            Text.of("A payment is pending on this order.", "इस ऑर्डर का एक भुगतान बाकी है।"),
            Text.of("Your order continues as soon as we receive it, and this page will update.",
                    "भुगतान मिलते ही आपका ऑर्डर आगे बढ़ेगा और यह पेज अपडेट हो जाएगा।"));

    private final Text message;
    private final Text next;

    HoldReason(Text message, Text next) {
        this.message = message;
        this.next = next;
    }

    public Text message() {
        return message;
    }

    public Text next() {
        return next;
    }

    public static Optional<HoldReason> parse(String code) {
        for (HoldReason r : values()) {
            if (r.name().equals(code)) {
                return Optional.of(r);
            }
        }
        return Optional.empty();
    }
}
