package com.udyam.orders;

/**
 * Order statuses. Transitions between them live in one place: OrderStatusMachine.
 * QUOTE_ACCEPTED is set on creation; KYC moves the order through the KYC_* values; the tracker takes it from there.
 */
public final class OrderStatus {

    public static final String QUOTE_ACCEPTED = "QUOTE_ACCEPTED";
    /** No active KYC consent (set after a withdrawal; QUOTE_ACCEPTED also means "consent not given yet"). */
    public static final String KYC_PENDING_CONSENT = "KYC_PENDING_CONSENT";
    public static final String KYC_IN_PROGRESS = "KYC_IN_PROGRESS";
    public static final String KYC_ACTION_REQUIRED = "KYC_ACTION_REQUIRED";
    public static final String KYC_REJECTED = "KYC_REJECTED";
    public static final String KYC_VERIFIED = "KYC_VERIFIED";
    public static final String INSTALL_SCHEDULED = "INSTALL_SCHEDULED";
    public static final String INSTALLED = "INSTALLED";
    public static final String ACTIVE = "ACTIVE";
    public static final String ON_HOLD = "ON_HOLD";
    public static final String CANCELLED = "CANCELLED";

    private OrderStatus() {
    }
}
