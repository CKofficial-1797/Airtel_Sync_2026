package com.udyam.kyc.harness;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.udyam.common.errors.ApiException;
import com.udyam.kyc.DocType;
import com.udyam.kyc.KycDocument;
import com.udyam.kyc.KycProperties;
import com.udyam.kyc.KycService;
import com.udyam.kyc.KycStore;
import com.udyam.kyc.consent.ConsentNotices;
import com.udyam.kyc.extract.ExtractResult;
import com.udyam.kyc.extract.FixtureCache;
import com.udyam.orders.OrderService;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Runs the full KYC flow for the specimen applicants: a throwaway order without a profile (C17 skipped),
 * consent, upload of the applicant's files from data/kyc_documents, submit, and a comparison with the answer key.
 * On a live run (fixture cache off) the extracted fields are saved as golden fixtures for the JUnit tests.
 */
@Service
public class SpecimenHarness {

    private static final Logger log = LoggerFactory.getLogger(SpecimenHarness.class);

    private final OrderService orders;
    private final KycService kyc;
    private final KycStore store;
    private final FixtureCache fixtureCache;
    private final ConsentNotices notices;
    private final KycProperties props;
    private final ObjectMapper json;

    public SpecimenHarness(OrderService orders, KycService kyc, KycStore store, FixtureCache fixtureCache,
                           ConsentNotices notices, KycProperties props, ObjectMapper json) {
        this.orders = orders;
        this.kyc = kyc;
        this.store = store;
        this.fixtureCache = fixtureCache;
        this.notices = notices;
        this.props = props;
        this.json = json;
    }

    public record Row(String applicant, String business, String expected, String actual,
                      List<String> expectedBlocking, List<String> blockingChecks, List<String> warnings,
                      boolean match, String orderRef, int documents, String error) {
    }

    public record Result(int total, int matched, boolean fixtureCache, int fixturesWritten, List<Row> rows) {
    }

    /** applicants: subset like {"K01","K03"}; empty = all 12. */
    public Result run(Set<String> applicants) {
        ExpectedVerdicts key = ExpectedVerdicts.load(json, Path.of(props.expectedVerdicts()).toAbsolutePath());
        Path images = Path.of(props.specimenDir()).toAbsolutePath().normalize();
        boolean live = !fixtureCache.enabled();
        List<Row> rows = new ArrayList<>();
        int written = 0;
        for (ExpectedVerdicts.Applicant a : key.applicants()) {
            if (!applicants.isEmpty() && !applicants.contains(a.applicant())) {
                continue;
            }
            UUID orderId = orders.createBlank("KYC specimen harness: " + a.applicant());
            String orderRef = orders.get(orderId).orderRef();
            int uploaded = 0;
            try {
                kyc.giveConsent(orderId, new KycService.ConsentRequest(notices.version(), "en",
                        Map.of(ConsentNotices.KYC_VERIFICATION, true, ConsentNotices.SERVICE_INSTALLATION, true,
                                ConsentNotices.WHATSAPP_UPDATES, false)));
                for (DocType type : DocType.values()) {
                    Path image = images.resolve(a.applicant() + "_" + type.fileSuffix() + ".png");
                    if (Files.exists(image)) {
                        kyc.upload(orderId, type.name(), Files.readAllBytes(image));
                        uploaded++;
                    }
                }
                if (live) {
                    written += writeFixtures(a.applicant(), store.documents(orderId));
                }
                KycService.SubmitResult r = kyc.submit(orderId);
                boolean match = a.expected().equals(r.overall())
                        && new HashSet<>(a.blocking()).equals(new HashSet<>(r.blockingChecks()));
                rows.add(new Row(a.applicant(), a.business(), a.expected(), r.overall(), a.blocking(),
                        r.blockingChecks(), r.warnings(), match, orderRef, uploaded, null));
            } catch (ApiException | IOException e) {
                rows.add(new Row(a.applicant(), a.business(), a.expected(), null, a.blocking(), List.of(), List.of(),
                        false, orderRef, uploaded, e.getMessage()));
            }
        }
        int matched = (int) rows.stream().filter(Row::match).count();
        log.info("KYC specimen run: {}/{} match (fixture cache {}, {} fixtures written)", matched, rows.size(),
                live ? "off" : "on", written);
        return new Result(rows.size(), matched, !live, written, rows);
    }

    private int writeFixtures(String applicant, List<KycDocument> docs) {
        Path dir = Path.of(props.fixtureDir()).toAbsolutePath().normalize();
        int n = 0;
        try {
            Files.createDirectories(dir);
            for (KycDocument d : docs) {
                ObjectNode f = json.createObjectNode();
                f.put("source", "run-specimens (live extraction)");
                f.put("status", d.status());
                f.put("docType", d.docType().name());
                f.put("detectedDocType", d.detectedDocType());
                ObjectNode q = f.putObject("quality");
                q.put("blurScore", d.blurScore());
                q.put("isBlurry", ExtractResult.UNREADABLE.equals(d.status()));
                q.put("skewDeg", d.skewDeg());
                q.put("deskewed", d.deskewed());
                f.set("fields", d.fields() == null ? json.nullNode() : d.fields());
                f.put("model", d.model());
                f.put("ms", 0);
                json.writerWithDefaultPrettyPrinter().writeValue(
                        dir.resolve(applicant + "_" + d.docType().fileSuffix() + ".json").toFile(), f);
                n++;
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot write fixtures for " + applicant, e);
        }
        return n;
    }
}
