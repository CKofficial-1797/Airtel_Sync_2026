package com.udyam.rules.quote;

import com.udyam.rules.profile.BusinessProfile;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/quote")
public class QuoteController {

    private final QuoteService quotes;

    public QuoteController(QuoteService quotes) {
        this.quotes = quotes;
    }

    /**
     * @param removedProductIds optional: products the user toggled off, so dependents are reported as
     *                          DEPENDENCY_REMOVED ("removed because X was removed")
     */
    public record QuoteRequest(@Valid @NotNull BusinessProfile profile, @NotEmpty List<@Valid QuoteItem> items,
                               List<String> removedProductIds) {
    }

    @PostMapping
    public Quote quote(@Valid @RequestBody QuoteRequest request) {
        return quotes.quote(request.profile(), request.items(), request.removedProductIds());
    }
}
