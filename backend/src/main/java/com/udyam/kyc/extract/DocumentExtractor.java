package com.udyam.kyc.extract;

import com.udyam.kyc.DocType;
import java.nio.file.Path;

/** Turns one uploaded image into fields. App: AiServiceExtractor (ai-service /extract); tests: fakes. */
public interface DocumentExtractor {

    /**
     * @throws ExtractionUnavailableException when the ai-service is down, times out or fails
     * @throws InvalidImageException          when the ai-service rejects the file itself
     */
    ExtractResult extract(Path image, DocType docType);

    class ExtractionUnavailableException extends RuntimeException {
        public ExtractionUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    class InvalidImageException extends RuntimeException {
        public InvalidImageException(String message) {
            super(message);
        }
    }
}
