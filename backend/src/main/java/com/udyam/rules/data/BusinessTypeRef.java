package com.udyam.rules.data;

/** A business type known from smb_leads with its segment name (GET /api/reference/business-types item). */
public record BusinessTypeRef(String businessType, String segment) {
}
