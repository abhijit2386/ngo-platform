package org.ngoplatform.commons.audit;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.Immutable;
import org.hibernate.type.SqlTypes;

/** Append-only audit trail (DB trigger also blocks UPDATE/DELETE). */
@Entity
@Immutable
@Table(name = "audit_events")
public class AuditEvent {
    @Id private UUID id;
    @Column(name = "tenant_id") private UUID tenantId;
    @Column(name = "actor_id") private UUID actorId;
    @Column(nullable = false) private String action;
    @Column(name = "resource_type", nullable = false) private String resourceType;
    @Column(name = "resource_id") private String resourceId;
    @JdbcTypeCode(SqlTypes.JSON) @Column(columnDefinition = "jsonb") private String details;
    @Column(name = "correlation_id") private String correlationId;
    @Column(name = "created_at", nullable = false) private Instant createdAt;

    protected AuditEvent() {}

    public AuditEvent(UUID tenantId, UUID actorId, String action, String resourceType, String resourceId,
                      String details, String correlationId) {
        this.id = UUID.randomUUID(); this.tenantId = tenantId; this.actorId = actorId; this.action = action;
        this.resourceType = resourceType; this.resourceId = resourceId; this.details = details;
        this.correlationId = correlationId; this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public UUID getActorId() { return actorId; }
    public String getAction() { return action; }
    public String getResourceType() { return resourceType; }
    public String getResourceId() { return resourceId; }
    public String getDetails() { return details; }
    public Instant getCreatedAt() { return createdAt; }
}
