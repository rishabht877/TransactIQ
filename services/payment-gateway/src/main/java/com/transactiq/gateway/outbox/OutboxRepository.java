package com.transactiq.gateway.outbox;

import jakarta.persistence.LockModeType;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;

public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    /**
     * Fetch the oldest unpublished rows for the given topics, row-locking them with SKIP LOCKED
     * so multiple relay instances can poll concurrently without double-publishing the same row.
     *
     * <p>Hibernate maps {@code jakarta.persistence.lock.timeout = -2} to SKIP LOCKED. Must be
     * called inside a transaction (the relay method is {@code @Transactional}).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints(@jakarta.persistence.QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2"))
    @Query("select o from OutboxEvent o where o.publishedAt is null and o.topic in :topics order by o.id")
    List<OutboxEvent> findUnpublishedByTopics(@Param("topics") List<String> topics, Pageable pageable);
}
