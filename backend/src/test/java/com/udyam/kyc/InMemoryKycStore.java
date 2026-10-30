package com.udyam.kyc;

import com.udyam.kyc.checks.CheckResult;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** KycStore for unit tests (no DB). Public: the tracker tests reuse it. */
public class InMemoryKycStore implements KycStore {

    final Map<UUID, List<Consent>> consent = new HashMap<>();
    final Map<UUID, Integer> withdrawals = new HashMap<>();
    final Map<UUID, Map<DocType, KycDocument>> documents = new HashMap<>();
    final Map<UUID, List<CheckResult>> checks = new HashMap<>();
    final Map<UUID, LocalDate> activation = new HashMap<>();

    @Override
    public void saveConsent(UUID orderId, String noticeVersion, String language, Map<String, Boolean> purposes) {
        withdrawConsent(orderId);
        List<Consent> rows = new ArrayList<>();
        purposes.forEach((p, g) -> rows.add(new Consent(p, g, noticeVersion, language, OffsetDateTime.now())));
        consent.put(orderId, rows);
    }

    @Override
    public List<Consent> activeConsent(UUID orderId) {
        return consent.getOrDefault(orderId, List.of());
    }

    @Override
    public int withdrawConsent(UUID orderId) {
        List<Consent> rows = consent.remove(orderId);
        int n = rows == null ? 0 : rows.size();
        withdrawals.merge(orderId, n, Integer::sum);
        return n;
    }

    @Override
    public void saveDocument(UUID orderId, KycDocument d) {
        documents.computeIfAbsent(orderId, k -> new LinkedHashMap<>()).put(d.docType(), new KycDocument(d.docType(),
                d.status(), d.detectedDocType(), d.sha256(), d.blurScore(), d.skewDeg(), d.deskewed(), d.fields(),
                d.model(), OffsetDateTime.now()));
    }

    @Override
    public List<KycDocument> documents(UUID orderId) {
        return List.copyOf(documents.getOrDefault(orderId, Map.of()).values());
    }

    @Override
    public void saveChecks(UUID orderId, UUID runId, List<CheckResult> results) {
        checks.put(orderId, List.copyOf(results));
    }

    @Override
    public List<CheckResult> latestChecks(UUID orderId) {
        return checks.getOrDefault(orderId, List.of());
    }

    @Override
    public void deleteDocumentsAndChecks(UUID orderId) {
        documents.remove(orderId);
        checks.remove(orderId);
    }

    @Override
    public void setActivationDate(UUID orderId, LocalDate date) {
        if (date == null) {
            activation.remove(orderId);
        } else {
            activation.put(orderId, date);
        }
    }

    @Override
    public Optional<LocalDate> activationDate(UUID orderId) {
        return Optional.ofNullable(activation.get(orderId));
    }
}
