-- V2 — transactional outbox + idempotent-consumer dedup table.
--
-- These are the two tables that turn an at-least-once event stream into effectively-once
-- processing:
--   * outbox           — written in the SAME DB transaction as the business row, so we never
--                        have a committed business change without its event (or vice versa).
--                        A relay publishes unpublished rows to Kafka, then stamps published_at.
--   * processed_events — the consumer claims each event_id here (PRIMARY KEY) in the same
--                        transaction as its business effect, so a re-delivered event is
--                        detected and skipped.

CREATE TABLE outbox (
    id             BIGINT       NOT NULL AUTO_INCREMENT PRIMARY KEY,
    aggregate_type VARCHAR(64)  NOT NULL,           -- e.g. 'payment'
    aggregate_id   VARCHAR(64)  NOT NULL,           -- payment id
    event_id       VARCHAR(36)  NOT NULL,           -- unique event identity (consumer dedup key)
    event_type     VARCHAR(64)  NOT NULL,           -- PaymentRequested | PaymentProcessed | PaymentBlocked
    topic          VARCHAR(128) NOT NULL,           -- destination Kafka topic
    message_key    VARCHAR(128) NOT NULL,           -- Kafka partition key (payment id -> per-key ordering)
    payload        TEXT         NOT NULL,           -- event JSON (TEXT for portability; MySQL JSON also works)
    created_at     TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    published_at   TIMESTAMP(3) NULL,               -- set once the broker has acked the publish

    -- Each event_id appears once in the outbox.
    CONSTRAINT uq_outbox_event_id UNIQUE (event_id)
) ENGINE = InnoDB;

-- Relay hot path: "unpublished rows, oldest first". published_at leads so NULLs cluster.
CREATE INDEX idx_outbox_unpublished ON outbox (published_at, id);

CREATE TABLE processed_events (
    event_id     VARCHAR(36)  NOT NULL PRIMARY KEY,
    processed_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3)
) ENGINE = InnoDB;
