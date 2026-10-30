package com.udyam.orders;

import com.udyam.rules.quote.Quote;

/** The server-side recompute removed items the client tried to order: the UI must show the new quote. */
public class QuoteChangedException extends RuntimeException {

    private final transient Quote quote;

    public QuoteChangedException(Quote quote) {
        super("Some items are not available for this business; please review the updated quote");
        this.quote = quote;
    }

    public Quote getQuote() {
        return quote;
    }
}
