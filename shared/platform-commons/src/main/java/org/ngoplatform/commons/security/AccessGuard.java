package org.ngoplatform.commons.security;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.ngoplatform.commons.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;

/**
 * Central authorization entry point for controllers/services. Enforces, in this order:
 * authentication -> tenant membership (object-level) -> permission -> entitlement.
 */
@Component
public class AccessGuard {
    private final MembershipResolver memberships;
    private final EntitlementResolver entitlements;

    public AccessGuard(MembershipResolver memberships, EntitlementResolver entitlements) {
        this.memberships = memberships;
        this.entitlements = entitlements;
    }

    public CurrentUser user() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in required");
        }
        return fromJwt(token.getToken());
    }

    public static CurrentUser fromJwt(Jwt jwt) {
        Set<String> roles = new HashSet<>();
        Object realm = jwt.getClaims().get("realm_access");
        if (realm instanceof Map<?, ?> m && m.get("roles") instanceof List<?> l) {
            l.forEach(r -> roles.add(String.valueOf(r)));
        }
        String name = jwt.getClaimAsString("name");
        if (name == null) name = jwt.getClaimAsString("preferred_username");
        return new CurrentUser(UUID.fromString(jwt.getSubject()), jwt.getClaimAsString("email"), name, Set.copyOf(roles));
    }

    /** Active member of the NGO. Platform staff are NOT implicitly members (no browsing of tenant data). */
    public Membership member(UUID ngoId) {
        CurrentUser u = user();
        return memberships.find(u.userId(), ngoId)
                .filter(Membership::isActive)
                .orElseThrow(() -> ApiException.forbidden("NOT_A_MEMBER", "You are not a member of this organization"));
    }

    public Membership require(UUID ngoId, String permission) {
        Membership m = member(ngoId);
        if (!m.can(permission)) {
            throw ApiException.forbidden("PERMISSION_DENIED", "Missing permission " + permission);
        }
        return m;
    }

    public Membership requireAdmin(UUID ngoId) {
        Membership m = member(ngoId);
        if (!m.isAdmin()) throw ApiException.forbidden("ADMIN_REQUIRED", "Administrator access required");
        return m;
    }

    public CurrentUser requirePlatformAdmin() {
        CurrentUser u = user();
        if (!u.isPlatformAdmin()) throw ApiException.forbidden("PLATFORM_ADMIN_REQUIRED", "Platform administrator only");
        return u;
    }

    public CurrentUser requirePlatformSupport() {
        CurrentUser u = user();
        if (!u.isPlatformSupport()) throw ApiException.forbidden("PLATFORM_SUPPORT_REQUIRED", "Platform support only");
        return u;
    }

    public Entitlements entitlements(UUID ngoId) { return entitlements.get(ngoId); }

    /** Blocks new paid operations when the subscription is suspended/expired (history stays readable). */
    public Entitlements requireOperational(UUID ngoId) {
        Entitlements e = entitlements.get(ngoId);
        if (!e.operationsAllowed()) {
            throw ApiException.entitlement("SUBSCRIPTION_ACTIVE",
                    "Your subscription is " + e.subscriptionStatus() + ". Renew to continue this action.");
        }
        return e;
    }

    public Entitlements requireFlag(UUID ngoId, String key, String message) {
        Entitlements e = requireOperational(ngoId);
        if (!e.flag(key)) throw ApiException.entitlement(key, message);
        return e;
    }
}
