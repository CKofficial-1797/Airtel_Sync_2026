package com.udyam.kyc.extract;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.udyam.kyc.DocType;
import com.udyam.kyc.KycProperties;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * DEV ONLY (app.kyc.fixture-cache=true): when an upload is byte-identical (same sha256) to one of the
 * specimen images, return its golden fixture instead of calling Gemini. Saves the limited API quota
 * during demos and repeated harness runs. Any other upload still goes to the ai-service.
 */
@Component
public class FixtureCache {

    private static final Logger log = LoggerFactory.getLogger(FixtureCache.class);

    private final KycProperties props;
    private final ObjectMapper json;
    private Map<String, Path> fixtureBySha;

    public FixtureCache(KycProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
    }

    public boolean enabled() {
        return props.fixtureCache();
    }

    /**
     * The result the ai-service would give for this specimen in this slot: its fixture in its own slot,
     * WRONG_DOC_TYPE in another slot (a blurry specimen stays UNREADABLE in any slot, as the quality gate runs
     * first). Empty when the cache is off or the image is not a specimen.
     */
    public Optional<ExtractResult> find(String sha256, DocType docType) {
        if (!enabled()) {
            return Optional.empty();
        }
        Path fixture = index().get(sha256);
        if (fixture == null) {
            return Optional.empty();
        }
        ExtractResult r;
        try {
            r = json.readValue(fixture.toFile(), ExtractResult.class);
        } catch (IOException e) {
            throw new UncheckedIOException("Unreadable fixture " + fixture.getFileName(), e);
        }
        if (fixture.getFileName().toString().endsWith("_" + docType.fileSuffix() + ".json")) {
            return Optional.of(r);
        }
        String status = ExtractResult.UNREADABLE.equals(r.status()) ? ExtractResult.UNREADABLE
                : ExtractResult.WRONG_DOC_TYPE;
        return Optional.of(new ExtractResult(status, docType.name(), r.detectedDocType(), r.quality(), null, r.model(),
                r.ms()));
    }

    private synchronized Map<String, Path> index() {
        if (fixtureBySha == null) {
            Map<String, Path> map = new HashMap<>();
            Path fixtures = Path.of(props.fixtureDir()).toAbsolutePath().normalize();
            Path images = Path.of(props.specimenDir()).toAbsolutePath().normalize();
            try (var files = Files.list(images)) {
                for (Path image : files.filter(p -> p.toString().endsWith(".png")).toList()) {
                    String name = image.getFileName().toString().replace(".png", ".json");
                    Path fixture = fixtures.resolve(name);
                    if (Files.exists(fixture)) {
                        map.put(Hashing.sha256(Files.readAllBytes(image)), fixture);
                    }
                }
            } catch (IOException e) {
                log.warn("Fixture cache disabled: cannot read {} ({})", images, e.getClass().getSimpleName());
            }
            log.info("Fixture cache: {} specimen fixtures indexed", map.size());
            fixtureBySha = map;
        }
        return fixtureBySha;
    }
}
