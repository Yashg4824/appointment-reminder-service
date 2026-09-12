package com.dealership.appointmentreminder.appointment;

/** Thrown when an appointment id does not exist. Mapped to HTTP 404. */
public class AppointmentNotFoundException extends RuntimeException {

    public AppointmentNotFoundException(Long appointmentId) {
        super("Appointment not found: " + appointmentId);
    }
}
