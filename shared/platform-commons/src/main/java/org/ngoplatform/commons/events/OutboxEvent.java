package org.ngoplatform.commons.events;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "outbox_events")
public class OutboxEvent {
    @Id private UUID id;
    @Column(name = "event_type", nullable = false) private String eventType;
    @Column(name = "event_version", nullable = false) private int eventVersion;
    @Column(name = "tenant_id") private UUID tenantId;
    @Column(name = "resource_id") private String resourceId;
    @Column(name = "correlation_id") private String correlationId;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "jsonb") private String payload;
    @Column(name = "created_at", nullable = false) private Instant createdAt;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "attempts", nullable = false) private int attempts;
    @Column(name = "last_error") private String lastError;

    protected OutboxEvent() {}

    public OutboxEvent(UUID id, String eventType, int eventVersion, UUID tenantId, String resourceId,
                       String correlationId, String payload) {
        this.id = id; this.eventType = eventType; this.eventVersion = eventVersion; this.tenantId = tenantId;
        this.resourceId = resourceId; this.correlationId = correlationId; this.payload = payload;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public String getEventType() { return eventType; }
    public int getEventVersion() { return eventVersion; }
    public UUID getTenantId() { return tenantId; }
    public String getResourceId() { return resourceId; }
    public String getCorrelationId() { return correlationId; }
    public String getPayload() { return payload; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getPublishedAt() { return publishedAt; }
    public int getAttempts() { return attempts; }
    public void markPublished() { this.publishedAt = Instant.now(); }
    public void markFailed(String error) {
        this.attempts++;
        this.lastError = error == null ? null : error.substring(0, Math.min(error.length(), 500));
    }
}
