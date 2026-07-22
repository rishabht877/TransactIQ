package com.transactiq.processor.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ProcessedEventRepository extends JpaRepository<ProcessedEvent, String> {

    /**
     * Atomically claim an event_id. Returns 1 if this call inserted the row (first time we've
     * seen the event) or 0 if it was already present (a re-delivery). {@code INSERT IGNORE}
     * avoids throwing a constraint violation — which would poison the surrounding transaction —
     * so the caller can branch on the return value instead of catching an exception.
     *
     * <p>Runs in the caller's transaction, so if later work in that transaction fails and rolls
     * back, this claim is rolled back too and the event can be reprocessed on retry.
     */
    @Modifying
    @Query(value = "INSERT IGNORE INTO processed_events (event_id) VALUES (:eventId)", nativeQuery = true)
    int claim(@Param("eventId") String eventId);
}
