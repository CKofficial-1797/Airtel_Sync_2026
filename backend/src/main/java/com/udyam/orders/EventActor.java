package com.udyam.orders;

/** Who caused an order_event. */
public final class EventActor {

    public static final String SYSTEM = "SYSTEM";
    public static final String CUSTOMER = "CUSTOMER";
    public static final String DEMO = "DEMO";

    private EventActor() {
    }
}
