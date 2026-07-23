package com.transactiq.fraud.api;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import java.math.BigDecimal;

/** Input to POST /api/fraud/evaluate. Sent by the payment-processor for each payment. */
public record FraudEvaluationRequest(
        @NotBlank String paymentId,
        @NotNull @Positive BigDecimal amount,
        @NotBlank String currency,
        @NotBlank String customerId,
        String cardLast4,
        String country,
        String merchant) {
}
