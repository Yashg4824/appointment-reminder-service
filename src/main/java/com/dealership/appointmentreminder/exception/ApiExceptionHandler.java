package com.dealership.appointmentreminder.exception;

import java.time.Clock;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import com.dealership.appointmentreminder.dto.ApiError;

/**
 * Translates exceptions into the API's error responses.
 *
 * <p><b>Why this class exists:</b> without it, a domain exception surfaces as a bare HTTP 500 with
 * a stack trace, which tells a client nothing. Handling the translation in one place keeps
 * controllers free of try/catch and keeps the error contract consistent across endpoints.
 *
 * <p><b>Why it extends {@link ResponseEntityExceptionHandler}:</b> Spring MVC raises its own
 * exceptions for conditions it already knows the correct status for - wrong HTTP method (405),
 * unsupported content type (415), a missing request parameter (400). An advice class with a
 * catch-all {@code @ExceptionHandler(Exception.class)} and nothing else intercepts those too and
 * reports every one of them as a 500, because {@code @ExceptionHandler} methods are resolved
 * before Spring's own {@code DefaultHandlerExceptionResolver}. Extending this base class keeps
 * each of those exceptions mapped to the status Spring intended, while
 * {@link #handleExceptionInternal} gives them the same body shape as everything else.
 *
 * <p><b>Responsibility:</b> exception-to-HTTP mapping only. No business logic, which is why each
 * handler is two lines.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);

    private final Clock clock;

    public ApiExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    // ---------------------------------------------------------------------
    // Spring MVC's own exceptions
    // ---------------------------------------------------------------------

    /** Bean Validation failures on a request body: missing or malformed required fields. */
    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException e,
                                                                  HttpHeaders headers,
                                                                  HttpStatus status,
                                                                  WebRequest request) {
        List<String> details = e.getBindingResult().getFieldErrors().stream()
                .map(fieldError -> fieldError.getField() + ": " + fieldError.getDefaultMessage())
                .collect(Collectors.toList());
        ApiError body = error(status, "VALIDATION_FAILED", "Request validation failed", details);
        return handleExceptionInternal(e, body, headers, status, request);
    }

    /**
     * Unparseable JSON. This is also what a timestamp with no UTC offset produces, because
     * {@code OffsetDateTime} cannot be built from a bare local date-time - the API's time-zone
     * contract enforced by the type system.
     */
    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(HttpMessageNotReadableException e,
                                                                  HttpHeaders headers,
                                                                  HttpStatus status,
                                                                  WebRequest request) {
        ApiError body = error(status, "MALFORMED_REQUEST",
                "Request body could not be parsed. Timestamps must be ISO-8601 with an offset, "
                        + "for example 2026-09-20T14:30:00-04:00", null);
        return handleExceptionInternal(e, body, headers, status, request);
    }

    /**
     * Every remaining Spring MVC exception passes through here - 405, 415, a missing request
     * parameter - keeping the status Spring already determined and gaining our error body.
     */
    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception e,
                                                             Object body,
                                                             HttpHeaders headers,
                                                             HttpStatus status,
                                                             WebRequest request) {
        Object payload = (body instanceof ApiError)
                ? body
                : error(status, status.name(), e.getMessage(), null);
        return super.handleExceptionInternal(e, payload, headers, status, request);
    }

    // ---------------------------------------------------------------------
    // Domain exceptions
    // ---------------------------------------------------------------------

    /** A business rule broken by an otherwise well-formed request. */
    @ExceptionHandler(InvalidAppointmentRequestException.class)
    public ResponseEntity<ApiError> handleInvalidRequest(InvalidAppointmentRequestException e) {
        return build(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", e.getMessage());
    }

    @ExceptionHandler(AppointmentNotFoundException.class)
    public ResponseEntity<ApiError> handleNotFound(AppointmentNotFoundException e) {
        return build(HttpStatus.NOT_FOUND, "APPOINTMENT_NOT_FOUND", e.getMessage());
    }

    /** The request is valid but conflicts with the resource's current state. */
    @ExceptionHandler(InvalidAppointmentStateException.class)
    public ResponseEntity<ApiError> handleInvalidState(InvalidAppointmentStateException e) {
        return build(HttpStatus.CONFLICT, "INVALID_APPOINTMENT_STATE", e.getMessage());
    }

    /**
     * Anything genuinely unanticipated. Spring MVC's own exceptions never reach this method:
     * they are matched by the more specific handlers inherited from the base class.
     */
    @ExceptionHandler(Exception.class)
    public ResponseEntity<ApiError> handleUnexpected(Exception e) {
        log.error("Unhandled exception serving request", e);
        return build(HttpStatus.INTERNAL_SERVER_ERROR, "INTERNAL_ERROR", "An unexpected error occurred");
    }

    private ResponseEntity<ApiError> build(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(error(status, code, message, null));
    }

    private ApiError error(HttpStatus status, String code, String message, List<String> details) {
        return new ApiError(clock.instant(), status.value(), code, message, details);
    }
}
