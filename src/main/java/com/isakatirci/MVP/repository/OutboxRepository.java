package com.isakatirci.MVP.repository;

import com.isakatirci.MVP.entity.Outbox;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxRepository extends JpaRepository<Outbox, Long> {

    List<Outbox> findByStatusOrderByCreatedAtAsc(Outbox.OutboxStatus status);

    List<Outbox> findByStatusAndRetryCountLessThanOrderByCreatedAtAsc(
            Outbox.OutboxStatus status, Integer maxRetries);

    /**
     * Claims pending outbox events using FOR UPDATE SKIP LOCKED to prevent
     * multiple relay instances from processing the same event.
     */
    @Query(value = """
            SELECT * FROM outbox
            WHERE status = 'PENDING'
              AND retry_count < ?1
              AND (next_attempt_at IS NULL OR next_attempt_at <= NOW())
            ORDER BY created_at ASC
            LIMIT ?2
            FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<Outbox> claimPendingEvents(int maxRetries, int batchSize);
}
