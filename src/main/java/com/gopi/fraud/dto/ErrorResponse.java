package com.gopi.fraud.dto;

import java.util.List;

/** The body of every error response. {@code fieldErrors} is empty unless request validation failed. */
public record ErrorResponse(int status, String error, String message, List<FieldError> fieldErrors) {

    public record FieldError(String field, String message) {
    }

    public static ErrorResponse of(int status, String error, String message) {
        return new ErrorResponse(status, error, message, List.of());
    }
}
