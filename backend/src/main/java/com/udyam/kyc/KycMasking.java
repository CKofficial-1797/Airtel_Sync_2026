package com.udyam.kyc;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.udyam.common.privacy.Masking;

/** Masks identifiers in extracted fields before they leave the API. Full values stay in the DB only. */
public final class KycMasking {

    private KycMasking() {
    }

    public static JsonNode fields(JsonNode fields) {
        if (!(fields instanceof ObjectNode obj)) {
            return fields;
        }
        ObjectNode copy = obj.deepCopy();
        mask(copy, "gstin", Masking::gstin);
        mask(copy, "udyamNumber", Masking::udyam);
        mask(copy, "idNumber", Masking::keepLast3);
        mask(copy, "consumerNumber", Masking::keepLast3);
        return copy;
    }

    private static void mask(ObjectNode node, String field, java.util.function.UnaryOperator<String> masker) {
        if (node.hasNonNull(field)) {
            node.put(field, masker.apply(node.get(field).asText()));
        }
    }
}
