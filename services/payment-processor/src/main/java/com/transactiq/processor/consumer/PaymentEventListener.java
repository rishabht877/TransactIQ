package com.transactiq.processor.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transactiq.processor.event.PaymentRequestedEvent;
import com.transactiq.processor.service.PaymentProcessingService;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.DltHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.annotation.RetryableTopic;
import org.springframework.kafka.retrytopic.DltStrategy;
import org.springframework.kafka.retrytopic.TopicSuffixingStrategy;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.retry.annotation.Backoff;
import org.springframework.stereotype.Component;

/**
 * Consumes PaymentRequested with NON-BLOCKING retries + a DLQ.
 *
 * <p>{@code @RetryableTopic} routes a failing record to timed retry topics
 * ({@code payments.requested-retry-0/1/2}) rather than blocking the main partition, then to a
 * dead-letter topic ({@code payments.requested-dlt}) after the attempts are exhausted. Because
 * every retry carries the same {@code eventId}, the idempotent consumer's {@code processed_events}
 * claim keeps the effect exactly-once even across retries and rebalances.
 */
@Component
public class PaymentEventListener {

    public static final String TOPIC_PAYMENTS_REQUESTED = "payments.requested";

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final PaymentProcessingService processingService;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;

    public PaymentEventListener(PaymentProcessingService processingService,
                                ObjectMapper objectMapper,
                                MeterRegistry meterRegistry) {
        this.processingService = processingService;
        this.objectMapper = objectMapper;
        this.meterRegistry = meterRegistry;
    }

    @RetryableTopic(
            attempts = "4",
            backoff = @Backoff(delay = 1000, multiplier = 2.0),
            autoCreateTopics = "true",
            dltStrategy = DltStrategy.FAIL_ON_ERROR,
            topicSuffixingStrategy = TopicSuffixingStrategy.SUFFIX_WITH_INDEX_VALUE)
    @KafkaListener(topics = TOPIC_PAYMENTS_REQUESTED, groupId = "payment-processor")
    public void onPaymentRequested(String message) {
        PaymentRequestedEvent event = parse(message);
        processingService.process(event);
    }

    /**
     * Poison messages land here after retries are exhausted. In production this would raise an
     * alert / persist the payload for manual replay; for the project we log the payload + reason.
     */
    @DltHandler
    public void handleDlt(ConsumerRecord<String, String> record,
                          @Header(KafkaHeaders.DLT_EXCEPTION_MESSAGE) String exceptionMessage) {
        meterRegistry.counter("transactiq.events.dlq").increment();
        log.error("DLQ: poison message on {} offset {} -> reason: {} | payload: {}",
                record.topic(), record.offset(), exceptionMessage, record.value());
    }

    private PaymentRequestedEvent parse(String message) {
        try {
            return objectMapper.readValue(message, PaymentRequestedEvent.class);
        } catch (Exception e) {
            // Unparseable payload: throw so @RetryableTopic ultimately routes it to the DLQ.
            throw new IllegalArgumentException("Unparseable PaymentRequested payload: " + message, e);
        }
    }
}
