package com.udyam.tracker;

import com.udyam.common.config.BusinessClock;
import com.udyam.kyc.DocType;
import com.udyam.kyc.InMemoryKycStore;
import com.udyam.kyc.KycFixtures;
import com.udyam.kyc.KycProperties;
import com.udyam.kyc.KycService;
import com.udyam.kyc.consent.ConsentNotices;
import com.udyam.kyc.extract.FixtureCache;
import com.udyam.orders.InMemoryOrderStore;
import com.udyam.orders.OrderService;
import com.udyam.orders.OrderStatusMachine;
import com.udyam.rules.RulesFixture;
import com.udyam.rules.quote.QuoteItem;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** The order, KYC and tracker services wired by hand on in-memory stores; specimens are read through the fixture cache. */
final class TrackerKit {

    final InMemoryOrderStore orders = new InMemoryOrderStore();
    final InMemoryKycStore kycStore = new InMemoryKycStore();
    final BusinessClock clock = new BusinessClock(RulesFixture.APP);
    final OrderStatusMachine machine = new OrderStatusMachine(orders, clock);
    final OrderService orderService = new OrderService(orders, RulesFixture.QUOTES, KycFixtures.JSON, RulesFixture.APP);
    final KycService kyc = kycService();
    final InstallSlotService slots = new InstallSlotService(orders, kycStore, machine);
    final TrackerService tracker = new TrackerService(orders, kycStore, RulesFixture.GSTINS);

    private KycService kycService() {
        try {
            KycProperties props = KycFixtures.props(Files.createTempDirectory("tracker-kit"), true);
            return new KycService(orders, machine, kycStore, KycFixtures.VALIDATOR, (image, type) -> {
                throw new IllegalStateException("Only specimens are used here");
            }, new FixtureCache(props, KycFixtures.JSON), new ConsentNotices(props), props, RulesFixture.PROVIDER,
                    RulesFixture.APP);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** Kirana order: P01 (5 days) + P07 x2 (1 day), so the ETA is 2026-07-20, a Monday. */
    UUID newOrder() {
        return orderService.create(null, RulesFixture.profile("Kirana store", 2, 3, 1, "NONE"),
                List.of(new QuoteItem("P01", 1), new QuoteItem("P07", 2))).orderId();
    }

    void consent(UUID id) {
        kyc.giveConsent(id, new KycService.ConsentRequest("2026-07-v1", "en",
                Map.of(ConsentNotices.KYC_VERIFICATION, true, ConsentNotices.SERVICE_INSTALLATION, true)));
    }

    void uploadAll(UUID id, String applicant) {
        for (DocType t : DocType.values()) {
            if (Files.exists(KycFixtures.SPECIMENS.resolve(applicant + "_" + t.fileSuffix() + ".png"))) {
                kyc.upload(id, t.name(), KycFixtures.specimen(applicant, t));
            }
        }
    }

    /** A new order taken through the real KYC with this applicant's documents. */
    UUID afterKyc(String applicant) {
        UUID id = newOrder();
        consent(id);
        uploadAll(id, applicant);
        kyc.submit(id);
        return id;
    }

    String status(UUID id) {
        return orders.find(id).orElseThrow().status();
    }
}
