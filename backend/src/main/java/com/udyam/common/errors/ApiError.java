package com.udyam.common.errors;

/** JSON error body returned by every API error. */
public record ApiError(int status, String error, String message, String path) {
}
