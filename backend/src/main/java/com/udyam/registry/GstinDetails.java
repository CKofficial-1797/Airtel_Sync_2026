package com.udyam.registry;

import java.time.LocalDate;

/** Plain (non-JPA) view of one GST registry row, shared by the JPA and CSV lookups. */
public record GstinDetails(
        String gstin,
        String legalName,
        String tradeName,
        String constitution,
        String principalPlaceOfBusiness,
        String state,
        LocalDate registrationDate,
        String status) {

    public static final String ACTIVE = "Active";

    /** Only "Active" counts as a valid GSTIN for eligibility/KYC; Cancelled and Suspended do not. */
    public boolean active() {
        return ACTIVE.equals(status);
    }

    static GstinDetails from(GstinRecord r) {
        return new GstinDetails(r.getGstin(), r.getLegalName(), r.getTradeName(), r.getConstitution(),
                r.getPrincipalPlaceOfBusiness(), r.getState(), r.getRegistrationDate(), r.getStatus());
    }
}
