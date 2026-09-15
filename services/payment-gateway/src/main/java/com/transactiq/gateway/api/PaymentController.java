package com.transactiq.gateway.api;

import com.transactiq.gateway.domain.Payment;
import com.transactiq.gateway.domain.PaymentRepository;
import com.transactiq.gateway.service.PaymentService;
import jakarta.validation.Valid;
import java.util.List;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/payments")
public class PaymentController {

    /** Rows returned when the caller does not ask for a specific page size. */
    static final int DEFAULT_LIMIT = 100;

    /** Hard ceiling — a client cannot ask the gateway to materialise the whole table. */
    static final int MAX_LIMIT = 1000;

    /** Total row count, exposed to the browser via the CORS config (see WebConfig). */
    static final String TOTAL_COUNT_HEADER = "X-Total-Count";

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

    /**
     * List payments, newest first.
     *
     * <p>This is polled every couple of seconds by the dashboard, so it is paginated rather
     * than an unbounded {@code findAll()}: after a load-test run the table holds tens of
     * thousands of rows and the poll would drag all of them over the wire each tick.
     *
     * <p>Ordering by {@code createdAt} descending is what makes {@code limit} meaningful — it
     * returns the newest N, not an arbitrary N. The total row count comes back in the
     * {@code X-Total-Count} header so callers can paginate without a second endpoint.
     *
     * @param limit rows per page, clamped to [1, {@value #MAX_LIMIT}]
     * @param page  zero-based page index
     */
    @GetMapping
    public ResponseEntity<List<Payment>> list(
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
            @RequestParam(defaultValue = "0") int page) {
        int size = Math.clamp(limit, 1, MAX_LIMIT);
        Page<Payment> result = paymentRepository.findAll(
                PageRequest.of(Math.max(page, 0), size, Sort.by(Sort.Direction.DESC, "createdAt")));
        return ResponseEntity.ok()
                .header(TOTAL_COUNT_HEADER, String.valueOf(result.getTotalElements()))
                .body(result.getContent());
    }
}
