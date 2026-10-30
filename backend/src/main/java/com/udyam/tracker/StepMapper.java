package com.udyam.tracker;

import static com.udyam.orders.OrderStatus.ACTIVE;
import static com.udyam.orders.OrderStatus.CANCELLED;
import static com.udyam.orders.OrderStatus.INSTALLED;
import static com.udyam.orders.OrderStatus.INSTALL_SCHEDULED;
import static com.udyam.orders.OrderStatus.KYC_ACTION_REQUIRED;
import static com.udyam.orders.OrderStatus.KYC_REJECTED;
import static com.udyam.orders.OrderStatus.KYC_VERIFIED;
import static com.udyam.orders.OrderStatus.ON_HOLD;

import com.udyam.kyc.checks.Text;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Internal status → the 5 steps the owner sees: Order placed → Documents verified → Installation scheduled →
 * Installed → Active. A hold blocks the step it paused; KYC_ACTION_REQUIRED and KYC_REJECTED block step 2.
 */
public final class StepMapper {

    public static final String ORDER_PLACED = "ORDER_PLACED";
    public static final String DOCUMENTS = "DOCUMENTS";
    public static final String SCHEDULED = "SCHEDULED";
    public static final String INSTALL = "INSTALLED";
    public static final String LIVE = "ACTIVE";

    public static final String DONE = "DONE";
    public static final String CURRENT = "CURRENT";
    public static final String UPCOMING = "UPCOMING";
    public static final String BLOCKED = "BLOCKED";

    private static final List<String> KEYS = List.of(ORDER_PLACED, DOCUMENTS, SCHEDULED, INSTALL, LIVE);
    private static final Map<String, Text> LABELS = Map.of(
            ORDER_PLACED, Text.of("Order placed", "ऑर्डर हो गया"),
            DOCUMENTS, Text.of("Documents verified", "दस्तावेज़ सत्यापित"),
            SCHEDULED, Text.of("Installation scheduled", "इंस्टॉलेशन तय"),
            INSTALL, Text.of("Installed", "इंस्टॉल हो गया"),
            LIVE, Text.of("Active", "सेवा चालू"));

    private StepMapper() {
    }

    public record Step(String key, String labelEn, String labelHi, String state, LocalDate date) {
    }

    /** @param pausedStatus the status a hold paused (only for ON_HOLD) */
    public static List<Step> steps(String status, String pausedStatus, Map<String, LocalDate> dates) {
        boolean onHold = ON_HOLD.equals(status);
        String effective = onHold ? pausedStatus : status;
        int current = currentIndex(effective);
        boolean blocked = onHold || KYC_ACTION_REQUIRED.equals(status) || KYC_REJECTED.equals(status);
        boolean cancelled = CANCELLED.equals(status);

        List<Step> steps = new ArrayList<>();
        for (int i = 0; i < KEYS.size(); i++) {
            String key = KEYS.get(i);
            String state;
            if (i < current) {
                state = DONE;
            } else if (i == current && !cancelled) {
                state = blocked ? BLOCKED : CURRENT;
            } else {
                state = UPCOMING;
            }
            Text label = LABELS.get(key);
            steps.add(new Step(key, label.en(), label.hi(), state, dates.get(key)));
        }
        return steps;
    }

    /** The step in progress, 1-5. */
    public static int customerStep(String status, String pausedStatus) {
        int current = currentIndex(ON_HOLD.equals(status) ? pausedStatus : status);
        return Math.min(current + 1, KEYS.size());
    }

    /** Index of the first step not finished yet; all 5 finished = 5. */
    private static int currentIndex(String status) {
        if (status == null) {
            return 1;
        }
        return switch (status) {
            case KYC_VERIFIED -> 2;
            case INSTALL_SCHEDULED -> 3;
            case INSTALLED -> 4;
            case ACTIVE -> 5;
            default -> 1; // everything before verification, including rejected and cancelled
        };
    }
}
