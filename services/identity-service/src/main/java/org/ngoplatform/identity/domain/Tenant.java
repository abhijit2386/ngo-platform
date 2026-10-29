package org.ngoplatform.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
public class Tenant {
    @Id @Column(name = "ngo_id") private UUID ngoId;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    protected Tenant() {}
    public Tenant(UUID ngoId) { this.ngoId = ngoId; }
    public UUID getNgoId() { return ngoId; }
}
