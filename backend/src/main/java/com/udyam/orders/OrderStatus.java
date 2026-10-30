package com.udyam.orders;

/**
 * Order statuses. QUOTE_ACCEPTED is set on creation; KYC moves the order through the KYC_* values
 * (each transition also writes an order_event row). Phase 8 adds tracker statuses.
 */
public final class OrderStatus {

    public static final String QUOTE_ACCEPTED = "QUOTE_ACCEPTED";
    /** No active KYC consent (set after a withdrawal; QUOTE_ACCEPTED also means "consent not given yet"). */
    public static final String KYC_PENDING_CONSENT = "KYC_PENDING_CONSENT";
    public static final String KYC_IN_PROGRESS = "KYC_IN_PROGRESS";
    public static final String KYC_ACTION_REQUIRED = "KYC_ACTION_REQUIRED";
    public static final String KYC_REJECTED = "KYC_REJECTED";
    public static final String KYC_VERIFIED = "KYC_VERIFIED";

    private OrderStatus() {
    }
}
