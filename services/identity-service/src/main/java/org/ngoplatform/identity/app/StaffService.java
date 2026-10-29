package org.ngoplatform.identity.app;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.events.EventPublisher;
import org.ngoplatform.commons.events.EventTypes;
import org.ngoplatform.commons.security.Entitlements;
import org.ngoplatform.commons.security.EntitlementResolver;
import org.ngoplatform.identity.domain.NgoMembership;
import org.ngoplatform.identity.domain.Repositories;
import org.ngoplatform.identity.domain.StaffInvitation;
import org.ngoplatform.identity.domain.Tenant;
import org.ngoplatform.identity.domain.UserAccount;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Staff & administrator lifecycle with transactional seat accounting (spec §11).
 * Every seat-changing operation first locks the tenant row, so concurrent invitations cannot exceed capacity.
 */
@Service
public class StaffService {
    public record Capacity(int staffMax, long activeStaff, long pendingStaff, long available,
                           int adminMax, long activeAdmins, long pendingAdmins, boolean pendingReservesSeat) {}

    private final Repositories.Tenants tenants;
    private final Repositories.Memberships memberships;
    private final Repositories.Invitations invitations;
    private final Repositories.Users users;
    private final EntitlementResolver entitlements;
    private final EventPublisher events;
    private final AuditLog audit;
    private final Clock clock;
    private final boolean pendingReservesSeat;
    private final int maxAdmins;
    private final Duration inviteTtl;

    public StaffService(Repositories.Tenants tenants, Repositories.Memberships memberships,
                        Repositories.Invitations invitations, Repositories.Users users,
                        EntitlementResolver entitlements, EventPublisher events, AuditLog audit, Clock clock,
                        @Value("${ngo.staff.pending-invitations-reserve-seats:true}") boolean pendingReservesSeat,
                        @Value("${ngo.staff.max-admins:2}") int maxAdmins,
                        @Value("${ngo.staff.invitation-ttl:P7D}") Duration inviteTtl) {
        this.tenants = tenants; this.memberships = memberships; this.invitations = invitations; this.users = users;
        this.entitlements = entitlements; this.events = events; this.audit = audit; this.clock = clock;
        this.pendingReservesSeat = pendingReservesSeat; this.maxAdmins = maxAdmins; this.inviteTtl = inviteTtl;
    }

    // ---------------------------------------------------------------- tenant bootstrap (from NGO registration)

    @Transactional
    public void bootstrapTenant(UUID ngoId, UUID primaryAdminUserId, String primaryAdminEmail, String secondaryAdminEmail) {
        if (tenants.existsById(ngoId)) return; // idempotent
        tenants.save(new Tenant(ngoId));
        users.findById(primaryAdminUserId).orElseGet(() ->
                users.save(new UserAccount(primaryAdminUserId, primaryAdminEmail, false, null)));
        memberships.save(new NgoMembership(ngoId, primaryAdminUserId, "PRIMARY_ADMIN"));
        audit.record(ngoId, primaryAdminUserId, "TENANT_CREATED", "TENANT", ngoId, Map.of());
        if (secondaryAdminEmail != null && !secondaryAdminEmail.isBlank()
                && !secondaryAdminEmail.equalsIgnoreCase(primaryAdminEmail)) {
            StaffInvitation inv = invitations.save(new StaffInvitation(ngoId, secondaryAdminEmail, "SECONDARY_ADMIN",
                    primaryAdminUserId, now().plus(inviteTtl)));
            publishInvited(inv);
        }
        events.publish(EventTypes.MEMBERSHIP_CHANGED, ngoId, primaryAdminUserId, Map.of("userId", primaryAdminUserId.toString()));
    }

    // ---------------------------------------------------------------- capacity

    @Transactional(readOnly = true)
    public Capacity capacity(UUID ngoId) {
        Entitlements e = entitlements.get(ngoId);
        int staffMax = e.limit(Entitlements.STAFF_MAX);
        long active = memberships.countActiveStaff(ngoId);
        long pending = invitations.countPendingStaff(ngoId, now());
        long used = active + (pendingReservesSeat ? pending : 0);
        long available = staffMax < 0 ? Long.MAX_VALUE : Math.max(0, staffMax - used);
        return new Capacity(staffMax, active, pending, available, maxAdmins,
                memberships.countActiveAdmins(ngoId), invitations.countPendingAdmins(ngoId, now()), pendingReservesSeat);
    }

    private void lock(UUID ngoId) {
        tenants.lock(ngoId).orElseThrow(() -> ApiException.notFound("Organization"));
    }

    private void ensureStaffSeat(UUID ngoId, boolean countPending) {
        Entitlements e = entitlements.get(ngoId);
        if (!e.operationsAllowed()) {
            throw ApiException.entitlement("SUBSCRIPTION_ACTIVE",
                    "Subscription is " + e.subscriptionStatus() + ". Renew or start your trial to add staff.");
        }
        int max = e.limit(Entitlements.STAFF_MAX);
        if (max < 0) return;
        long used = memberships.countActiveStaff(ngoId)
                + (countPending && pendingReservesSeat ? invitations.countPendingStaff(ngoId, now()) : 0);
        if (used >= max) {
            throw ApiException.rule("STAFF_LIMIT_REACHED",
                    "Your " + e.plan() + " plan allows " + max + " staff. Upgrade your plan"
                            + ("ENTERPRISE".equals(e.plan()) ? " or add a staff block" : "") + " to add more.");
        }
    }

    private void ensureAdminSeat(UUID ngoId, boolean countPending) {
        long used = memberships.countActiveAdmins(ngoId)
                + (countPending ? invitations.countPendingAdmins(ngoId, now()) : 0);
        if (used >= maxAdmins) {
            throw ApiException.rule("ADMIN_LIMIT_REACHED", "An organization can have at most " + maxAdmins + " administrators");
        }
    }

    // ---------------------------------------------------------------- invitations

    @Transactional
    public StaffInvitation invite(UUID ngoId, UUID actor, String email, String role) {
        if (!RoleCatalog.isKnownRole(role) || "PRIMARY_ADMIN".equals(role)) {
            throw ApiException.badRequest("INVALID_ROLE", "Role " + role + " cannot be assigned by invitation");
        }
        lock(ngoId);
        String normalized = email.trim().toLowerCase();
        users.findByEmailIgnoreCase(normalized)
                .flatMap(u -> memberships.findByNgoIdAndUserId(ngoId, u.getId()))
                .filter(m -> m.getStatus() != NgoMembership.Status.REMOVED)
                .ifPresent(m -> { throw ApiException.conflict("ALREADY_MEMBER", "This person is already a member"); });
        boolean duplicate = invitations.findByEmailIgnoreCaseAndStatus(normalized, StaffInvitation.Status.PENDING_ACCEPTANCE)
                .stream().anyMatch(i -> i.getNgoId().equals(ngoId));
        if (duplicate) throw ApiException.conflict("INVITATION_PENDING", "An invitation is already pending for this email");
        if ("SECONDARY_ADMIN".equals(role)) ensureAdminSeat(ngoId, true); else ensureStaffSeat(ngoId, true);
        StaffInvitation inv = invitations.save(new StaffInvitation(ngoId, normalized, role, actor, now().plus(inviteTtl)));
        audit.record(ngoId, actor, "STAFF_INVITED", "INVITATION", inv.getId(), Map.of("role", role));
        publishInvited(inv);
        return inv;
    }

    private void publishInvited(StaffInvitation inv) {
        events.publish(EventTypes.STAFF_INVITED, inv.getNgoId(), inv.getId(),
                Map.of("email", inv.getEmail(), "role", inv.getRole(), "expiresAt", inv.getExpiresAt().toString()));
    }

    @Transactional
    public void cancelInvitation(UUID ngoId, UUID actor, UUID invitationId) {
        lock(ngoId);
        StaffInvitation inv = invitations.findByIdAndNgoId(invitationId, ngoId)
                .orElseThrow(() -> ApiException.notFound("Invitation"));
        if (inv.getStatus() != StaffInvitation.Status.PENDING_ACCEPTANCE) {
            throw ApiException.rule("INVITATION_NOT_PENDING", "Only pending invitations can be cancelled");
        }
        inv.cancel();
        audit.record(ngoId, actor, "INVITATION_CANCELLED", "INVITATION", invitationId, Map.of());
    }

    @Transactional
    public StaffInvitation resend(UUID ngoId, UUID actor, UUID invitationId) {
        lock(ngoId);
        StaffInvitation inv = invitations.findByIdAndNgoId(invitationId, ngoId)
                .orElseThrow(() -> ApiException.notFound("Invitation"));
        if (inv.getStatus() == StaffInvitation.Status.EXPIRED
                || (inv.getStatus() == StaffInvitation.Status.PENDING_ACCEPTANCE && !inv.isPending(now()))) {
            // An expired invitation no longer holds a seat: re-check capacity before reviving it.
            inv.expire();
            invitations.flush();
            if (inv.isAdminRole()) ensureAdminSeat(ngoId, true); else ensureStaffSeat(ngoId, true);
            StaffInvitation fresh = invitations.save(new StaffInvitation(ngoId, inv.getEmail(), inv.getRole(), actor,
                    now().plus(inviteTtl)));
            publishInvited(fresh);
            return fresh;
        }
        if (inv.getStatus() != StaffInvitation.Status.PENDING_ACCEPTANCE) {
            throw ApiException.rule("INVITATION_NOT_PENDING", "Invitation is " + inv.getStatus());
        }
        inv.extend(now().plus(inviteTtl));
        publishInvited(inv);
        audit.record(ngoId, actor, "INVITATION_RESENT", "INVITATION", invitationId, Map.of());
        return inv;
    }

    @Transactional(readOnly = true)
    public List<StaffInvitation> myInvitations(String email) {
        return invitations.findByEmailIgnoreCaseAndStatus(email, StaffInvitation.Status.PENDING_ACCEPTANCE)
                .stream().filter(i -> i.isPending(now())).toList();
    }

    @Transactional
    public NgoMembership accept(UUID invitationId, UserAccount user) {
        StaffInvitation inv = invitations.findById(invitationId).orElseThrow(() -> ApiException.notFound("Invitation"));
        if (!user.isEmailVerified()) {
            throw ApiException.rule("EMAIL_NOT_VERIFIED", "Verify your email address before accepting an invitation");
        }
        if (!inv.getEmail().equalsIgnoreCase(user.getEmail())) {
            throw ApiException.forbidden("INVITATION_EMAIL_MISMATCH", "This invitation was sent to a different email");
        }
        lock(inv.getNgoId());
        if (inv.getStatus() != StaffInvitation.Status.PENDING_ACCEPTANCE) {
            throw ApiException.rule("INVITATION_NOT_PENDING", "Invitation is " + inv.getStatus());
        }
        if (!inv.isPending(now())) {
            inv.expire();
            throw ApiException.rule("INVITATION_EXPIRED", "This invitation has expired. Ask the administrator to resend it.");
        }
        // Seat check excludes pending invites (this invite's reserved seat converts to an active seat).
        if (inv.isAdminRole()) ensureAdminSeat(inv.getNgoId(), false); else ensureStaffSeat(inv.getNgoId(), false);
        Optional<NgoMembership> existing = memberships.findByNgoIdAndUserId(inv.getNgoId(), user.getId());
        NgoMembership m;
        if (existing.isPresent()) {
            m = existing.get();
            if (m.getStatus() != NgoMembership.Status.REMOVED) {
                throw ApiException.conflict("ALREADY_MEMBER", "You are already a member of this organization");
            }
            m.changeRole(inv.getRole());
            m.changeStatus(NgoMembership.Status.ACTIVE);
        } else {
            m = memberships.save(new NgoMembership(inv.getNgoId(), user.getId(), inv.getRole()));
        }
        inv.accept(user.getId());
        audit.record(inv.getNgoId(), user.getId(), "INVITATION_ACCEPTED", "MEMBERSHIP", m.getId(), Map.of("role", inv.getRole()));
        events.publish(EventTypes.STAFF_ACCEPTED, inv.getNgoId(), m.getId(), Map.of("userId", user.getId().toString(), "role", inv.getRole()));
        events.publish(EventTypes.MEMBERSHIP_CHANGED, inv.getNgoId(), user.getId(), Map.of("userId", user.getId().toString()));
        return m;
    }

    @Scheduled(fixedDelayString = "${ngo.staff.expiry-sweep-ms:600000}")
    @Transactional
    public void expireInvitations() {
        invitations.findByStatusAndExpiresAtBefore(StaffInvitation.Status.PENDING_ACCEPTANCE, now())
                .forEach(StaffInvitation::expire);
    }

    // ---------------------------------------------------------------- members

    @Transactional
    public NgoMembership changeRole(UUID ngoId, UUID actor, UUID membershipId, String newRole) {
        if (!RoleCatalog.isKnownRole(newRole) || "PRIMARY_ADMIN".equals(newRole)) {
            throw ApiException.badRequest("INVALID_ROLE", "Use the transfer flow to change the primary administrator");
        }
        lock(ngoId);
        NgoMembership m = load(ngoId, membershipId);
        if ("PRIMARY_ADMIN".equals(m.getRole())) {
            throw ApiException.rule("PRIMARY_ADMIN_PROTECTED", "Transfer the primary administrator role first");
        }
        if (m.getRole().equals(newRole)) return m;
        boolean toAdmin = NgoMembership.ADMIN_ROLES.contains(newRole);
        if (m.isActive()) {
            if (toAdmin && !m.isAdmin()) { StepUp.require(); ensureAdminSeat(ngoId, true); }
            if (!toAdmin && m.isAdmin()) { StepUp.require(); ensureStaffSeat(ngoId, true); }
        }
        String old = m.getRole();
        m.changeRole(newRole);
        audit.record(ngoId, actor, "ROLE_CHANGED", "MEMBERSHIP", membershipId, Map.of("from", old, "to", newRole));
        membershipChanged(ngoId, m);
        return m;
    }

    @Transactional
    public NgoMembership changeStatus(UUID ngoId, UUID actor, UUID membershipId, NgoMembership.Status target) {
        lock(ngoId);
        NgoMembership m = load(ngoId, membershipId);
        if (m.getStatus() == target) return m;
        if (m.getStatus() == NgoMembership.Status.REMOVED) {
            throw ApiException.rule("MEMBER_REMOVED", "Removed members must be re-invited");
        }
        if ("PRIMARY_ADMIN".equals(m.getRole()) && target != NgoMembership.Status.ACTIVE) {
            throw ApiException.rule("PRIMARY_ADMIN_PROTECTED", "Transfer the primary administrator role first");
        }
        if (m.isAdmin() && m.isActive() && target != NgoMembership.Status.ACTIVE) {
            StepUp.require();
            if (memberships.countActiveAdmins(ngoId) <= 1) {
                throw ApiException.rule("LAST_ADMIN", "The last active administrator cannot be removed");
            }
        }
        if (target == NgoMembership.Status.ACTIVE) {
            if (m.isAdmin()) ensureAdminSeat(ngoId, true); else ensureStaffSeat(ngoId, true);
        }
        NgoMembership.Status old = m.getStatus();
        m.changeStatus(target);
        audit.record(ngoId, actor, "MEMBER_STATUS_CHANGED", "MEMBERSHIP", membershipId,
                Map.of("from", old.name(), "to", target.name()));
        membershipChanged(ngoId, m);
        return m;
    }

    @Transactional
    public NgoMembership setPermission(UUID ngoId, UUID actor, UUID membershipId, String permission, Boolean granted) {
        if (!org.ngoplatform.commons.security.Permissions.ALL.contains(permission)) {
            throw ApiException.badRequest("UNKNOWN_PERMISSION", "Unknown permission " + permission);
        }
        NgoMembership m = load(ngoId, membershipId);
        if (m.isAdmin()) throw ApiException.rule("ADMIN_PERMISSIONS_FIXED", "Administrators always have all permissions");
        m.setOverride(permission, granted);
        audit.record(ngoId, actor, "PERMISSION_CHANGED", "MEMBERSHIP", membershipId,
                Map.of("permission", permission, "granted", String.valueOf(granted)));
        membershipChanged(ngoId, m);
        return m;
    }

    /** Primary admin hands over to an active secondary admin (both remain admins; roles swap). */
    @Transactional
    public void transferPrimary(UUID ngoId, UUID actor, UUID targetMembershipId) {
        StepUp.require();
        lock(ngoId);
        NgoMembership current = memberships.findByNgoIdAndUserId(ngoId, actor)
                .filter(x -> "PRIMARY_ADMIN".equals(x.getRole()) && x.isActive())
                .orElseThrow(() -> ApiException.forbidden("PRIMARY_ADMIN_REQUIRED", "Only the primary administrator can transfer"));
        NgoMembership target = load(ngoId, targetMembershipId);
        if (!"SECONDARY_ADMIN".equals(target.getRole()) || !target.isActive()) {
            throw ApiException.rule("TARGET_NOT_SECONDARY_ADMIN", "Choose an active secondary administrator");
        }
        current.changeRole("SECONDARY_ADMIN");
        memberships.saveAndFlush(current); // release the partial unique index before promoting the target
        target.changeRole("PRIMARY_ADMIN");
        audit.record(ngoId, actor, "PRIMARY_ADMIN_TRANSFERRED", "MEMBERSHIP", targetMembershipId, Map.of());
        membershipChanged(ngoId, current);
        membershipChanged(ngoId, target);
    }

    @Transactional(readOnly = true)
    public List<NgoMembership> members(UUID ngoId) {
        return memberships.findByNgoIdAndStatusNotOrderByCreatedAt(ngoId, NgoMembership.Status.REMOVED);
    }

    @Transactional(readOnly = true)
    public List<StaffInvitation> pendingInvitations(UUID ngoId) {
        return invitations.findByNgoIdAndStatusOrderByCreatedAtDesc(ngoId, StaffInvitation.Status.PENDING_ACCEPTANCE);
    }

    private NgoMembership load(UUID ngoId, UUID membershipId) {
        return memberships.findById(membershipId).filter(m -> m.getNgoId().equals(ngoId))
                .orElseThrow(() -> ApiException.notFound("Member"));
    }

    private void membershipChanged(UUID ngoId, NgoMembership m) {
        events.publish(EventTypes.MEMBERSHIP_CHANGED, ngoId, m.getUserId(), Map.of("userId", m.getUserId().toString()));
    }

    private Instant now() { return clock.instant(); }
}
