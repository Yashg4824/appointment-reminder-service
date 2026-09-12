package com.dealership.appointmentreminder.appointment;

/**
 * Thrown when a request is well-formed JSON but breaks a business rule - a scheduled time in the
 * past, or a customer contact that is neither an email address nor a phone number. Mapped to
 * HTTP 400.
 *
 * <p>These rules live in {@code AppointmentService} rather than in Bean Validation annotations
 * because they depend on the injected {@code Clock} or on domain knowledge, and because keeping
 * them there means they can be tested without standing up the web layer.
 */
public class InvalidAppointmentRequestException extends RuntimeException {

    public InvalidAppointmentRequestException(String message) {
        super(message);
    }
}
