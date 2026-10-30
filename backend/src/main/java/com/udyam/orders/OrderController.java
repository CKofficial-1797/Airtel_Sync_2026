package com.udyam.orders;

import com.udyam.rules.profile.BusinessProfile;
import com.udyam.rules.quote.QuoteItem;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orders;

    public OrderController(OrderService orders) {
        this.orders = orders;
    }

    /** No totals in the request on purpose: the server recomputes the quote (unknown JSON fields are ignored). */
    public record CreateOrderRequest(UUID sessionId, @Valid @NotNull BusinessProfile profile,
                                     @NotEmpty List<@Valid QuoteItem> items) {
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderService.OrderCreated create(@Valid @RequestBody CreateOrderRequest request) {
        return orders.create(request.sessionId(), request.profile(), request.items());
    }

    @GetMapping("/{orderId}")
    public OrderService.OrderView get(@PathVariable UUID orderId) {
        return orders.get(orderId);
    }
}
