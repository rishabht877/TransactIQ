package com.transactiq.gateway.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.transactiq.gateway.api.PaymentRequest;
import com.transactiq.gateway.api.PaymentResponse;
import com.transactiq.gateway.domain.Payment;
import com.transactiq.gateway.domain.PaymentRepository;
import com.transactiq.gateway.domain.PaymentStatus;
import com.transactiq.gateway.event.PaymentRequestedEvent;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

/**
 * Phase 1 payment creation: persist the payment, then publish PaymentRequested.
 *
 * <p><b>KNOWN DUAL-WRITE PROBLEM (fixed in Phase 2):</b> this method writes to the DB and then
 * publishes to Kafka as two independent operations. If the process dies after the DB commit
 * but before the publish (or the publish succeeds but the tx rolls back), the two stores
 * diverge — a payment with no event, or an event with no payment. There is no atomic
 * "commit DB + send to Kafka". Phase 2 replaces this with the <i>transactional outbox</i>:
 * the payment row and an outbox row are written in ONE transaction, and a separate relay
 * publishes the outbox row to Kafka.
 */
@Service
public class PaymentService {

    public static final String TOPIC_PAYMENTS_REQUESTED = "payments.requested";

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public PaymentService(PaymentRepository paymentRepository,
                          KafkaTemplate<String, String> kafkaTemplate,
                          ObjectMapper objectMapper) {
        this.paymentRepository = paymentRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public PaymentResponse createPayment(String idempotencyKey, PaymentRequest request) {
        String paymentId = UUID.randomUUID().toString();
        Payment payment = new Payment(
                paymentId,
                idempotencyKey,
                request.amount(),
                request.currency(),
                request.customerId(),
                request.cardLast4(),
                request.country(),
                request.merchant(),
                PaymentStatus.RECEIVED);
        // save() commits in its own transaction, so we publish AFTER the row is durable — the
        // processor can never look up a payment that has not been committed yet. This is still
        // a dual-write (the publish below can fail after the commit); Phase 2's outbox fixes it.
        paymentRepository.save(payment);

        // Phase 1 naive publish (dual-write — see class Javadoc). Replaced by the outbox in Phase 2.
        publishPaymentRequested(payment);

        log.info("Created payment {} (status={})", paymentId, payment.getStatus());
        return new PaymentResponse(paymentId, payment.getStatus());
    }

    private void publishPaymentRequested(Payment payment) {
        PaymentRequestedEvent event = new PaymentRequestedEvent(
                UUID.randomUUID().toString(),
                payment.getId(),
                payment.getIdempotencyKey(),
                payment.getAmount(),
                payment.getCurrency(),
                payment.getCustomerId(),
                payment.getCardLast4(),
                payment.getCountry(),
                payment.getMerchant());
        try {
            String json = objectMapper.writeValueAsString(event);
            // Key by paymentId so all events for one payment share a partition (per-key ordering).
            kafkaTemplate.send(TOPIC_PAYMENTS_REQUESTED, payment.getId(), json);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize PaymentRequestedEvent", e);
        }
    }
}
