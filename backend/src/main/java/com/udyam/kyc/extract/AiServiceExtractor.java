package com.udyam.kyc.extract;

import com.udyam.kyc.DocType;
import java.nio.file.Path;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Sends one image to the ai-service POST /extract (multipart: file, docType; X-Internal-Token set by the client). */
@Component
public class AiServiceExtractor implements DocumentExtractor {

    private final RestClient rest;

    public AiServiceExtractor(@Qualifier("aiServiceRestClient") RestClient rest) {
        this.rest = rest;
    }

    @Override
    public ExtractResult extract(Path image, DocType docType) {
        MultiValueMap<String, Object> parts = new LinkedMultiValueMap<>();
        parts.add("file", new FileSystemResource(image));
        parts.add("docType", docType.name());
        ExtractResult result;
        try {
            result = rest.post()
                    .uri("/extract")
                    .contentType(MediaType.MULTIPART_FORM_DATA)
                    .body(parts)
                    .retrieve()
                    .body(ExtractResult.class);
        } catch (HttpClientErrorException e) {
            if (e.getStatusCode().isSameCodeAs(HttpStatus.BAD_REQUEST)) {
                throw new InvalidImageException("The image was rejected: use a clear PNG or JPEG photo");
            }
            throw new ExtractionUnavailableException("ai-service /extract failed: " + e.getStatusCode(), e);
        } catch (RestClientException e) {
            throw new ExtractionUnavailableException("ai-service /extract failed: " + e.getClass().getSimpleName(), e);
        }
        if (result == null || result.status() == null) {
            throw new ExtractionUnavailableException("ai-service /extract returned an empty response", null);
        }
        return result;
    }
}
