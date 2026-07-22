package com.transactiq.processor.outbox;

import com.transactiq.processor.service.PaymentProcessingService;
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
 * Publishes the processor's OUTPUT events (PaymentProcessed / PaymentBlocked) from the outbox.
 * Same relay pattern as the gateway; it only owns the processed/blocked topics so the two
 * relays never contend over the shared outbox table (and SKIP LOCKED protects them if they did).
 */
@Component
public class OutboxRelay {

    private static final List<String> OWNED_TOPICS =
            List.of(PaymentProcessingService.TOPIC_PROCESSED, PaymentProcessingService.TOPIC_BLOCKED);
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
            event.markPublished(Instant.now());
        }
        log.debug("Relayed {} outcome event(s)", batch.size());
    }

    private void publish(OutboxEvent event) {
        try {
            kafkaTemplate.send(event.getTopic(), event.getMessageKey(), event.getPayload())
                    .get(PUBLISH_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing outbox event " + event.getEventId(), e);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to publish outbox event " + event.getEventId(), e);
        }
    }
}
