package com.udyam.rules.quote;

import com.udyam.rules.data.CatalogueProduct;
import org.springframework.stereotype.Component;

/** Unit price from the catalogue range. Integer rupees only; no discounts, no taxes. */
@Component
public class PriceCalculator {

    private final PricingMode mode;

    public PriceCalculator(PricingProperties props) {
        this.mode = props.mode() == null ? PricingMode.MIN : props.mode();
    }

    public PricingMode mode() {
        return mode;
    }

    public int unitPrice(CatalogueProduct p) {
        return switch (mode) {
            case MIN -> p.priceMinInr();
            case MID -> (p.priceMinInr() + p.priceMaxInr() + 1) / 2;
        };
    }
}
