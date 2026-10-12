package com.transactiq.processor.fraud;

import com.transactiq.processor.event.PaymentRequestedEvent;
import java.math.BigDecimal;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Calls fraud-service over HTTP for the triage decision.
 *
 * <p>Fail-closed: if fraud-service is unreachable or errors, this throws, which propagates out of
 * the Kafka listener → {@code @RetryableTopic} retries → DLQ. We never auto-APPROVE a payment
 * just because the fraud check was unavailable.
 */
@Component
public class RemoteFraudDecider implements FraudDecider {

    private final RestClient restClient;

    /**
     * @param readTimeoutSeconds how long to wait for fraud-service to answer. Without this the call
     *     had no upper bound at all, so one hung fraud-service call held a consumer thread forever.
     *     It must stay ABOVE fraud-service's own worst case (the LLM timeout, 60s with one attempt),
     *     otherwise the processor gives up and retries while fraud-service is still working, and the
     *     same payment is evaluated twice.
     */
    public RemoteFraudDecider(RestClient.Builder builder,
                              @Value("${transactiq.fraud.service.url:http://localhost:8082}") String baseUrl,
                              @Value("${transactiq.fraud.service.connect-timeout-seconds:3}") long connectTimeoutSeconds,
                              @Value("${transactiq.fraud.service.read-timeout-seconds:90}") long readTimeoutSeconds) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(connectTimeoutSeconds));
        requestFactory.setReadTimeout(Duration.ofSeconds(readTimeoutSeconds));
        this.restClient = builder.baseUrl(baseUrl).requestFactory(requestFactory).build();
    }

    @Override
    public FraudDecision decide(PaymentRequestedEvent event) {
        FraudDecision decision = restClient.post()
                .uri("/api/fraud/evaluate")
                .contentType(MediaType.APPLICATION_JSON)
                .body(new FraudEvaluationRequest(
                        event.paymentId(),
                        event.amount(),
                        event.currency(),
                        event.customerId(),
                        event.cardLast4(),
                        event.country(),
                        event.merchant()))
                .retrieve()
                .body(FraudDecision.class);
        if (decision == null || decision.decision() == null) {
            throw new IllegalStateException(
                    "fraud-service returned no decision for payment " + event.paymentId());
        }
        return decision;
    }

    /** Request body sent to fraud-service (mirrors its FraudEvaluationRequest contract). */
    private record FraudEvaluationRequest(
            String paymentId, BigDecimal amount, String currency, String customerId,
            String cardLast4, String country, String merchant) {
    }
}
