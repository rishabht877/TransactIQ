package com.transactiq.gateway.service;

import com.transactiq.gateway.api.PaymentRequest;
import com.transactiq.gateway.api.PaymentResponse;
import com.transactiq.gateway.domain.Payment;
import com.transactiq.gateway.domain.PaymentRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * Orchestrates idempotent payment creation. Three layers of dedup, cheapest first:
 *
 * <ol>
 *   <li><b>Redis fast-path</b> — repeat key returns the cached result, no DB, no publish.</li>
 *   <li><b>DB read</b> — Redis miss but the key exists in {@code payments} (cache evicted /
 *       restarted): return the existing row.</li>
 *   <li><b>DB UNIQUE constraint</b> — two requests race past 1 and 2 at once: one insert wins,
 *       the loser catches the violation and returns the winner's row.</li>
 * </ol>
 *
 * <p>The result: the same Idempotency-Key always maps to exactly one payment — never a double
 * charge — with Redis as an optimization and the DB constraint as the durable guarantee.
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final IdempotencyService idempotencyService;
    private final PaymentWriter paymentWriter;
    private final PaymentRepository paymentRepository;
    private final MeterRegistry meterRegistry;

    public PaymentService(IdempotencyService idempotencyService,
                          PaymentWriter paymentWriter,
                          PaymentRepository paymentRepository,
                          MeterRegistry meterRegistry) {
        this.idempotencyService = idempotencyService;
        this.paymentWriter = paymentWriter;
        this.paymentRepository = paymentRepository;
        this.meterRegistry = meterRegistry;
    }

    public PaymentResponse createPayment(String idempotencyKey, PaymentRequest request) {
        Timer.Sample sample = Timer.start(meterRegistry);
        String result = "created";
        try {
            // 1. Redis fast-path.
            var cached = idempotencyService.lookup(idempotencyKey);
            if (cached.isPresent()) {
                log.debug("Idempotency hit (redis) for key {}", idempotencyKey);
                result = "idempotent_redis";
                return cached.get();
            }

            // 2. DB read (durable, covers a cold/evicted cache).
            var existing = paymentRepository.findByIdempotencyKey(idempotencyKey);
            if (existing.isPresent()) {
                PaymentResponse response = toResponse(existing.get());
                idempotencyService.remember(idempotencyKey, response);
                result = "idempotent_db";
                return response;
            }

            // 3. Insert payment + outbox in one transaction; the UNIQUE constraint guards the race.
            PaymentResponse response;
            try {
                response = paymentWriter.insertNewPayment(idempotencyKey, request);
                log.info("Created payment {} for key {}", response.paymentId(), idempotencyKey);
            } catch (DataIntegrityViolationException duplicate) {
                // A concurrent request won. Re-read its row in a fresh transaction and return it.
                Payment winner = paymentRepository.findByIdempotencyKey(idempotencyKey)
                        .orElseThrow(() -> duplicate);
                log.info("Idempotency race for key {} -> returning existing payment {}",
                        idempotencyKey, winner.getId());
                result = "idempotent_race";
                response = toResponse(winner);
            }

            idempotencyService.remember(idempotencyKey, response);
            return response;
        } finally {
            sample.stop(meterRegistry.timer("transactiq.gateway.create"));
            meterRegistry.counter("transactiq.payments.accepted", "result", result).increment();
        }
    }

    private static PaymentResponse toResponse(Payment payment) {
        return new PaymentResponse(payment.getId(), payment.getStatus());
    }
}
