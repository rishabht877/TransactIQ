package com.transactiq.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.transactiq.processor.event.PaymentRequestedEvent;
import com.transactiq.processor.fraud.Decision;
import com.transactiq.processor.fraud.FraudDecider;
import com.transactiq.processor.fraud.FraudDecision;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

/**
 * Verifies fraud routing: when the decider returns BLOCK, the payment ends BLOCKED (never
 * PROCESSED) and the outcome event goes to {@code payments.blocked}. Uses a mocked decider so
 * fraud-service/Ollama are not required; requires compose MySQL + EmbeddedKafka.
 */
@SpringBootTest(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(partitions = 1, topics = {"payments.requested"})
class BlockRoutingIT {

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    @MockBean
    FraudDecider fraudDecider;

    private final String paymentId = UUID.randomUUID().toString();
    private final String eventId = UUID.randomUUID().toString();

    @BeforeEach
    void stubBlock() {
        when(fraudDecider.decide(any())).thenReturn(
                new FraudDecision(Decision.BLOCK, 0.97, List.of("velocity: card-testing pattern")));
    }

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM outbox WHERE aggregate_id = ?", paymentId);
        jdbc.update("DELETE FROM processed_events WHERE event_id = ?", eventId);
        jdbc.update("DELETE FROM payments WHERE id = ?", paymentId);
    }

    @Test
    void blockedPaymentNeverReachesProcessed() throws Exception {
        jdbc.update("INSERT INTO payments (id, amount, currency, customer_id, status) VALUES (?,?,?,?,?)",
                paymentId, new BigDecimal("10.00"), "USD", "cust-block", "RECEIVED");

        String payload = objectMapper.writeValueAsString(new PaymentRequestedEvent(
                eventId, paymentId, "idem-block", new BigDecimal("10.00"), "USD",
                "cust-block", "4242", "US", "Acme"));
        kafkaTemplate.send("payments.requested", paymentId, payload).get();

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            String status = jdbc.queryForObject(
                    "SELECT status FROM payments WHERE id = ?", String.class, paymentId);
            assertThat(status).isEqualTo("BLOCKED");
        });

        // Terminal state is BLOCKED and the outcome routed to payments.blocked — never PROCESSED.
        String status = jdbc.queryForObject(
                "SELECT status FROM payments WHERE id = ?", String.class, paymentId);
        Integer blockedEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND topic = 'payments.blocked'",
                Integer.class, paymentId);
        Integer processedEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND topic = 'payments.processed'",
                Integer.class, paymentId);

        assertThat(status).isEqualTo("BLOCKED");
        assertThat(blockedEvents).as("payments.blocked outbox rows").isEqualTo(1);
        assertThat(processedEvents).as("payments.processed outbox rows").isZero();
    }
}
