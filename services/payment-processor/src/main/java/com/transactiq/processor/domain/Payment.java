package com.transactiq.processor.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.math.BigDecimal;

/**
 * Processor's view of the shared {@code payments} row. It maps the columns it writes: the
 * terminal {@code status} plus the fraud triage result ({@code fraud_decision}, {@code
 * risk_score}, {@code fraud_reasons}) persisted for the dashboard. Everything it needs to make
 * the decision arrives in the event payload, so it never re-reads amount/customer/etc.
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

    @Column(name = "fraud_decision", length = 16)
    private String fraudDecision;

    @Column(name = "risk_score", precision = 4, scale = 3)
    private BigDecimal riskScore;

    @Column(name = "fraud_reasons", columnDefinition = "TEXT")
    private String fraudReasons;

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

    public void setFraudDecision(String fraudDecision) {
        this.fraudDecision = fraudDecision;
    }

    public void setRiskScore(BigDecimal riskScore) {
        this.riskScore = riskScore;
    }

    public void setFraudReasons(String fraudReasons) {
        this.fraudReasons = fraudReasons;
    }
}
