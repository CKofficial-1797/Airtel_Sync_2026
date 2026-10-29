package com.udyam.common.errors;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

/**
 * JSON error body returned by every API error. code and errors are optional (omitted when null),
 * so Phase 1 errors keep their original shape {status, error, message, path}.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record ApiError(int status, String error, String code, String message, String path, List<String> errors) {

    public ApiError(int status, String error, String message, String path) {
        this(status, error, null, message, path, null);
    }
}
