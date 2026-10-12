package com.transactiq.processor.fraud;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpServer;
import com.transactiq.processor.event.PaymentRequestedEvent;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * The call to fraud-service must not be able to hang a consumer thread. Uses a real local HTTP
 * server (no Docker, no Kafka): one that never answers, and one that answers normally.
 */
class RemoteFraudDeciderTimeoutTest {

    private static final PaymentRequestedEvent EVENT = new PaymentRequestedEvent(
            "evt-1", "pay-1", "key-1", new BigDecimal("42.00"), "USD", "cust-1", "4242", "US", "Acme");

    private HttpServer server;
    private final CountDownLatch release = new CountDownLatch(1);

    @AfterEach
    void stop() {
        release.countDown();
        if (server != null) {
            server.stop(0);
        }
    }

    private String start(boolean hang) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/fraud/evaluate", exchange -> {
            exchange.getRequestBody().readAllBytes();
            if (hang) {
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                exchange.close();
                return;
            }
            byte[] body = "{\"decision\":\"BLOCK\",\"riskScore\":0.9,\"reasons\":[\"amount exceeds hard limit\"]}"
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Test
    void aFraudServiceThatNeverAnswersIsCutOffAtTheReadTimeout() throws IOException {
        RemoteFraudDecider decider =
                new RemoteFraudDecider(RestClient.builder(), start(true), 1, 1);

        long started = System.nanoTime();
        // Spring surfaces a read timeout as the generic RestClientException here, so check the real
        // cause: it has to be the socket timeout, not some other failure.
        assertThatThrownBy(() -> decider.decide(EVENT))
                .isInstanceOf(RestClientException.class)
                .hasRootCauseInstanceOf(SocketTimeoutException.class);
        long elapsedSeconds = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - started);

        // 1s timeout; the 30s the server was prepared to hang for must not be reached.
        assertThat(elapsedSeconds).isLessThan(10);
    }

    @Test
    void aNormalAnswerStillComesThroughWithTimeoutsConfigured() throws IOException {
        RemoteFraudDecider decider =
                new RemoteFraudDecider(RestClient.builder(), start(false), 1, 5);

        FraudDecision decision = decider.decide(EVENT);

        assertThat(decision.decision()).isEqualTo(Decision.BLOCK);
        assertThat(decision.riskScore()).isEqualTo(0.9);
        assertThat(decision.reasons()).containsExactly("amount exceeds hard limit");
    }
}
