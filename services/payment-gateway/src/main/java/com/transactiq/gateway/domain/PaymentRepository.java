package com.transactiq.gateway.domain;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, String> {

    /** Durable idempotency lookup — the DB UNIQUE constraint is the source of truth. */
    Optional<Payment> findByIdempotencyKey(String idempotencyKey);
}
