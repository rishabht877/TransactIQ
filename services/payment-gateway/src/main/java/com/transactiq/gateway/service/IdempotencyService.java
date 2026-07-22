package com.transactiq.gateway.service;

import com.transactiq.gateway.api.PaymentResponse;
import com.transactiq.gateway.domain.PaymentStatus;
import java.time.Duration;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/**
 * Redis fast-path for Idempotency-Key dedup ("hot dedup").
 *
 * <p>This is a CACHE, not the source of truth: the durable guarantee is the UNIQUE constraint on
 * {@code payments.idempotency_key}. Redis just lets us short-circuit a repeat request without
 * touching MySQL. {@link #remember} uses SETNX (setIfAbsent) so the FIRST result for a key wins
 * and can never be overwritten by a racing request. If Redis is empty (eviction/restart) we fall
 * back to the DB lookup, so correctness never depends on the cache.
 */
@Service
public class IdempotencyService {

    private static final String KEY_PREFIX = "idem:";

    private final StringRedisTemplate redis;
    private final Duration ttl;

    public IdempotencyService(StringRedisTemplate redis,
                              @Value("${transactiq.idempotency.ttl:PT24H}") Duration ttl) {
        this.redis = redis;
        this.ttl = ttl;
    }

    /** Fast-path lookup. Returns the original result if this key was seen recently. */
    public Optional<PaymentResponse> lookup(String idempotencyKey) {
        String value = redis.opsForValue().get(KEY_PREFIX + idempotencyKey);
        return Optional.ofNullable(value).map(IdempotencyService::decode);
    }

    /** Cache the result under the key (SETNX + TTL) — first writer wins. */
    public void remember(String idempotencyKey, PaymentResponse response) {
        redis.opsForValue().setIfAbsent(KEY_PREFIX + idempotencyKey, encode(response), ttl);
    }

    private static String encode(PaymentResponse r) {
        return r.paymentId() + "|" + r.status().name();
    }

    private static PaymentResponse decode(String value) {
        int sep = value.indexOf('|');
        String paymentId = value.substring(0, sep);
        PaymentStatus status = PaymentStatus.valueOf(value.substring(sep + 1));
        return new PaymentResponse(paymentId, status);
    }
}
