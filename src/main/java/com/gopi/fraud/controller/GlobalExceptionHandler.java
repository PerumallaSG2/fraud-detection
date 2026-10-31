package com.gopi.fraud.controller;

import com.gopi.fraud.dto.ErrorResponse;
import com.gopi.fraud.exception.AlertAlreadyResolvedException;
import com.gopi.fraud.exception.AlertNotFoundException;
import com.gopi.fraud.exception.EventPublishException;
import com.gopi.fraud.exception.TransactionNotFoundException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.TypeMismatchException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into an {@link ErrorResponse}. Extending {@link ResponseEntityExceptionHandler} covers
 * Spring's own exceptions (405, 415, unknown path, ...) too. Responses never include exception details.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler({TransactionNotFoundException.class, AlertNotFoundException.class})
    ResponseEntity<Object> handleNotFound(RuntimeException ex) {
        return body(HttpStatus.NOT_FOUND, ex.getMessage(), List.of());
    }

    @ExceptionHandler(AlertAlreadyResolvedException.class)
    ResponseEntity<Object> handleAlreadyResolved(AlertAlreadyResolvedException ex) {
        return body(HttpStatus.CONFLICT, ex.getMessage(), List.of());
    }

    /** Kafka did not store the transaction, so nothing will score it; the client can safely retry. */
    @ExceptionHandler(EventPublishException.class)
    ResponseEntity<Object> handlePublishFailure(EventPublishException ex) {
        log.warn("{}: {}", ex.getMessage(), ex.getCause() == null ? "" : ex.getCause().toString());
        return body(HttpStatus.SERVICE_UNAVAILABLE, "Transaction could not be accepted, try again", List.of());
    }

    @ExceptionHandler(Exception.class)
    ResponseEntity<Object> handleUnexpected(Exception ex) {
        log.error("Unhandled exception", ex);
        return body(HttpStatus.INTERNAL_SERVER_ERROR, "Unexpected error", List.of());
    }

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = ex.getBindingResult().getFieldErrors().stream()
                .map(error -> new ErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
                .toList();
        return body(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors);
    }

    /** Constraint violations on request parameters, e.g. {@code size} above its maximum. */
    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(HandlerMethodValidationException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<ErrorResponse.FieldError> fieldErrors = ex.getParameterValidationResults().stream()
                .flatMap(result -> result.getResolvableErrors().stream()
                        .map(error -> new ErrorResponse.FieldError(result.getMethodParameter().getParameterName(),
                                error.getDefaultMessage())))
                .toList();
        return body(HttpStatus.BAD_REQUEST, "Validation failed", fieldErrors);
    }

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException ex,
            HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        return body(HttpStatus.BAD_REQUEST, "Malformed request body", List.of());
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(TypeMismatchException ex, HttpHeaders headers,
            HttpStatusCode status, WebRequest request) {
        return body(HttpStatus.BAD_REQUEST, "Invalid value for '" + ex.getPropertyName() + "'", List.of());
    }

    /** Fallback for Spring's other exceptions: keep the status, replace the body with an ErrorResponse. */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {
        HttpStatus status = HttpStatus.valueOf(statusCode.value());
        return ResponseEntity.status(statusCode).headers(headers)
                .body(new ErrorResponse(status.value(), status.getReasonPhrase(), status.getReasonPhrase(),
                        List.of()));
    }

    private static ResponseEntity<Object> body(HttpStatus status, String message,
                                               List<ErrorResponse.FieldError> fieldErrors) {
        return ResponseEntity.status(status)
                .body(new ErrorResponse(status.value(), status.getReasonPhrase(), message, fieldErrors));
    }
}
