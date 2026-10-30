package com.udyam.tracker;

import com.udyam.common.errors.ApiException;
import com.udyam.common.errors.NotFoundException;
import com.udyam.kyc.DocType;
import com.udyam.kyc.KycDocument;
import com.udyam.kyc.KycStore;
import com.udyam.kyc.checks.AddressMatcher;
import com.udyam.orders.OrderStore;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/**
 * Finds an order from its reference + installation PIN code (from the KYC address). A wrong reference, wrong PIN and
 * an order with no address yet all give the same 404, so the answer never reveals which part was wrong.
 */
@Service
public class TrackLookupService {

    private final OrderStore orders;
    private final KycStore kyc;
    private final LookupRateLimiter limiter;

    public TrackLookupService(OrderStore orders, KycStore kyc, LookupRateLimiter limiter) {
        this.orders = orders;
        this.kyc = kyc;
        this.limiter = limiter;
    }

    public UUID lookup(String orderRef, String pincode, String clientKey) {
        if (!limiter.tryAcquire(clientKey)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "RATE_LIMITED", "Too many attempts. Please try again in a few minutes.");
        }
        String ref = orderRef == null ? "" : orderRef.trim().toUpperCase(Locale.ROOT);
        String pin = pincode == null ? "" : pincode.trim();
        return orders.findByRef(ref)
                .filter(order -> !pin.isEmpty() && pin.equals(installationPincode(order.id()).orElse(null)))
                .map(OrderStore.Order::id)
                .orElseThrow(() -> new NotFoundException("Order not found"));
    }

    /** The bill's supply address is where we install; the GST address is the fallback. */
    private Optional<String> installationPincode(UUID orderId) {
        List<KycDocument> docs = kyc.documents(orderId);
        return pincode(docs, DocType.ADDRESS_PROOF, "supplyAddress")
                .or(() -> pincode(docs, DocType.GST_CERTIFICATE, "principalAddress"));
    }

    private static Optional<String> pincode(List<KycDocument> docs, DocType type, String field) {
        return docs.stream().filter(d -> d.docType() == type).findFirst()
                .flatMap(d -> AddressMatcher.pincode(d.text(field)));
    }
}
