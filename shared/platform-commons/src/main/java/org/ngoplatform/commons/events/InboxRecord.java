package org.ngoplatform.commons.events;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "inbox_processed_events")
public class InboxRecord {
    @Id private UUID id;
    @Column(nullable = false) private String consumer;
    @Column(name = "event_id", nullable = false) private UUID eventId;
    @Column(name = "processed_at", nullable = false) private Instant processedAt;

    protected InboxRecord() {}
    public InboxRecord(String consumer, UUID eventId) {
        this.id = UUID.randomUUID(); this.consumer = consumer; this.eventId = eventId; this.processedAt = Instant.now();
    }
}
