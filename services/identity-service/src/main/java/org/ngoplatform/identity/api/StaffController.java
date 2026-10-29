package org.ngoplatform.identity.api;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.ngoplatform.commons.audit.AuditRepository;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.security.Membership;
import org.ngoplatform.commons.security.Permissions;
import org.ngoplatform.commons.web.PageResponse;
import org.ngoplatform.identity.app.RoleCatalog;
import org.ngoplatform.identity.app.StaffService;
import org.ngoplatform.identity.domain.NgoMembership;
import org.ngoplatform.identity.domain.Repositories;
import org.ngoplatform.identity.domain.UserAccount;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ngos/{ngoId}/staff")
public class StaffController {
    private final AccessGuard guard;
    private final StaffService staff;
    private final Repositories.Users users;
    private final AuditRepository auditRepo;

    private final org.ngoplatform.commons.audit.AuditLog audit;

    public StaffController(AccessGuard guard, StaffService staff, Repositories.Users users, AuditRepository auditRepo,
                           org.ngoplatform.commons.audit.AuditLog audit) {
        this.guard = guard; this.staff = staff; this.users = users; this.auditRepo = auditRepo; this.audit = audit;
    }

    @GetMapping
    public List<Dtos.MemberView> members(@PathVariable UUID ngoId) {
        guard.require(ngoId, Permissions.STAFF_VIEW);
        List<NgoMembership> ms = staff.members(ngoId);
        Map<UUID, UserAccount> byId = users.findAllById(ms.stream().map(NgoMembership::getUserId).toList())
                .stream().collect(Collectors.toMap(UserAccount::getId, Function.identity()));
        List<Dtos.MemberView> list = ms.stream().map(m -> {
            UserAccount u = byId.get(m.getUserId());
            return new Dtos.MemberView(m.getId(), m.getUserId(), u == null ? null : u.getDisplayName(),
                    u == null ? null : u.getEmail(), m.getRole(), m.getStatus().name(), RoleCatalog.effective(m),
                    m.getCreatedAt(), u == null ? null : Dtos.Avatar.of(u),
                    // Contact numbers are personal: visible only inside the same NGO team (STAFF_VIEW checked above).
                    u == null || !m.isActive() ? null : u.getPhone());
        }).toList();
        // Every view of team contact details is recorded (who, which NGO, when) - no numbers in the log.
        audit.record(ngoId, guard.user().userId(), "TEAM_CONTACTS_VIEWED", "NGO", ngoId, Map.of("members", list.size()));
        return list;
    }

    @GetMapping("/capacity")
    public StaffService.Capacity capacity(@PathVariable UUID ngoId) {
        guard.require(ngoId, Permissions.STAFF_VIEW);
        return staff.capacity(ngoId);
    }

    @GetMapping("/invitations")
    public List<Dtos.InvitationView> invitations(@PathVariable UUID ngoId) {
        guard.require(ngoId, Permissions.STAFF_VIEW);
        return staff.pendingInvitations(ngoId).stream().map(Dtos.InvitationView::of).toList();
    }

    @PostMapping("/invitations")
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.InvitationView invite(@PathVariable UUID ngoId, @Valid @RequestBody Dtos.InviteRequest req) {
        Membership m = req.role().equals("SECONDARY_ADMIN") ? guard.requireAdmin(ngoId)
                : guard.require(ngoId, Permissions.STAFF_MANAGE);
        return Dtos.InvitationView.of(staff.invite(ngoId, m.userId(), req.email(), req.role()));
    }

    @PostMapping("/invitations/{invitationId}/resend")
    public Dtos.InvitationView resend(@PathVariable UUID ngoId, @PathVariable UUID invitationId) {
        Membership m = guard.require(ngoId, Permissions.STAFF_MANAGE);
        return Dtos.InvitationView.of(staff.resend(ngoId, m.userId(), invitationId));
    }

    @DeleteMapping("/invitations/{invitationId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancel(@PathVariable UUID ngoId, @PathVariable UUID invitationId) {
        Membership m = guard.require(ngoId, Permissions.STAFF_MANAGE);
        staff.cancelInvitation(ngoId, m.userId(), invitationId);
    }

    @PutMapping("/{membershipId}/role")
    public Dtos.MembershipView role(@PathVariable UUID ngoId, @PathVariable UUID membershipId,
                                    @Valid @RequestBody Dtos.ChangeRole req) {
        Membership m = guard.requireAdmin(ngoId);
        return Dtos.MembershipView.of(staff.changeRole(ngoId, m.userId(), membershipId, req.role()));
    }

    @PutMapping("/{membershipId}/status")
    public Dtos.MembershipView status(@PathVariable UUID ngoId, @PathVariable UUID membershipId,
                                      @Valid @RequestBody Dtos.ChangeStatus req) {
        Membership m = guard.require(ngoId, Permissions.STAFF_MANAGE);
        return Dtos.MembershipView.of(staff.changeStatus(ngoId, m.userId(), membershipId, req.status()));
    }

    @PutMapping("/{membershipId}/permissions")
    public Dtos.MembershipView permission(@PathVariable UUID ngoId, @PathVariable UUID membershipId,
                                          @Valid @RequestBody Dtos.PermissionChange req) {
        Membership m = guard.requireAdmin(ngoId);
        return Dtos.MembershipView.of(staff.setPermission(ngoId, m.userId(), membershipId, req.permission(), req.granted()));
    }

    @PostMapping("/transfer-primary")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void transfer(@PathVariable UUID ngoId, @Valid @RequestBody Dtos.TransferPrimary req) {
        Membership m = guard.requireAdmin(ngoId);
        staff.transferPrimary(ngoId, m.userId(), req.targetMembershipId());
    }

    @GetMapping("/activity")
    public PageResponse<Map<String, Object>> activity(@PathVariable UUID ngoId,
                                                      @RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        guard.require(ngoId, Permissions.STAFF_VIEW);
        return PageResponse.of(auditRepo.findByTenantIdOrderByCreatedAtDesc(ngoId, PageRequest.of(page, Math.min(size, 100))),
                a -> Map.of("action", a.getAction(), "actorId", String.valueOf(a.getActorId()),
                        "resourceType", a.getResourceType(), "at", a.getCreatedAt().toString()));
    }
}
