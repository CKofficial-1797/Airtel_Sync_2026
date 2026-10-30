package com.udyam.orders;

import static com.udyam.rules.RulesFixture.profile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.udyam.common.errors.NotFoundException;
import com.udyam.rules.RulesFixture;
import com.udyam.rules.eligibility.Reason;
import com.udyam.rules.eligibility.ReasonCode;
import com.udyam.rules.profile.BusinessProfile;
import com.udyam.rules.quote.QuoteItem;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OrderServiceTest {

    private final ObjectMapper json = new ObjectMapper().registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false); // as Spring Boot configures it
    private InMemoryOrderStore store;
    private OrderService service;

    private static final BusinessProfile KIRANA = profile("Kirana store", 2, 3, 1, "NONE");
    private static final List<QuoteItem> KIRANA_ITEMS = List.of(new QuoteItem("P01", 1), new QuoteItem("P07", 2));

    @BeforeEach
    void setUp() {
        store = new InMemoryOrderStore();
        service = new OrderService(store, RulesFixture.QUOTES, json, RulesFixture.APP);
    }

    @Test
    void serverRecomputesTheQuoteAndIgnoresClientTotals() throws Exception {
        // A tampered request: the client claims ₹1/month. The record has no totals, so they are dropped.
        String body = """
                {"profile": {"businessType": "Kirana store", "cityTier": 2, "employees": 3, "sites": 1},
                 "items": [{"productId": "P01", "quantity": 1}, {"productId": "P07", "quantity": 2}],
                 "monthlyTotalInr": 1, "oneTimeTotalInr": 0, "quote": {"monthlyTotalInr": 1}}
                """;
        OrderController.CreateOrderRequest req = json.readValue(body, OrderController.CreateOrderRequest.class);

        OrderService.OrderCreated created = service.create(null, req.profile(), req.items());

        // P01 999 + P07 299 x 2 = 1,597 (catalogue MIN prices), not the ₹1 the client sent.
        assertThat(created.quote().monthlyTotalInr()).isEqualTo(1597);
        OrderStore.Order saved = store.orders.get(created.orderId());
        assertThat(saved.monthlyTotalInr()).isEqualTo(1597);
        assertThat(saved.oneTimeTotalInr()).isZero();
        assertThat(saved.contractMonths()).isEqualTo(12);
        assertThat(saved.quote().get("monthlyTotalInr").asInt()).isEqualTo(1597);
        assertThat(saved.status()).isEqualTo(OrderStatus.QUOTE_ACCEPTED);
    }

    @Test
    void ineligibleItemsGiveQuoteChangedWithTheFreshQuote() {
        // Static IP needs fibre; AirFiber (P04) does not satisfy it -> removed by the engine.
        List<QuoteItem> items = List.of(new QuoteItem("P04", 1), new QuoteItem("P11", 1));

        assertThatThrownBy(() -> service.create(null, profile("Salon", 2, 4, 1, "NONE"), items))
                .isInstanceOfSatisfying(QuoteChangedException.class, e -> {
                    assertThat(e.getQuote().removed()).singleElement().satisfies(r -> {
                        assertThat(r.productId()).isEqualTo("P11");
                        assertThat(r.reasons()).extracting(Reason::code).containsExactly(ReasonCode.DEPENDENCY_MISSING);
                    });
                    assertThat(e.getQuote().monthlyTotalInr()).isEqualTo(899);
                });
        assertThat(store.orders).isEmpty();
    }

    @Test
    void gstOnlyProductWithoutGstinIsRejected() {
        List<QuoteItem> items = List.of(new QuoteItem("P01", 1), new QuoteItem("P15", 1));
        assertThatThrownBy(() -> service.create(null, KIRANA, items)).isInstanceOf(QuoteChangedException.class);
    }

    @Test
    void orderRefsAreUniqueAndFormatted() {
        String first = service.create(null, KIRANA, KIRANA_ITEMS).orderRef();
        String second = service.create(null, KIRANA, KIRANA_ITEMS).orderRef();

        assertThat(first).isEqualTo("UD-260715-0042").matches("UD-\\d{6}-\\d{4,}");
        assertThat(second).isEqualTo("UD-260715-0043");
        assertThat(service.orderRef(12345)).isEqualTo("UD-260715-12345");
    }

    @Test
    void eventRowIsCreatedAndSessionIsMarkedCheckedOut() {
        UUID sessionId = UUID.randomUUID();
        store.sessions.add(sessionId);

        OrderService.OrderCreated created = service.create(sessionId, KIRANA, KIRANA_ITEMS);

        assertThat(store.events.get(created.orderId())).singleElement()
                .satisfies(e -> assertThat(e.status()).isEqualTo(OrderStatus.QUOTE_ACCEPTED));
        assertThat(store.checkedOut).containsEntry(sessionId, created.orderId());
        assertThat(store.orders.get(created.orderId()).sessionId()).isEqualTo(sessionId);
    }

    @Test
    void unknownSessionIsRejectedBeforeSaving() {
        assertThatThrownBy(() -> service.create(UUID.randomUUID(), KIRANA, KIRANA_ITEMS))
                .isInstanceOf(NotFoundException.class);
        assertThat(store.orders).isEmpty();
    }

    @Test
    void getMasksTheGstinAndListsEvents() {
        BusinessProfile withGstin = profile("Dental clinic", 1, 14, 1, "ACTIVE");
        OrderService.OrderCreated created = service.create(null, withGstin,
                List.of(new QuoteItem("P02", 1), new QuoteItem("P16", 10)));

        OrderService.OrderView view = service.get(created.orderId());

        assertThat(view.profile().get("gstin").asText()).isEqualTo("32TTN*****1ZW");
        assertThat(view.orderRef()).isEqualTo(created.orderRef());
        assertThat(view.events()).extracting(OrderService.EventView::status).containsExactly(OrderStatus.QUOTE_ACCEPTED);
        assertThat(view.quote().get("monthlyTotalInr").asInt()).isEqualTo(1499 + 1250);
        assertThatThrownBy(() -> service.get(UUID.randomUUID())).isInstanceOf(NotFoundException.class);
    }
}
