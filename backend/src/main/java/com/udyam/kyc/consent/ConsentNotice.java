package com.udyam.kyc.consent;

import java.util.List;

/** The DPDP consent notice shown before any KYC document is collected (GET /api/kyc/consent-notice). */
public record ConsentNotice(
        String version,
        String language,
        String title,
        String dataFiduciary,
        List<Purpose> purposes,
        String dataCollected,
        String retention,
        int retentionDays,
        List<String> rights,
        String contact) {

    public record Purpose(String code, boolean required, String title, String description) {
    }
}
