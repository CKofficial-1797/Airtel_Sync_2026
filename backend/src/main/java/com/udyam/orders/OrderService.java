package com.udyam.orders;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.udyam.common.config.AppProperties;
import com.udyam.common.errors.NotFoundException;
import com.udyam.common.privacy.Masking;
import com.udyam.rules.profile.BusinessProfile;
import com.udyam.rules.quote.Quote;
import com.udyam.rules.quote.QuoteItem;
import com.udyam.rules.quote.QuoteService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Creates an order from an accepted quote. The quote is ALWAYS recomputed on the server with QuoteService;
 * nothing the client says about prices or totals is used. If the recompute drops any item, nothing is saved
 * and the caller gets 409 QUOTE_CHANGED with the fresh quote.
 */
@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);
    private static final DateTimeFormatter REF_DATE = DateTimeFormatter.ofPattern("yyMMdd");

    private final OrderStore store;
    private final QuoteService quotes;
    private final ObjectMapper json;
    private final LocalDate referenceDate;

    public OrderService(OrderStore store, QuoteService quotes, ObjectMapper json, AppProperties app) {
        this.store = store;
        this.quotes = quotes;
        this.json = json;
        this.referenceDate = app.referenceDate();
    }

    @Transactional
    public OrderCreated create(UUID sessionId, BusinessProfile profile, List<QuoteItem> items) {
        if (sessionId != null && !store.sessionExists(sessionId)) {
            throw new NotFoundException("Advisor session not found: " + sessionId);
        }
        Quote quote = quotes.quote(profile, items, List.of());
        if (!quote.removed().isEmpty()) {
            throw new QuoteChangedException(quote);
        }

        UUID id = UUID.randomUUID();
        String ref = orderRef(store.nextRefNumber());
        store.insert(new OrderStore.Order(id, ref, sessionId, json.valueToTree(profile), json.valueToTree(quote),
                quote.monthlyTotalInr(), quote.oneTimeTotalInr(), quote.contractMonths(), OrderStatus.QUOTE_ACCEPTED,
                null));
        store.addEvent(id, OrderStatus.QUOTE_ACCEPTED, "Quote accepted by the customer");
        if (sessionId != null) {
            store.markSessionCheckedOut(sessionId, id);
        }
        log.info("Order {} created ({} lines, monthly {})", ref, quote.lines().size(), quote.monthlyTotalInr());
        return new OrderCreated(id, ref, OrderStatus.QUOTE_ACCEPTED, quote);
    }

    public OrderView get(UUID orderId) {
        OrderStore.Order o = store.find(orderId)
                .orElseThrow(() -> new NotFoundException("Order not found: " + orderId));
        List<EventView> events = store.events(orderId).stream()
                .map(e -> new EventView(e.status(), e.note(), e.createdAt()))
                .toList();
        return new OrderView(o.id(), o.orderRef(), o.status(), o.createdAt(), o.quote(), maskedProfile(o.profile()),
                events);
    }

    /** "UD-" + reference date (yyMMdd) + "-" + sequence padded to 4 digits, e.g. UD-260715-0042. */
    String orderRef(long number) {
        return "UD-" + referenceDate.format(REF_DATE) + "-" + String.format("%04d", number);
    }

    private JsonNode maskedProfile(JsonNode profile) {
        if (profile instanceof ObjectNode obj && obj.hasNonNull("gstin")) {
            ObjectNode copy = obj.deepCopy();
            copy.put("gstin", Masking.gstin(obj.get("gstin").asText()));
            return copy;
        }
        return profile;
    }

    public record OrderCreated(UUID orderId, String orderRef, String status, Quote quote) {
    }

    public record EventView(String status, String note, OffsetDateTime createdAt) {
    }

    public record OrderView(UUID orderId, String orderRef, String status, OffsetDateTime createdAt, JsonNode quote,
                            JsonNode profile, List<EventView> events) {
    }
}
