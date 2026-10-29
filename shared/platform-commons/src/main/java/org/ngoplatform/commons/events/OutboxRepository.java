package org.ngoplatform.commons.events;

import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OutboxRepository extends JpaRepository<OutboxEvent, UUID> {
    /** SKIP LOCKED lets several replicas relay concurrently without double-publishing the same row. */
    @Query(value = "select * from outbox_events where published_at is null and attempts < 50 "
            + "order by created_at limit :limit for update skip locked", nativeQuery = true)
    List<OutboxEvent> lockBatch(int limit);

    long countByPublishedAtIsNull();
}
