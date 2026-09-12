package com.dealership.appointmentreminder.web;

import java.time.Instant;
import java.util.List;

/**
 * The single error body shape the API returns.
 *
 * <p>Deliberately small - an error code, a human-readable message, and optionally the list of
 * field-level validation problems. The architecture rules out a formal problem-detail framework
 * at this scope (architecture document, section 10 of the exclusions).
 */
public class ApiError {

    private final Instant timestamp;
    private final int status;
    private final String error;
    private final String message;
    private final List<String> details;

    public ApiError(Instant timestamp, int status, String error, String message, List<String> details) {
        this.timestamp = timestamp;
        this.status = status;
        this.error = error;
        this.message = message;
        this.details = details;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public int getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }

    public List<String> getDetails() {
        return details;
    }
}
