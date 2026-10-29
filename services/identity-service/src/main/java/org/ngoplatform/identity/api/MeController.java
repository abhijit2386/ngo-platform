package org.ngoplatform.identity.api;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.security.CurrentUser;
import org.ngoplatform.identity.app.MeService;
import org.ngoplatform.identity.app.StaffService;
import org.ngoplatform.identity.domain.UserAccount;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/me")
public class MeController {
    private final AccessGuard guard;
    private final MeService me;
    private final StaffService staff;

    public MeController(AccessGuard guard, MeService me, StaffService staff) {
        this.guard = guard; this.me = me; this.staff = staff;
    }

    @GetMapping
    public Dtos.MeView get() {
        CurrentUser cu = guard.user();
        UserAccount u = me.sync(cu);
        return Dtos.MeView.of(u, cu.platformRoles(), me.memberships(cu));
    }

    @PutMapping
    public Dtos.MeView update(@Valid @RequestBody Dtos.UpdateProfile req) {
        CurrentUser cu = guard.user();
        UserAccount u = me.updateProfile(cu, req.displayName().trim(), req.preferredLanguage());
        return Dtos.MeView.of(u, cu.platformRoles(), me.memberships(cu));
    }

    @GetMapping("/invitations")
    public List<Dtos.InvitationView> invitations() {
        UserAccount u = me.sync(guard.user());
        return staff.myInvitations(u.getEmail()).stream().map(Dtos.InvitationView::of).toList();
    }

    @PostMapping("/invitations/{id}/accept")
    public Dtos.MembershipView accept(@PathVariable UUID id) {
        UserAccount u = me.sync(guard.user());
        return Dtos.MembershipView.of(staff.accept(id, u));
    }

    @PostMapping("/deletion-request")
    public Dtos.MeView requestDeletion() {
        CurrentUser cu = guard.user();
        return Dtos.MeView.of(me.requestDeletion(cu), cu.platformRoles(), me.memberships(cu));
    }

    @DeleteMapping("/deletion-request")
    public Dtos.MeView cancelDeletion() {
        CurrentUser cu = guard.user();
        return Dtos.MeView.of(me.cancelDeletion(cu), cu.platformRoles(), me.memberships(cu));
    }
}
