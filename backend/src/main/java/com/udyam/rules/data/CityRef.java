package com.udyam.rules.data;

/** A city known from smb_leads with its tier and state (GET /api/reference/cities item). */
public record CityRef(String city, int tier, String state) {
}
