package com.transactiq.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import com.transactiq.gateway.api.PaymentResponse;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;

/**
 * Proves the gateway's idempotency guarantee: the same Idempotency-Key posted twice returns the
 * same payment id and creates exactly one payments row and one outbox row — never a double
 * charge. Exercises the real Redis fast-path + MySQL UNIQUE constraint from docker-compose.
 *
 * <p>Requires {@code docker compose up} (MySQL + Redis healthy). See {@code ReplayIdempotencyIT}
 * for why this uses compose infra + EmbeddedKafka instead of Testcontainers on this machine.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}")
@EmbeddedKafka(partitions = 1, topics = {"payments.requested"})
class IdempotencyIT {

    @Autowired
    TestRestTemplate rest;

    @Autowired
    JdbcTemplate jdbc;

    // Unique per run so repeated runs against the shared dev DB never collide.
    private final String key = "dup-key-" + UUID.randomUUID();

    @AfterEach
    void cleanup() {
        jdbc.update("DELETE FROM outbox WHERE aggregate_id IN (SELECT id FROM payments WHERE idempotency_key = ?)", key);
        jdbc.update("DELETE FROM payments WHERE idempotency_key = ?", key);
    }

    @Test
    void sameIdempotencyKeyReturnsSamePaymentAndCreatesOneRow() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Idempotency-Key", key);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(Map.of(
                "amount", 25.50,
                "currency", "USD",
                "customerId", "cust-idem",
                "cardLast4", "4242",
                "country", "US",
                "merchant", "Acme"), headers);

        ResponseEntity<PaymentResponse> first =
                rest.postForEntity("/api/payments", request, PaymentResponse.class);
        ResponseEntity<PaymentResponse> second =
                rest.postForEntity("/api/payments", request, PaymentResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(first.getBody()).isNotNull();
        assertThat(second.getBody()).isNotNull();
        // Same key -> same payment id (the second call is a dedup hit, not a new charge).
        assertThat(second.getBody().paymentId()).isEqualTo(first.getBody().paymentId());

        Integer paymentRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM payments WHERE idempotency_key = ?", Integer.class, key);
        Integer outboxRows = jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE aggregate_id = ? AND topic = 'payments.requested'",
                Integer.class, first.getBody().paymentId());

        assertThat(paymentRows).as("payments rows for the key").isEqualTo(1);
        assertThat(outboxRows).as("PaymentRequested outbox rows").isEqualTo(1);
    }

    @Test
    void missingIdempotencyKeyIsRejected() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(Map.of(
                "amount", 10.00, "currency", "USD", "customerId", "c"), headers);

        ResponseEntity<String> response = rest.postForEntity("/api/payments", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
