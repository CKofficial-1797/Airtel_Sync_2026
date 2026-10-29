package com.udyam.rules.quote;

import static com.udyam.rules.RulesFixture.QUOTES;
import static com.udyam.rules.RulesFixture.profile;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.udyam.common.errors.ApiException;
import com.udyam.rules.RulesFixture;
import com.udyam.rules.eligibility.Reason;
import com.udyam.rules.eligibility.ReasonCode;
import com.udyam.rules.profile.BusinessProfile;
import com.udyam.rules.quote.QuoteController.QuoteRequest;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.Test;

class QuoteServiceTest {

    private static QuoteItem item(String id, int qty) {
        return new QuoteItem(id, qty);
    }

    @Test
    void kiranaBundle() {
        // P01 999 x1 = 999; P07 299 x2 = 598 -> 1,597/month. Setup 0. Contract max(12, 1) = 12.
        // Install max(5, 1) = 5 days -> 2026-07-15 + 5 = 2026-07-20.
        Quote q = QUOTES.quote(profile("Kirana store", 2, 3, 1, "NONE"), List.of(item("P01", 1), item("P07", 2)), null);
        assertThat(q.monthlyTotalInr()).isEqualTo(1597);
        assertThat(q.oneTimeTotalInr()).isZero();
        assertThat(q.contractMonths()).isEqualTo(12);
        assertThat(q.maxInstallDays()).isEqualTo(5);
        assertThat(q.estimatedActivationDate()).isEqualTo(LocalDate.of(2026, 7, 20));
        assertThat(q.notes()).anyMatch(n -> n.startsWith("Prices as per Accel Business catalogue"));
    }

    @Test
    void dentalClinicBundle() {
        // P02 1499x1 = 1499; P16 125x10 = 1250; P08 599x3 = 1797; P20 99x7 = 693; P17 499x1 = 499
        // = 1499 + 1250 + 1797 + 693 + 499 = 5,738/month. All setup fees 0. Contract 12. Install max 5 -> 2026-07-20.
        Quote q = QUOTES.quote(profile("Dental clinic", 1, 14, 1, "ACTIVE"),
                List.of(item("P02", 1), item("P16", 10), item("P08", 3), item("P20", 7), item("P17", 1)), null);
        assertThat(q.monthlyTotalInr()).isEqualTo(5738);
        assertThat(q.oneTimeTotalInr()).isZero();
        assertThat(q.contractMonths()).isEqualTo(12);
        assertThat(q.estimatedActivationDate()).isEqualTo(LocalDate.of(2026, 7, 20));
    }

    @Test
    void restaurantChainBundle() {
        // Monthly: P02 1499x8 = 11,992; P09 499x8 = 3,992; P19 599x8 = 4,792; P23 199x16 = 3,184;
        //          P10 2500x8 = 20,000; P08 599x5 = 2,995 -> total 46,955.
        // Setup:   P09 1500x8 = 12,000; P19 2500x8 = 20,000; P23 1800x16 = 28,800; P10 5000x8 = 40,000 -> 100,800.
        // Contract = max(12, 12, 12, 12, 24, 1) = 24 (P10). Install = max(5, 3, 3, 4, 14, 1) = 14 -> 2026-07-29.
        Quote q = QUOTES.quote(profile("Restaurant chain", 1, 60, 8, "ACTIVE"), List.of(item("P02", 8), item("P09", 8),
                item("P19", 8), item("P23", 16), item("P10", 8), item("P08", 5)), null);
        assertThat(q.monthlyTotalInr()).isEqualTo(46955);
        assertThat(q.oneTimeTotalInr()).isEqualTo(100800);
        assertThat(q.contractMonths()).isEqualTo(24);
        assertThat(q.maxInstallDays()).isEqualTo(14);
        assertThat(q.estimatedActivationDate()).isEqualTo(LocalDate.of(2026, 7, 29));
        assertThat(q.lines()).filteredOn(l -> l.productId().equals("P23")).singleElement()
                .satisfies(l -> {
                    assertThat(l.unitPriceInr()).isEqualTo(199);
                    assertThat(l.monthlyTotalInr()).isEqualTo(3184);
                    assertThat(l.setupFeeInr()).isEqualTo(28800);
                });
    }

    @Test
    void midPricingRoundsHalfUp() {
        // MID: P02 (1499+1799)/2 = 1649; P16 (125+150)/2 = 137.5 -> 138; x2 = 276. Total 1649 + 276 = 1925.
        QuoteService mid = RulesFixture.quoteService(new PriceCalculator(new PricingProperties(PricingMode.MID)));
        Quote q = mid.quote(profile("CA firm", 1, 5, 1, "NONE"), List.of(item("P02", 1), item("P16", 2)), null);
        assertThat(q.pricingMode()).isEqualTo(PricingMode.MID);
        assertThat(q.monthlyTotalInr()).isEqualTo(1925);
    }

    @Test
    void staticIpWithOnlyAirFiberIsRemoved() {
        Quote q = QUOTES.quote(profile("Salon", 2, 4, 1, "NONE"), List.of(item("P04", 1), item("P11", 1)), null);
        assertThat(q.lines()).extracting(Quote.Line::productId).containsExactly("P04");
        assertThat(q.removed()).singleElement().satisfies(r -> {
            assertThat(r.productId()).isEqualTo("P11");
            assertThat(r.reasons()).extracting(Reason::code).containsExactly(ReasonCode.DEPENDENCY_MISSING);
        });
        assertThat(q.monthlyTotalInr()).isEqualTo(899);
    }

    @Test
    void removingFibreCascades() {
        BusinessProfile p = profile("CA firm", 1, 5, 1, "NONE");
        Quote before = QUOTES.quote(p, List.of(item("P02", 1), item("P11", 1), item("P09", 1)), null);
        assertThat(before.removed()).isEmpty();

        Quote after = QUOTES.quote(p, List.of(item("P11", 1), item("P09", 1)), List.of("P02"));
        assertThat(after.lines()).isEmpty();
        assertThat(after.monthlyTotalInr()).isZero();
        assertThat(after.removed()).extracting(Quote.RemovedItem::productId).containsExactly("P11", "P09");
        assertThat(after.removed()).allSatisfy(r -> assertThat(r.reasons()).extracting(Reason::code)
                .containsExactly(ReasonCode.DEPENDENCY_REMOVED));
        assertThat(after.removed().get(0).reasons().get(0).message())
                .isEqualTo("Static IP removed because Biz Fibre 300 was removed");
    }

    @Test
    void addOnsAndIneligibleCoverTheRestOfTheCatalogue() {
        Quote q = QUOTES.quote(profile("Kirana store", 2, 3, 1, "NONE"), List.of(item("P01", 1), item("P07", 2)), null);
        assertThat(q.lines().size() + q.availableAddOns().size() + q.ineligibleProducts().size()).isEqualTo(25);
        assertThat(q.availableAddOns()).extracting(Quote.AddOn::productId).contains("P11", "P22").doesNotContain("P15");
        assertThat(q.ineligibleProducts()).extracting(r -> r.productId()).contains("P03", "P15");
    }

    @Test
    void spendComparisonIsFactual() {
        BusinessProfile p = new BusinessProfile("Dental clinic", null, null, 2, 20, 1, null, null, null, 3050, null,
                null, null, null, null, null, null);
        Quote q = QUOTES.quote(p, List.of(item("P02", 1), item("P12", 2)), null);
        // P12 needs GSTIN -> removed. P02 1,499 vs 3,050 -> difference -1,551.
        assertThat(q.comparison()).isEqualTo(new Quote.SpendComparison(3050, 1499, -1551));
    }

    @Test
    void duplicateAndUnknownProductsAreRejected() {
        BusinessProfile p = profile("Kirana store", 2, 3, 1, "NONE");
        assertThatThrownBy(() -> QUOTES.quote(p, List.of(item("P01", 1), item("P01", 2)), null))
                .isInstanceOf(ApiException.class).hasMessageContaining("twice");
        assertThatThrownBy(() -> QUOTES.quote(p, List.of(item("P99", 1)), null))
                .isInstanceOf(ApiException.class).hasMessageContaining("Unknown product");
    }

    @Test
    void quantityOutOfRangeFailsValidation() {
        Validator validator = Validation.buildDefaultValidatorFactory().getValidator();
        BusinessProfile p = profile("Kirana store", 2, 3, 1, "NONE");
        assertThat(validator.validate(new QuoteRequest(p, List.of(item("P01", 0)), null)))
                .anyMatch(v -> v.getPropertyPath().toString().equals("items[0].quantity"));
        assertThat(validator.validate(new QuoteRequest(p, List.of(item("P01", 501)), null))).isNotEmpty();
        assertThat(validator.validate(new QuoteRequest(p, List.of(item("P01", 500)), null))).isEmpty();
    }
}
