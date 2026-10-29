package org.ngoplatform.identity.api;

import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.Membership;
import org.ngoplatform.identity.app.LocalMembershipResolver;
import org.ngoplatform.identity.domain.Repositories;
import org.springframework.web.bind.annotation.*;

/** Service-to-service API (X-Internal-Token). Never routed by the gateway. */
@RestController
@RequestMapping("/internal/v1")
public class InternalController {
    private final LocalMembershipResolver resolver;
    private final Repositories.Memberships memberships;
    private final Repositories.Users users;
    private final org.ngoplatform.identity.app.MeService me;

    public InternalController(LocalMembershipResolver resolver, Repositories.Memberships memberships,
                              Repositories.Users users, org.ngoplatform.identity.app.MeService me) {
        this.resolver = resolver; this.memberships = memberships; this.users = users; this.me = me;
    }

    @GetMapping("/memberships/{ngoId}/{userId}")
    public Membership membership(@PathVariable UUID ngoId, @PathVariable UUID userId) {
        return resolver.find(userId, ngoId).orElseThrow(() -> ApiException.notFound("Membership"));
    }

    /** Used by subscription-service to assess downgrade impact. */
    @GetMapping("/tenants/{ngoId}/usage")
    public Map<String, Long> usage(@PathVariable UUID ngoId) {
        return Map.of("activeStaff", memberships.countActiveStaff(ngoId),
                "activeAdmins", memberships.countActiveAdmins(ngoId));
    }

    /** Active administrators of an NGO with contact data, for notification-service. */
    @GetMapping("/tenants/{ngoId}/admins")
    public java.util.List<Map<String, String>> admins(@PathVariable UUID ngoId) {
        return memberships.findByNgoIdAndStatusNotOrderByCreatedAt(ngoId, org.ngoplatform.identity.domain.NgoMembership.Status.REMOVED)
                .stream()
                .filter(m -> m.getStatus() == org.ngoplatform.identity.domain.NgoMembership.Status.ACTIVE && m.isAdmin())
                .flatMap(m -> users.findById(m.getUserId()).stream())
                .map(InternalController::contactOf)
                .toList();
    }

    /** Registered user by e-mail (404 when none), for notification-service. */
    @GetMapping("/users/lookup")
    public Map<String, String> lookup(@RequestParam String email) {
        return users.findByEmailIgnoreCase(email.trim()).map(InternalController::contactOf)
                .orElseThrow(() -> ApiException.notFound("User"));
    }

    private static Map<String, String> contactOf(org.ngoplatform.identity.domain.UserAccount u) {
        return Map.of("userId", u.getId().toString(), "email", u.getEmail(),
                "name", u.getDisplayName() == null ? "" : u.getDisplayName(), "language", u.getPreferredLanguage());
    }

    /** Adults-only gate for the other services (see commons RestProfileStatusResolver). */
    @GetMapping("/users/{userId}/profile-status")
    public Map<String, Boolean> profileStatus(@PathVariable UUID userId) {
        return Map.of("complete", me.isUsable(userId));
    }

    /** Contact info for notifications (minimal fields). */
    @GetMapping("/users/{userId}/contact")
    public Map<String, String> contact(@PathVariable UUID userId) {
        return users.findById(userId)
                .map(u -> Map.of("email", u.getEmail(), "name", u.getDisplayName() == null ? "" : u.getDisplayName(),
                        "language", u.getPreferredLanguage()))
                .orElseThrow(() -> ApiException.notFound("User"));
    }
}
