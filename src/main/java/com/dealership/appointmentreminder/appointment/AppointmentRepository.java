package com.dealership.appointmentreminder.appointment;

import java.util.Optional;

import javax.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

/**
 * Database access for {@link Appointment}.
 *
 * <p><b>Why this exists:</b> it keeps persistence concerns out of {@code AppointmentService},
 * so the service reads as business logic rather than as query construction.
 *
 * <p><b>SOLID:</b> Dependency Inversion - the service depends on this interface, and Spring Data
 * supplies the implementation. Tests can substitute a fake without a database.
 */
@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Long> {

    /**
     * Loads an appointment and holds a row lock on it for the remainder of the transaction
     * ({@code SELECT ... FOR UPDATE}).
     *
     * <p>Used by cancel and reschedule. Architecture document section 9c relies on this: taking
     * the row lock is what makes a cancellation and a concurrent worker claim serialise against
     * each other instead of interleaving.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT a FROM Appointment a WHERE a.id = :id")
    Optional<Appointment> findByIdForUpdate(@Param("id") Long id);
}
