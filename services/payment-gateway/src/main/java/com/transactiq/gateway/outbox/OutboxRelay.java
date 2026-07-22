package com.transactiq.gateway.outbox;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Polls the outbox for unpublished {@code payments.requested} rows and publishes them to Kafka.
 *
 * <p><b>Why a relay (the dual-write fix):</b> the business row and the outbox row are written in
 * one DB transaction, so they commit or fail together. This relay then publishes asynchronously.
 * If it crashes after publishing but before stamping {@code published_at}, the row is picked up
 * again next poll and republished — i.e. <i>at-least-once</i> delivery. The processor's
 * idempotent consumer collapses duplicates, giving <i>effectively-once</i> end-to-end (NOT
 * exactly-once).
 *
 * <p><b>Tradeoff:</b> we publish inside the transaction while holding SKIP-LOCKED row locks, so
 * lock-hold time includes the Kafka round-trip. Fine at this scale; a higher-throughput design
 * would send the batch, then bulk-stamp published_at. Scaling the relay is an interview topic
 * (see INTERVIEW_NOTES in Phase 7): partition the outbox, or switch to CDC (Debezium).
 */
@Component
public class OutboxRelay {

    /** Topics this service's relay owns. The processor owns processed/blocked (shared table). */
    private static final List<String> OWNED_TOPICS = List.of("payments.requested");
    private static final long PUBLISH_TIMEOUT_SECONDS = 10;

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outboxRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final int batchSize;

    public OutboxRelay(OutboxRepository outboxRepository,
                       KafkaTemplate<String, String> kafkaTemplate,
                       @Value("${transactiq.outbox.batch-size:100}") int batchSize) {
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.batchSize = batchSize;
    }

    @Scheduled(fixedDelayString = "${transactiq.outbox.poll-interval-ms:500}")
    @Transactional
    public void publishBatch() {
        List<OutboxEvent> batch =
                outboxRepository.findUnpublishedByTopics(OWNED_TOPICS, PageRequest.of(0, batchSize));
        if (batch.isEmpty()) {
            return;
        }
        for (OutboxEvent event : batch) {
            publish(event);
            // Dirty-checked UPDATE on commit; if commit fails the row stays unpublished -> retried.
            event.markPublished(Instant.now());
        }
        log.debug("Relayed {} outbox event(s)", batch.size());
    }

    private void publish(OutboxEvent event) {
        try {
            kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload())
                    .get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing outbox event " + event.getEventId(), e);
        } catch (Exception e) {
            // Propagate so the whole batch tx rolls back; nothing is marked published -> safe retry.
            throw new IllegalStateException("Failed to publish outbox event " + event.getEventId(), e);
        }
    }
}
