package com.transactiq.processor.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transactiq.processor.event.PaymentRequestedEvent;
import com.transactiq.processor.service.PaymentProcessingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * Consumes PaymentRequested from {@code payments.requested}.
 *
 * <p>Phase 1: a plain listener. Phase 2 adds non-blocking retries via {@code @RetryableTopic}
 * and a DLQ for poison messages.
 */
@Component
public class PaymentEventListener {

    public static final String TOPIC_PAYMENTS_REQUESTED = "payments.requested";

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final PaymentProcessingService processingService;
    private final ObjectMapper objectMapper;

    public PaymentEventListener(PaymentProcessingService processingService, ObjectMapper objectMapper) {
        this.processingService = processingService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = TOPIC_PAYMENTS_REQUESTED, groupId = "payment-processor")
    public void onPaymentRequested(String message) {
        try {
            PaymentRequestedEvent event = objectMapper.readValue(message, PaymentRequestedEvent.class);
            processingService.process(event);
        } catch (Exception e) {
            // Phase 1: rethrow so the container's default error handling retries. Phase 2 replaces
            // this with @RetryableTopic (non-blocking retries) + a DLQ.
            log.error("Failed to process payments.requested message: {}", message, e);
            throw new RuntimeException(e);
        }
    }
}
