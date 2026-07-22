package com.transactiq.processor.domain;

/** Lifecycle of a payment. The processor moves RECEIVED -> PROCESSED (approved) or BLOCKED. */
public enum PaymentStatus {
    RECEIVED,
    PROCESSED,
    BLOCKED
}
