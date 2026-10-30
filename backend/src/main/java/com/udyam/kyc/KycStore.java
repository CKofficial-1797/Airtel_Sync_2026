package com.udyam.kyc;

import com.udyam.kyc.checks.CheckResult;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Persistence for KYC consent, documents and check runs. App: JdbcKycStore (Neon); tests: in-memory. */
public interface KycStore {

    /** Replaces any active consent of the order with these purpose rows. */
    void saveConsent(UUID orderId, String noticeVersion, String language, Map<String, Boolean> purposes);

    /** Active (not withdrawn) consent rows of the order. */
    List<Consent> activeConsent(UUID orderId);

    /** Marks the active consent withdrawn; returns the number of rows affected. */
    int withdrawConsent(UUID orderId);

    /** Insert or replace the document of this slot (UNIQUE order_id + doc_type). */
    void saveDocument(UUID orderId, KycDocument document);

    List<KycDocument> documents(UUID orderId);

    void saveChecks(UUID orderId, UUID runId, List<CheckResult> checks);

    /** Checks of the latest run, in code order (empty before the first submit). */
    List<CheckResult> latestChecks(UUID orderId);

    /** DPDP withdrawal: delete extracted fields and check evidence of the order (statuses and hashes stay). */
    void purgeExtractedData(UUID orderId);

    void setActivationDate(UUID orderId, LocalDate date);

    Optional<LocalDate> activationDate(UUID orderId);

    record Consent(String purpose, boolean granted, String noticeVersion, String language, OffsetDateTime createdAt) {
    }
}
