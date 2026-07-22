package com.transactiq.processor.outbox;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import org.hibernate.annotations.CreationTimestamp;

/**
 * Processor's view of the shared {@code outbox} table. The processor writes output events
 * (PaymentProcessed / PaymentBlocked) here in the same transaction as the status update; its
 * relay publishes them. (Separate class from the gateway's copy — each service owns its view.)
 */
@Entity
@Table(name = "outbox")
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id", updatable = false)
    private Long id;

    @Column(name = "aggregate_type", length = 64, nullable = false, updatable = false)
    private String aggregateType;

    @Column(name = "aggregate_id", length = 64, nullable = false, updatable = false)
    private String aggregateId;

    @Column(name = "event_id", length = 36, nullable = false, updatable = false, unique = true)
    private String eventId;

    @Column(name = "event_type", length = 64, nullable = false, updatable = false)
    private String eventType;

    @Column(name = "topic", length = 128, nullable = false, updatable = false)
    private String topic;

    @Column(name = "message_key", length = 128, nullable = false, updatable = false)
    private String messageKey;

    @Column(name = "payload", nullable = false, updatable = false, columnDefinition = "TEXT")
    private String payload;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    protected OutboxEvent() {
        // for JPA
    }

    public OutboxEvent(String aggregateType, String aggregateId, String eventId, String eventType,
                       String topic, String messageKey, String payload) {
        this.aggregateType = aggregateType;
        this.aggregateId = aggregateId;
        this.eventId = eventId;
        this.eventType = eventType;
        this.topic = topic;
        this.messageKey = messageKey;
        this.payload = payload;
    }

    public Long getId() {
        return id;
    }

    public String getEventId() {
        return eventId;
    }

    public String getTopic() {
        return topic;
    }

    public String getMessageKey() {
        return messageKey;
    }

    public String getPayload() {
        return payload;
    }

    public Instant getPublishedAt() {
        return publishedAt;
    }

    public void markPublished(Instant when) {
        this.publishedAt = when;
    }
}
