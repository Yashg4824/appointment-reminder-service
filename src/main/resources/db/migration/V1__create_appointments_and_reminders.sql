--
-- V1: core schema for the appointment booking / reminder service.
--
-- This migration is the most important file in the project. The requirement
-- "a customer must never receive the same reminder twice, and this must be provable"
-- is answered here, by uq_reminders_appointment_type, rather than by application code.
--

CREATE TABLE appointments (
    id               BIGSERIAL    PRIMARY KEY,
    dealership_id    BIGINT       NOT NULL,
    customer_name    VARCHAR(200) NOT NULL,
    customer_contact VARCHAR(200) NOT NULL,
    -- The absolute instant of the appointment. All reminder times derive from this.
    -- TIMESTAMPTZ (not TIMESTAMP) so the value is unambiguous across time zones and DST.
    scheduled_at     TIMESTAMPTZ  NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL
);

-- Listing a dealership's appointments. The tenant column leads because every
-- such query is tenant-scoped.
CREATE INDEX idx_appointments_dealership_scheduled
    ON appointments (dealership_id, scheduled_at);


CREATE TABLE reminders (
    id               BIGSERIAL    PRIMARY KEY,
    appointment_id   BIGINT       NOT NULL,
    reminder_type    VARCHAR(30)  NOT NULL,
    -- When this reminder becomes due to be sent (appointment.scheduled_at minus the type's offset).
    scheduled_at     TIMESTAMPTZ  NOT NULL,
    status           VARCHAR(20)  NOT NULL,
    attempt_count    INT          NOT NULL DEFAULT 0,
    -- Set while a worker holds this row. Once this instant passes without a terminal
    -- outcome the row is reclaimable by any worker. This is the entire crash-recovery
    -- mechanism (architecture document, section 11).
    processing_until TIMESTAMPTZ  NULL,
    -- Gates retries, so a failed attempt is not retried on the very next worker cycle.
    last_attempt_at  TIMESTAMPTZ  NULL,
    sent_at          TIMESTAMPTZ  NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    updated_at       TIMESTAMPTZ  NOT NULL,

    CONSTRAINT fk_reminders_appointment
        FOREIGN KEY (appointment_id) REFERENCES appointments (id) ON DELETE CASCADE,

    -- THE duplicate-prevention mechanism. A second reminder of the same type for the
    -- same appointment is not merely prevented, it is unrepresentable: any code path
    -- that attempts it fails loudly with a constraint violation rather than quietly
    -- double-sending. Verifiable by reading this line.
    CONSTRAINT uq_reminders_appointment_type
        UNIQUE (appointment_id, reminder_type)
);

-- The due-reminder claim query:
--   WHERE status = 'PENDING' AND scheduled_at <= :now ... FOR UPDATE SKIP LOCKED
-- Both predicates are covered, so the claim is an index range scan, not a table scan.
CREATE INDEX idx_reminders_status_scheduled
    ON reminders (status, scheduled_at);

-- The stale-work reclaim query:
--   WHERE status = 'PROCESSING' AND processing_until < :now
CREATE INDEX idx_reminders_status_processing_until
    ON reminders (status, processing_until);
