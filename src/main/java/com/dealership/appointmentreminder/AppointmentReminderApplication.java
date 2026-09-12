package com.dealership.appointmentreminder;

import java.util.TimeZone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Application entry point.
 *
 * <p>The JVM default time zone is pinned to UTC before Spring starts. Architecture document
 * section 7 requires that no part of the system ever depends on the server's local time zone;
 * doing this here means it is true for every component, including the JDBC driver and any
 * library that reads the default zone, rather than being a rule each class has to remember.
 */
@SpringBootApplication
public class AppointmentReminderApplication {

    public static void main(String[] args) {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));
        SpringApplication.run(AppointmentReminderApplication.class, args);
    }
}
