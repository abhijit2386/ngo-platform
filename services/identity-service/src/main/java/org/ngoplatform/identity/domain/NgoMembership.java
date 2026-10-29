package org.ngoplatform.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "ngo_memberships")
public class NgoMembership {
    public enum Status { ACTIVE, SUSPENDED, DEACTIVATED, REMOVED }

    @Id private UUID id;
    @Column(name = "ngo_id", nullable = false) private UUID ngoId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Column(name = "role_code", nullable = false) private String role;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version private long version;

    @ElementCollection(fetch = FetchType.EAGER)
    @CollectionTable(name = "membership_permission_overrides", joinColumns = @JoinColumn(name = "membership_id"))
    @MapKeyColumn(name = "permission_code")
    @Column(name = "granted")
    private Map<String, Boolean> overrides = new HashMap<>();

    protected NgoMembership() {}

    public NgoMembership(UUID ngoId, UUID userId, String role) {
        this.id = UUID.randomUUID(); this.ngoId = ngoId; this.userId = userId; this.role = role;
        this.status = Status.ACTIVE;
    }

    public static final Set<String> ADMIN_ROLES = Set.of("PRIMARY_ADMIN", "SECONDARY_ADMIN");

    public boolean isAdmin() { return ADMIN_ROLES.contains(role); }
    public boolean isActive() { return status == Status.ACTIVE; }
    /** Seats: active non-admin members consume operational staff seats (admins are counted separately). */
    public boolean consumesStaffSeat() { return status == Status.ACTIVE && !isAdmin(); }

    public void changeRole(String role) { this.role = role; touch(); }
    public void changeStatus(Status s) { this.status = s; touch(); }
    public void setOverride(String permission, Boolean granted) {
        if (granted == null) overrides.remove(permission); else overrides.put(permission, granted);
        touch();
    }
    private void touch() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getNgoId() { return ngoId; }
    public UUID getUserId() { return userId; }
    public String getRole() { return role; }
    public Status getStatus() { return status; }
    public Map<String, Boolean> getOverrides() { return overrides; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
}
