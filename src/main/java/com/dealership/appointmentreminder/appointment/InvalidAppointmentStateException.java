package com.dealership.appointmentreminder.appointment;

/**
 * Thrown when an operation is not legal for the appointment's current status - for example
 * rescheduling an appointment that has already been cancelled. Mapped to HTTP 409 Conflict,
 * because the request is valid in itself but conflicts with the state of the resource.
 */
public class InvalidAppointmentStateException extends RuntimeException {

    public InvalidAppointmentStateException(String message) {
        super(message);
    }
}
