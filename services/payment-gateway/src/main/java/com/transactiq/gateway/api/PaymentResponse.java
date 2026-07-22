package com.transactiq.gateway.api;

import com.transactiq.gateway.domain.PaymentStatus;

/** Response returned from POST /api/payments (and the idempotent replay of it). */
public record PaymentResponse(String paymentId, PaymentStatus status) {
}
