package com.transactiq.processor.fraud;

import com.transactiq.processor.event.PaymentRequestedEvent;
import java.math.BigDecimal;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
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

    public RemoteFraudDecider(RestClient.Builder builder,
                              @Value("${transactiq.fraud.service.url:http://localhost:8082}") String baseUrl) {
        this.restClient = builder.baseUrl(baseUrl).build();
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
