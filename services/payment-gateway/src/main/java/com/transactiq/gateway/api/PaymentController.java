package com.transactiq.gateway.api;

import com.transactiq.gateway.domain.Payment;
import com.transactiq.gateway.domain.PaymentRepository;
import com.transactiq.gateway.service.PaymentService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    private final PaymentService paymentService;
    private final PaymentRepository paymentRepository;

    public PaymentController(PaymentService paymentService, PaymentRepository paymentRepository) {
        this.paymentService = paymentService;
        this.paymentRepository = paymentRepository;
    }

    /**
     * Accept a payment. Returns 202 Accepted with the payment id — processing is asynchronous
     * (the processor consumes PaymentRequested and moves the payment to a terminal state).
     *
     * <p>The {@code Idempotency-Key} header is REQUIRED (a missing header is a 400). Re-sending
     * the same key returns the original payment id and never creates a second payment.
     */
    @PostMapping
    public ResponseEntity<PaymentResponse> create(
            @RequestHeader(value = "Idempotency-Key") String idempotencyKey,
            @Valid @RequestBody PaymentRequest request) {
        PaymentResponse response = paymentService.createPayment(idempotencyKey, request);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(response);
    }

    @GetMapping("/{id}")
    public ResponseEntity<Payment> get(@PathVariable String id) {
        return paymentRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Simple list for local inspection and the Phase 5 dashboard. */
    @GetMapping
    public List<Payment> list() {
        return paymentRepository.findAll();
    }
}
