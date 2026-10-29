package com.udyam.rules.profile;

/** Registry details the advisor can use to pre-fill the business details after a GSTIN is found. */
public record GstinAutofill(String legalName, String tradeName, String state, String principalPlaceOfBusiness) {
}
