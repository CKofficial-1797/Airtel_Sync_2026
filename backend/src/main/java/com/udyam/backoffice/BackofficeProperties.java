package com.udyam.backoffice;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * app.backoffice.*
 *
 * @param key                the prototype passcode (set it in application-local.yml or env); unset = no access at all
 * @param sessionIdleMinutes an advisor session counts as unclosed after this long without activity (0 for the demo)
 * @param orderStuckHours    an order counts as stuck after this long without a status change (0 for the demo)
 */
@ConfigurationProperties(prefix = "app.backoffice")
public record BackofficeProperties(
        String key,
        @DefaultValue("30") int sessionIdleMinutes,
        @DefaultValue("24") int orderStuckHours) {

    public boolean hasKey() {
        return key != null && !key.isBlank();
    }
}
