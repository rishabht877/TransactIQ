-- V1 — payments (business system-of-record row).
--
-- Shared schema note: this is a single-DB project. The migration scripts live once, under
-- the repo-root db-migrations/ directory, and BOTH payment-gateway and payment-processor add
-- it to their classpath (see each build.gradle.kts). Both services run Flyway against the
-- same MySQL; Flyway takes a DB lock on flyway_schema_history so concurrent startups apply
-- migrations exactly once. In a true polyrepo/microservice setup each service would own its
-- own schema (or DB) and share events via a schema registry rather than a shared DB.

CREATE TABLE payments (
    -- Application-generated UUID, known before we publish so downstream events can reference it.
    id              VARCHAR(36)   NOT NULL PRIMARY KEY,

    -- Client-supplied Idempotency-Key. UNIQUE is the DURABLE dedup guarantee (Redis is only a
    -- fast-path cache added in Phase 2). Nullable because Phase 1 does not populate it yet;
    -- MySQL allows multiple NULLs under a UNIQUE index.
    idempotency_key VARCHAR(80)   NULL,

    amount          DECIMAL(19,4) NOT NULL,
    currency        CHAR(3)       NOT NULL,

    -- Context carried for the Phase 3 fraud triage (velocity/amount/geo). Present now so we
    -- do not need a schema change later.
    customer_id     VARCHAR(64)   NOT NULL,
    card_last4      CHAR(4)       NULL,
    country         CHAR(2)       NULL,
    merchant        VARCHAR(128)  NULL,

    -- RECEIVED (gateway) -> PROCESSED | BLOCKED (processor). Stored as string for readability.
    status          VARCHAR(16)   NOT NULL,

    created_at      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    updated_at      TIMESTAMP(3)  NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),

    CONSTRAINT uq_payments_idempotency_key UNIQUE (idempotency_key)
) ENGINE = InnoDB;

CREATE INDEX idx_payments_customer ON payments (customer_id);
CREATE INDEX idx_payments_status   ON payments (status);
