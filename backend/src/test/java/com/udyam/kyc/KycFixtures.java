package com.udyam.kyc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.udyam.TestData;
import com.udyam.kyc.checks.KycValidationService;
import com.udyam.kyc.extract.ExtractResult;
import com.udyam.kyc.extract.Hashing;
import com.udyam.kyc.harness.ExpectedVerdicts;
import com.udyam.registry.CsvUdyamRegistryLookup;
import com.udyam.registry.UdyamLookupService;
import com.udyam.rules.RulesFixture;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.Map;

/** Golden fixtures, the answer key and a hand-wired KycValidationService (CSV registries, no DB, no network). */
public final class KycFixtures {

    public static final Path FIXTURE_DIR = Path.of("src/test/resources/kyc/fixtures");
    public static final Path EXPECTED = Path.of("src/test/resources/kyc/expected-verdicts.json");
    public static final Path SPECIMENS = TestData.DATA_DIR.resolve("kyc_documents");

    public static final ObjectMapper JSON = new ObjectMapper().registerModule(new JavaTimeModule())
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    public static final UdyamLookupService UDYAMS =
            new UdyamLookupService(CsvUdyamRegistryLookup.fromCsv(TestData.UDYAM_CSV));
    public static final KycValidationService VALIDATOR =
            new KycValidationService(RulesFixture.GSTINS, UDYAMS, RulesFixture.APP);

    private KycFixtures() {
    }

    public static ExpectedVerdicts expected() {
        return ExpectedVerdicts.load(JSON, EXPECTED);
    }

    public static ExtractResult fixture(String applicant, DocType type) {
        Path file = FIXTURE_DIR.resolve(applicant + "_" + type.fileSuffix() + ".json");
        try {
            return Files.exists(file) ? JSON.readValue(file.toFile(), ExtractResult.class) : null;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** The applicant's documents as they would be stored after upload (missing slots absent). */
    public static Map<DocType, KycDocument> documents(String applicant) {
        Map<DocType, KycDocument> docs = new EnumMap<>(DocType.class);
        for (DocType t : DocType.values()) {
            ExtractResult r = fixture(applicant, t);
            if (r != null) {
                docs.put(t, KycDocument.from(t, "test", r));
            }
        }
        return docs;
    }

    public static byte[] specimen(String applicant, DocType type) {
        try {
            return Files.readAllBytes(SPECIMENS.resolve(applicant + "_" + type.fileSuffix() + ".png"));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    public static String sha256(String applicant, DocType type) {
        return Hashing.sha256(specimen(applicant, type));
    }

    public static KycProperties props(Path tempDir, boolean fixtureCache) {
        return new KycProperties(tempDir.toString(), 5 * 1024 * 1024, 2555, "2026-07-v1", fixtureCache,
                FIXTURE_DIR.toString(), SPECIMENS.toString(), EXPECTED.toString());
    }
}
