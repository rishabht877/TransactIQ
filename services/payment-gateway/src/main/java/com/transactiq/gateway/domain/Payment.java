package com.transactiq.gateway.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

/** Business system-of-record row. Maps to the shared {@code payments} table (V1 migration). */
@Entity
@Table(name = "payments")
public class Payment {

    @Id
    @Column(name = "id", length = 36, updatable = false, nullable = false)
    private String id;

    @Column(name = "idempotency_key", length = 80, updatable = false)
    private String idempotencyKey;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "currency", length = 3, nullable = false)
    private String currency;

    @Column(name = "customer_id", length = 64, nullable = false)
    private String customerId;

    @Column(name = "card_last4", length = 4)
    private String cardLast4;

    @Column(name = "country", length = 2)
    private String country;

    @Column(name = "merchant", length = 128)
    private String merchant;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 16, nullable = false)
    private PaymentStatus status;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private Instant updatedAt;

    protected Payment() {
        // for JPA
    }

    public Payment(String id, String idempotencyKey, BigDecimal amount, String currency,
                   String customerId, String cardLast4, String country, String merchant,
                   PaymentStatus status) {
        this.id = id;
        this.idempotencyKey = idempotencyKey;
        this.amount = amount;
        this.currency = currency;
        this.customerId = customerId;
        this.cardLast4 = cardLast4;
        this.country = country;
        this.merchant = merchant;
        this.status = status;
    }

    public String getId() {
        return id;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public String getCurrency() {
        return currency;
    }

    public String getCustomerId() {
        return customerId;
    }

    public String getCardLast4() {
        return cardLast4;
    }

    public String getCountry() {
        return country;
    }

    public String getMerchant() {
        return merchant;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public void setStatus(PaymentStatus status) {
        this.status = status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
