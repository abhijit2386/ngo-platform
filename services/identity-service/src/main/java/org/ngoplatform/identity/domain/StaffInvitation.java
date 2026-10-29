package org.ngoplatform.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "staff_invitations")
public class StaffInvitation {
    public enum Status { PENDING_ACCEPTANCE, ACCEPTED, CANCELLED, EXPIRED }

    @Id private UUID id;
    @Column(name = "ngo_id", nullable = false) private UUID ngoId;
    @Column(nullable = false) private String email;
    @Column(name = "role_code", nullable = false) private String role;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status;
    @Column(name = "invited_by") private UUID invitedBy;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;
    @Column(name = "accepted_by") private UUID acceptedBy;
    @Column(name = "accepted_at") private Instant acceptedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Version private long version;

    protected StaffInvitation() {}

    public StaffInvitation(UUID ngoId, String email, String role, UUID invitedBy, Instant expiresAt) {
        this.id = UUID.randomUUID(); this.ngoId = ngoId; this.email = email.trim().toLowerCase();
        this.role = role; this.invitedBy = invitedBy; this.expiresAt = expiresAt;
        this.status = Status.PENDING_ACCEPTANCE;
    }

    public boolean isPending(Instant now) { return status == Status.PENDING_ACCEPTANCE && expiresAt.isAfter(now); }
    public boolean isAdminRole() { return NgoMembership.ADMIN_ROLES.contains(role); }

    public void accept(UUID userId) { status = Status.ACCEPTED; acceptedBy = userId; acceptedAt = Instant.now(); }
    public void cancel() { status = Status.CANCELLED; }
    public void expire() { status = Status.EXPIRED; }
    public void extend(Instant until) { expiresAt = until; }

    public UUID getId() { return id; }
    public UUID getNgoId() { return ngoId; }
    public String getEmail() { return email; }
    public String getRole() { return role; }
    public Status getStatus() { return status; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
}
