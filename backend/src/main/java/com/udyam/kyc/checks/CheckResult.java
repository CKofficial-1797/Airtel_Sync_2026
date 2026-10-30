package com.udyam.kyc.checks;

import java.util.Map;

/**
 * Outcome of one check. status: PASS | WARN | FAIL | SKIPPED; severity: BLOCKING | SOFT (WARN is always SOFT).
 * evidence holds what was compared, with identifiers masked; fix hints are null on PASS / SKIPPED.
 */
public record CheckResult(
        String code,
        String name,
        String status,
        String severity,
        boolean fixable,
        String messageEn,
        String messageHi,
        String fixHintEn,
        String fixHintHi,
        Map<String, Object> evidence) {

    public static final String PASS = "PASS";
    public static final String WARN = "WARN";
    public static final String FAIL = "FAIL";
    public static final String SKIPPED = "SKIPPED";
    public static final String BLOCKING = "BLOCKING";
    public static final String SOFT = "SOFT";

    public static CheckResult pass(CheckCode c, Text message, Map<String, Object> evidence) {
        return new CheckResult(c.name(), c.checkName(), PASS, BLOCKING, c.fixable(), message.en(), message.hi(),
                null, null, evidence);
    }

    public static CheckResult fail(CheckCode c, Text message, Text hint, Map<String, Object> evidence) {
        return fail(c, c.fixable(), message, hint, evidence);
    }

    public static CheckResult fail(CheckCode c, boolean fixable, Text message, Text hint,
                                   Map<String, Object> evidence) {
        return new CheckResult(c.name(), c.checkName(), FAIL, BLOCKING, fixable, message.en(), message.hi(),
                hint.en(), hint.hi(), evidence);
    }

    public static CheckResult warn(CheckCode c, Text message, Text hint, Map<String, Object> evidence) {
        return new CheckResult(c.name(), c.checkName(), WARN, SOFT, true, message.en(), message.hi(),
                hint == null ? null : hint.en(), hint == null ? null : hint.hi(), evidence);
    }

    /** A check that could not run because a document it needs is missing or unreadable (reported by C01-C03). */
    public static CheckResult skipped(CheckCode c, Text reason) {
        return new CheckResult(c.name(), c.checkName(), SKIPPED, BLOCKING, c.fixable(), reason.en(), reason.hi(),
                null, null, Map.of());
    }

    public boolean blockingFailure() {
        return FAIL.equals(status);
    }

    public boolean warning() {
        return WARN.equals(status);
    }
}
