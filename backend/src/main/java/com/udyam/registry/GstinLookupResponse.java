package com.udyam.registry;

import java.time.LocalDate;

/** Response of GET /api/mock/gstin/{gstin}. active is true only when status is "Active". */
public record GstinLookupResponse(
        String gstin,
        String legalName,
        String tradeName,
        String constitution,
        String principalPlaceOfBusiness,
        String state,
        String stateCode,
        LocalDate registrationDate,
        String status,
        boolean active) {

    static GstinLookupResponse from(GstinDetails d) {
        return new GstinLookupResponse(d.gstin(), d.legalName(), d.tradeName(), d.constitution(),
                d.principalPlaceOfBusiness(), d.state(), d.gstin().substring(0, 2), d.registrationDate(),
                d.status(), d.active());
    }
}
