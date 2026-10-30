package com.udyam.kyc;

import java.nio.file.Path;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * app.kyc.* settings.
 *
 * @param tempDir          where uploads live for the few seconds of extraction (always deleted afterwards)
 * @param maxUploadBytes   upload size limit (the ai-service enforces the same 5 MB)
 * @param retentionDays    how long extracted fields are kept (shown in the consent notice)
 * @param noticeVersion    current consent notice version; consent must quote it
 * @param fixtureCache     DEV ONLY: reuse golden fixtures for uploads whose sha256 matches a specimen image
 *                         (no Gemini call). Off by default.
 * @param fixtureDir       golden fixtures folder (read by the cache, written by a live specimen run)
 * @param specimenDir      the 47 specimen images
 * @param expectedVerdicts the answer key used by the specimen harness
 */
@ConfigurationProperties(prefix = "app.kyc")
public record KycProperties(
        String tempDir,
        @DefaultValue("5242880") long maxUploadBytes,
        @DefaultValue("2555") int retentionDays,
        @DefaultValue("2026-07-v1") String noticeVersion,
        @DefaultValue("false") boolean fixtureCache,
        @DefaultValue("src/test/resources/kyc/fixtures") String fixtureDir,
        @DefaultValue("../data/kyc_documents") String specimenDir,
        @DefaultValue("src/test/resources/kyc/expected-verdicts.json") String expectedVerdicts) {

    /** tempDir resolved against the working directory; defaults to {java.io.tmpdir}/udyam-kyc. */
    public Path tempPath() {
        String dir = tempDir == null || tempDir.isBlank()
                ? Path.of(System.getProperty("java.io.tmpdir"), "udyam-kyc").toString() : tempDir;
        return Path.of(dir).toAbsolutePath().normalize();
    }
}
