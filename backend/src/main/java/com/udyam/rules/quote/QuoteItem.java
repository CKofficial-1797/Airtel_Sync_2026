package com.udyam.rules.quote;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** One requested product and quantity (1..500). */
public record QuoteItem(@NotBlank String productId, @NotNull @Min(1) @Max(500) Integer quantity) {
}
