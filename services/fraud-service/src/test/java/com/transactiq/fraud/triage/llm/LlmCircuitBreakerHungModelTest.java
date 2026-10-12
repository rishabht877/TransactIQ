package com.transactiq.fraud.triage.llm;

import static org.assertj.core.api.Assertions.assertThat;

import com.sun.net.httpserver.HttpServer;
import com.transactiq.fraud.api.FraudEvaluationRequest;
import com.transactiq.fraud.config.LlmConfig;
import com.transactiq.fraud.domain.Decision;
import com.transactiq.fraud.domain.FraudDecision;
import com.transactiq.fraud.rules.RuleFindings;
import com.transactiq.fraud.triage.FraudReasoner;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * End to end against a model that never answers: the real Ollama client and the real LlmConfig
 * wiring, pointed at a local server that hangs. The timeout is 1s here instead of 60s so the test
 * is quick, but the shape is the same: the calls that trip the breaker each wait out the timeout,
 * and every call after that returns at once without touching the model.
 */
class LlmCircuitBreakerHungModelTest {

    private static final int TIMEOUT_SECONDS = 1;
    private static final int THRESHOLD = 3;

    private static final FraudEvaluationRequest REQUEST = new FraudEvaluationRequest(
            "p-1", new BigDecimal("30000"), "USD", "cust-1", "4242", "US", "Acme");
    private static final RuleFindings RULE_BLOCK =
            new RuleFindings(Decision.BLOCK, 0.9, List.of("amount exceeds hard limit"));

    private HttpServer server;
    private ExecutorService serverThreads;
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger requestsTheModelReceived = new AtomicInteger();

    @AfterEach
    void stop() {
        release.countDown();
        if (server != null) {
            server.stop(0);
        }
        if (serverThreads != null) {
            serverThreads.shutdownNow();
        }
    }

    private String startServerThatNeverAnswers() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        // One thread per request, otherwise a single hung request blocks the server from even
        // seeing the next one and the request count below would be wrong.
        serverThreads = Executors.newCachedThreadPool();
        server.setExecutor(serverThreads);
        server.createContext("/api/chat", exchange -> {
            requestsTheModelReceived.incrementAndGet();
            exchange.getRequestBody().readAllBytes();
            try {
                release.await(60, TimeUnit.SECONDS);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            exchange.close();
        });
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static boolean has(FraudDecision d, String text) {
        return d.reasons().stream().anyMatch(r -> r.contains(text));
    }

    @Test
    void afterNTimeoutsTheRulesAnswerInstantlyAndTheModelIsLeftAlone() throws IOException {
        String baseUrl = startServerThatNeverAnswers();
        LlmConfig config = new LlmConfig();
        FraudAssistant assistant = config.fraudAssistant(
                config.chatLanguageModel("ollama", baseUrl, "llama3.2", 0.0, TIMEOUT_SECONDS, 1));
        LlmCircuitBreaker breaker = config.llmCircuitBreaker(
                THRESHOLD, 30, new MutableClock(), new SimpleMeterRegistry());
        FraudReasoner reasoner = config.llmFraudReasoner(assistant, breaker);

        // The calls that trip the breaker each wait out the model timeout.
        for (int i = 1; i <= THRESHOLD; i++) {
            long started = System.nanoTime();
            FraudDecision d = reasoner.reason(REQUEST, RULE_BLOCK);
            long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

            assertThat(millis).as("call %d really did wait on the hung model", i).isGreaterThan(700);
            assertThat(d.decision()).isEqualTo(Decision.BLOCK);
            assertThat(has(d, "LLM triage not applied")).isTrue();
            assertThat(has(d, "circuit breaker open")).isFalse();
        }
        assertThat(requestsTheModelReceived)
                .as("one attempt per call, not the client default of three")
                .hasValue(THRESHOLD);

        // Now it is open: the rules answer immediately and the model is not called.
        long started = System.nanoTime();
        FraudDecision skipped = reasoner.reason(REQUEST, RULE_BLOCK);
        long millis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertThat(millis).as("no waiting on the model").isLessThan(300);
        assertThat(skipped.decision()).isEqualTo(Decision.BLOCK);
        assertThat(skipped.riskScore()).isEqualTo(0.9);
        assertThat(has(skipped, "LLM triage not applied")).isTrue();
        assertThat(has(skipped, "circuit breaker open")).isTrue();
        assertThat(requestsTheModelReceived).as("the model never saw the 4th call").hasValue(THRESHOLD);
    }
}
