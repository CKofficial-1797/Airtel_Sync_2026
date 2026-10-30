package com.udyam.orders;

import com.udyam.rules.quote.Quote;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 409 QUOTE_CHANGED carries the recomputed quote; runs before the generic GlobalExceptionHandler. */
@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class OrderExceptionHandler {

    public record QuoteChangedError(int status, String error, String code, String message, String path, Quote quote) {
    }

    @ExceptionHandler(QuoteChangedException.class)
    public ResponseEntity<QuoteChangedError> handle(QuoteChangedException ex, HttpServletRequest req) {
        HttpStatus status = HttpStatus.CONFLICT;
        return ResponseEntity.status(status).body(new QuoteChangedError(status.value(), status.getReasonPhrase(),
                "QUOTE_CHANGED", ex.getMessage(), req.getRequestURI(), ex.getQuote()));
    }
}
