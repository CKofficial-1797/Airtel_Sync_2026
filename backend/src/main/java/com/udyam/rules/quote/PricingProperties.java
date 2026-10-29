package com.udyam.rules.quote;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/** app.pricing.* in application.yml. */
@ConfigurationProperties(prefix = "app.pricing")
public record PricingProperties(@DefaultValue("MIN") PricingMode mode) {
}
