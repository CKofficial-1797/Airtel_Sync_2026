package com.udyam.backoffice;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/**
 * The first read of the 5,000 provided leads takes several seconds from Neon. Do it in the background after startup,
 * not while someone waits at the passcode screen. If it fails, the first request simply does the work instead.
 */
@Component
public class BackofficeWarmup {

    private static final Logger log = LoggerFactory.getLogger(BackofficeWarmup.class);

    private final BackofficeService service;
    private final InsightsService insights;
    private final BackofficeProperties props;

    public BackofficeWarmup(BackofficeService service, InsightsService insights, BackofficeProperties props) {
        this.service = service;
        this.insights = insights;
        this.props = props;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warmUp() {
        if (!props.hasKey()) {
            return; // nobody can get in, so don't spend the database on it
        }
        Thread thread = new Thread(() -> {
            try {
                service.reengagement(0, 1);
                insights.insights();
                log.info("Back-office data ready");
            } catch (RuntimeException e) {
                log.warn("Back-office warm-up failed ({}); the first request will load the data", e.getClass().getSimpleName());
            }
        }, "backoffice-warmup");
        thread.setDaemon(true);
        thread.start();
    }
}
