package com.udyam.rules.eligibility;

/** One failed rule: a stable code plus a plain-English message. */
public record Reason(ReasonCode code, String message) {
}
