package com.udyam.tracker;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class EtaCalculatorTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate VERIFIED = LocalDate.of(2026, 7, 15);

    private static JsonNode quote(Object[]... lines) {
        ObjectNode quote = JSON.createObjectNode();
        ArrayNode array = quote.putArray("lines");
        for (Object[] l : lines) {
            array.addObject().put("productName", (String) l[0]).put("quantity", (int) l[1]).put("installDays", (int) l[2]);
        }
        return quote;
    }

    private static final JsonNode BUNDLE = quote(
            new Object[] {"Internet Leased Line 20", 1, 21},
            new Object[] {"Business Postpaid 299", 2, 1},
            new Object[] {"Biz Fibre 300", 1, 5});

    @Test
    void eachItemIsReadyAfterItsOwnInstallDaysFastestFirst() {
        List<EtaCalculator.ItemEta> items = EtaCalculator.items(BUNDLE, VERIFIED);

        assertThat(items).extracting(EtaCalculator.ItemEta::productName)
                .containsExactly("Business Postpaid 299", "Biz Fibre 300", "Internet Leased Line 20");
        assertThat(items).extracting(EtaCalculator.ItemEta::etaDate)
                .containsExactly(LocalDate.of(2026, 7, 16), LocalDate.of(2026, 7, 20), LocalDate.of(2026, 8, 5));
        assertThat(items.get(0).quantity()).isEqualTo(2);
    }

    @Test
    void theBundleIsReadyWhenItsSlowestItemIs() {
        List<EtaCalculator.ItemEta> items = EtaCalculator.items(BUNDLE, VERIFIED);
        assertThat(EtaCalculator.maxDays(items)).isEqualTo(21);
        assertThat(EtaCalculator.overall(VERIFIED, items)).isEqualTo(LocalDate.of(2026, 8, 5));
    }

    @Test
    void beforeVerificationThereAreDaysButNoDates() {
        List<EtaCalculator.ItemEta> items = EtaCalculator.items(BUNDLE, null);
        assertThat(items).extracting(EtaCalculator.ItemEta::etaDate).containsOnlyNulls();
        assertThat(items).extracting(EtaCalculator.ItemEta::etaDays).containsExactly(1, 5, 21);
    }

    @Test
    void anOrderWithoutLinesHasNoWait() {
        List<EtaCalculator.ItemEta> items = EtaCalculator.items(JSON.createObjectNode(), VERIFIED);
        assertThat(items).isEmpty();
        assertThat(EtaCalculator.overall(VERIFIED, items)).isEqualTo(VERIFIED);
    }

    @Test
    void theMessageSaysHowLongEachThingTakes() {
        var message = EtaCalculator.message(EtaCalculator.items(BUNDLE, VERIFIED));
        assertThat(message.en()).isEqualTo("Business Postpaid 299 in 1 day, Biz Fibre 300 in 5 days, Internet Leased Line 20 in 21 days");
        assertThat(message.hi()).contains("Business Postpaid 299: 1 दिन में");
    }

    @Test
    void theMessageGroupsItemsWithTheSameWait() {
        JsonNode q = quote(new Object[] {"Cloud Email", 5, 1}, new Object[] {"Static IP", 1, 1}, new Object[] {"Endpoint Security", 5, 1});
        var message = EtaCalculator.message(EtaCalculator.items(q, VERIFIED));
        assertThat(message.en()).isEqualTo("Cloud Email +2 more in 1 day");
        assertThat(message.hi()).isEqualTo("Cloud Email और 2 अन्य: 1 दिन में");
    }
}
