package com.udyam.common.errors;

import java.util.List;
import org.springframework.http.HttpStatus;

/** An API error with an explicit HTTP status, a machine-readable code (e.g. INVALID_CHECKSUM) and optional details. */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final List<String> errors;

    public ApiException(HttpStatus status, String code, String message, List<String> errors) {
        super(message);
        this.status = status;
        this.code = code;
        this.errors = errors;
    }

    public ApiException(HttpStatus status, String code, String message) {
        this(status, code, message, null);
    }

    public HttpStatus getStatus() { return status; }
    public String getCode() { return code; }
    public List<String> getErrors() { return errors; }
}
