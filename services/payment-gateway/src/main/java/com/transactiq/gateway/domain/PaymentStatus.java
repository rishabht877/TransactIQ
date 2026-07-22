package com.transactiq.gateway.domain;

/** Lifecycle of a payment. Gateway creates RECEIVED; processor moves it to PROCESSED/BLOCKED. */
public enum PaymentStatus {
    RECEIVED,
    PROCESSED,
    BLOCKED
}
