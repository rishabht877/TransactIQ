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
 * Core Phase 2 guarantee: replaying the SAME event three times produces exactly ONE business
 * effect.
 *
 * <p><b>Test infra:</b> a real broker (EmbeddedKafka, in-JVM) + the real MySQL from
 * docker-compose (localhost:3307, the module's default datasource). So this exercises the
 * genuine Kafka consume path and the genuine {@code processed_events} claim end-to-end — not
 * mocks. It therefore REQUIRES {@code docker compose up} (MySQL healthy) before running.
 *
 * <p>(Testcontainers would give hermetic infra, but this machine's Docker Desktop 29 drops
 * sustained JDBC connections to Testcontainers' ephemeral ports; the fixed compose port is
 * stable. The tradeoff — tests share the dev DB — is contained by using a unique id per run and
 * cleaning up afterwards.)
 */
@SpringBootTest(properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(partitions = 1, topics = {"payments.requested"})
class ReplayIdempotencyIT {

    @Autowired
    KafkaTemplate<String, String> kafkaTemplate;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    ObjectMapper objectMapper;

    // Fraud-service is not running in tests — stub the decider so processing is deterministic.
    @MockBean
    FraudDecider fraudDecider;

    // Both are 36-char UUIDs — event_id is VARCHAR(36) (real event ids are UUIDs), so a longer
    // synthetic string would be silently truncated by INSERT IGNORE and break the assertion.
    private final String paymentId = UUID.randomUUID().toString();
    private final String eventId = UUID.randomUUID().toString();

    @BeforeEach
    void stubFraud() {
        when(fraudDecider.decide(any())).thenReturn(
                new FraudDecision(Decision.APPROVE, 0.0, List.of("stubbed approve")));
    }

    @AfterEach
    void cleanup() {
        if (Boolean.getBoolean("skipCleanup")) {
            return;
        }
        jdbc.update("DELETE FROM outbox WHERE aggregate_id = ?", paymentId);
        jdbc.update("DELETE FROM processed_events WHERE event_id = ?", eventId);
        jdbc.update("DELETE FROM payments WHERE id = ?", paymentId);
    }

    @Test
    void sameEventDeliveredThriceProducesExactlyOneEffect() throws Exception {
        // Seed the RECEIVED payment row (normally written by the gateway).
        jdbc.update(
                "INSERT INTO payments (id, amount, currency, customer_id, status) VALUES (?,?,?,?,?)",
                paymentId, new BigDecimal("10.00"), "USD", "cust-replay", "RECEIVED");

        String payload = objectMapper.writeValueAsString(new PaymentRequestedEvent(
                eventId, paymentId, "idem-replay", new BigDecimal("10.00"), "USD",
                "cust-replay", "4242", "US", "Acme"));

        // Replay the identical event three times (same key -> same partition -> ordered).
        for (int i = 0; i < 3; i++) {
            kafkaTemplate.send("payments.requested", paymentId, payload).get();
        }

        // Wait until the payment reaches its terminal state.
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() -> {
            String status = jdbc.queryForObject(
                    "SELECT status FROM payments WHERE id = ?", String.class, paymentId);
            assertThat(status).isEqualTo("PROCESSED");
        });

        // Let any duplicate deliveries settle, then prove exactly-one across every effect.
        Thread.sleep(2000);

        Integer processedEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId);
        Integer outcomeEvents = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND topic = 'payments.processed'",
                Integer.class, paymentId);

        assertThat(processedEvents).as("processed_events rows for the event").isEqualTo(1);
        assertThat(outcomeEvents).as("PaymentProcessed outbox rows").isEqualTo(1);
    }
}
