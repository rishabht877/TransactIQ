package com.transactiq.gateway.api;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;

/** Inbound payment request body. Validated at the controller edge. */
public record PaymentRequest(
        @NotNull @DecimalMin(value = "0.01", message = "amount must be positive") BigDecimal amount,
        @NotBlank @Pattern(regexp = "[A-Z]{3}", message = "currency must be a 3-letter ISO code") String currency,
        @NotBlank @Size(max = 64) String customerId,
        @Pattern(regexp = "\\d{4}", message = "cardLast4 must be 4 digits") String cardLast4,
        @Pattern(regexp = "[A-Z]{2}", message = "country must be a 2-letter ISO code") String country,
        @Size(max = 128) String merchant) {
}
