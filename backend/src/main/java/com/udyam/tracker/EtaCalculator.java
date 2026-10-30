package com.udyam.tracker;

import com.fasterxml.jackson.databind.JsonNode;
import com.udyam.kyc.checks.Text;
import com.udyam.orders.OrderStatus;
import com.udyam.orders.OrderStore;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Honest dates, from the quote stored on the order: each item is ready kycVerifiedDate + its install days
 * (catalogue typical_install_days, snapshotted in the quote); the bundle is ready when its slowest item is.
 */
public final class EtaCalculator {

    private EtaCalculator() {
    }

    /** etaDate is null until the documents are verified: the date doesn't exist yet, only etaDays does. */
    public record ItemEta(String productName, int quantity, int etaDays, LocalDate etaDate) {
    }

    /** Fastest first. verifiedDate null = not verified yet. */
    public static List<ItemEta> items(JsonNode quote, LocalDate verifiedDate) {
        List<ItemEta> items = new ArrayList<>();
        if (quote != null && quote.has("lines")) {
            for (JsonNode line : quote.get("lines")) {
                int days = line.path("installDays").asInt();
                items.add(new ItemEta(line.path("productName").asText(), line.path("quantity").asInt(), days,
                        verifiedDate == null ? null : verifiedDate.plusDays(days)));
            }
        }
        items.sort(Comparator.comparingInt(ItemEta::etaDays));
        return items;
    }

    /** The slowest item's install days (0 for an order with no lines). */
    public static int maxDays(List<ItemEta> items) {
        return items.stream().mapToInt(ItemEta::etaDays).max().orElse(0);
    }

    /** The date the whole bundle should be ready. */
    public static LocalDate overall(LocalDate verifiedDate, List<ItemEta> items) {
        return verifiedDate.plusDays(maxDays(items));
    }

    /** The business date the documents were last verified, or null. */
    public static LocalDate verifiedDate(List<OrderStore.Event> events) {
        LocalDate date = null;
        for (OrderStore.Event e : events) {
            if (OrderStatus.KYC_VERIFIED.equals(e.status())) {
                date = e.businessDate();
            }
        }
        return date;
    }

    /** "Business Postpaid 299 in 1 day, Biz Fibre 100 in 5 days": one part per distinct wait. */
    public static Text message(List<ItemEta> items) {
        Map<Integer, List<String>> byDays = new LinkedHashMap<>();
        items.forEach(i -> byDays.computeIfAbsent(i.etaDays(), d -> new ArrayList<>()).add(i.productName()));
        List<String> en = new ArrayList<>();
        List<String> hi = new ArrayList<>();
        byDays.forEach((days, names) -> {
            int others = names.size() - 1;
            en.add(names.get(0) + (others > 0 ? " +" + others + " more" : "") + " in " + days + (days == 1 ? " day" : " days"));
            hi.add(names.get(0) + (others > 0 ? " और " + others + " अन्य" : "") + ": " + days + " दिन में");
        });
        return Text.of(String.join(", ", en), String.join(", ", hi));
    }
}
