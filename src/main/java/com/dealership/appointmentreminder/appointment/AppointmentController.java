package com.dealership.appointmentreminder.appointment;

import javax.validation.Valid;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.dealership.appointmentreminder.appointment.dto.AppointmentResponse;
import com.dealership.appointmentreminder.appointment.dto.CreateAppointmentRequest;
import com.dealership.appointmentreminder.appointment.dto.RescheduleAppointmentRequest;

/**
 * HTTP entry point for appointments.
 *
 * <p><b>Why this class exists:</b> to keep HTTP concerns - request binding, validation triggering,
 * status-code mapping - out of the business logic. {@link AppointmentService} can then be tested
 * directly, without MockMvc or a servlet container.
 *
 * <p><b>Responsibility:</b> translate HTTP to a service call and back. It contains no business
 * rules, no time arithmetic and no persistence.
 *
 * <p><b>SOLID:</b> Single Responsibility, and Dependency Inversion in the small - it depends on
 * the service, never on a repository.
 *
 * <p><b>Dependencies:</b> {@link AppointmentService} only. Anything else here would mean business
 * logic had leaked into the web layer.
 */
@RestController
@RequestMapping("/appointments")
public class AppointmentController {

    private final AppointmentService appointmentService;

    public AppointmentController(AppointmentService appointmentService) {
        this.appointmentService = appointmentService;
    }

    /**
     * Creates an appointment and its reminders.
     *
     * <p>{@code @Valid} triggers the Bean Validation rules on the request body, so malformed
     * input never reaches the service.
     *
     * @return 201 Created, with the appointment and the reminder plan that was generated
     */
    @PostMapping
    public ResponseEntity<AppointmentResponse> create(@Valid @RequestBody CreateAppointmentRequest request) {
        AppointmentResponse response = appointmentService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Returns an appointment with the full state of its reminders.
     *
     * <p>This is the endpoint that demonstrates the no-duplicates requirement from outside the
     * system: it shows each reminder's status, attempt count and send time.
     */
    @GetMapping("/{id}")
    public ResponseEntity<AppointmentResponse> findById(@PathVariable("id") Long id) {
        return ResponseEntity.ok(appointmentService.findById(id));
    }

    /**
     * Cancels an appointment and suppresses its unsent reminders.
     *
     * <p>Idempotent: cancelling an already-cancelled appointment returns 200 both times, because
     * a client retrying after a timeout should not receive an error for work already done.
     */
    @PostMapping("/{id}/cancel")
    public ResponseEntity<AppointmentResponse> cancel(@PathVariable("id") Long id) {
        return ResponseEntity.ok(appointmentService.cancel(id));
    }

    /**
     * Moves an appointment to a new time. Unsent reminders move with it; reminders already sent
     * are left alone, so no customer receives a second reminder of the same type.
     */
    @PatchMapping("/{id}")
    public ResponseEntity<AppointmentResponse> reschedule(@PathVariable("id") Long id,
                                                          @Valid @RequestBody RescheduleAppointmentRequest request) {
        return ResponseEntity.ok(appointmentService.reschedule(id, request.getScheduledAt().toInstant()));
    }
}
