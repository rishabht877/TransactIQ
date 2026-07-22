package com.transactiq.processor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Processor's view of the shared {@code payments} row.
 *
 * <p>Intentionally maps only {@code id} and {@code status}: the processor's only DB write is
 * moving the payment to a terminal state. All the data it needs to make that decision (amount,
 * customer, country, ...) arrives in the event payload, not from re-reading the row. Mapping a
 * minimal set means the UPDATE only ever touches {@code status} (updated_at is maintained by
 * the DB's ON UPDATE clause). Each service owning its own persistence view avoids coupling.
 */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private PaymentStatus status;

    protected Payment() {
        // for JPA
    }

    public String getId() {
        return id;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentStatus status) {
        this.status = status;
    }
}
