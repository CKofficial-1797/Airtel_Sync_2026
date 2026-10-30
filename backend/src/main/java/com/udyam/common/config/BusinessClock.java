package com.udyam.common.config;

import java.time.LocalDate;
import org.springframework.stereotype.Component;

/**
 * "Today" for business dates (ETAs, slot limits, document age): app.reference-date in the prototype, so the demo
 * data stays valid. Event timestamps (created_at) stay the real time. A real deployment would return the actual date.
 */
@Component
public class BusinessClock {

    private final LocalDate today;

    public BusinessClock(AppProperties app) {
        this.today = app.referenceDate();
    }

    public LocalDate today() {
        return today;
    }
}
