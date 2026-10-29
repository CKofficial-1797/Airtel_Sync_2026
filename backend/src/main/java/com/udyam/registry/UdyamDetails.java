package com.udyam.registry;

import java.time.LocalDate;

/** Plain view of one Udyam registry row (also the GET /api/mock/udyam/{n} response). */
public record UdyamDetails(
        String udyamNumber,
        String enterpriseName,
        String enterpriseType,
        String majorActivity,
        String nicDescription,
        String state,
        String city,
        LocalDate dateOfRegistration) {

    static UdyamDetails from(UdyamRecord r) {
        return new UdyamDetails(r.getUdyamNumber(), r.getEnterpriseName(), r.getEnterpriseType(),
                r.getMajorActivity(), r.getNicDescription(), r.getState(), r.getCity(), r.getDateOfRegistration());
    }
}
