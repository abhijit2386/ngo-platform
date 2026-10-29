package org.ngoplatform.commons.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.List;
import org.ngoplatform.commons.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * Owner rule (2026-10-29): adults only. Until a user's profile is complete (date of birth, 18+), every API answers
 * 403 PROFILE_INCOMPLETE except the few calls needed to complete or delete the profile. Enforced on the server so
 * no client (or sign-in method) can skip it. Platform staff accounts are vetted separately and are exempt.
 */
public class ProfileGate implements HandlerInterceptor {
    /** Paths a signed-in user may call before completing the profile (exact match or prefix ending in '/'). */
    static final List<String> OPEN = List.of("/api/v1/me", "/api/v1/me/profile", "/api/v1/me/photo",
            "/api/v1/me/avatar", "/api/v1/me/deletion-request", "/api/v1/me/phone", "/api/v1/me/phone/",
            "/api/v1/public/");

    private final ProfileStatusResolver resolver;

    public ProfileGate(ProfileStatusResolver resolver) { this.resolver = resolver; }

    static boolean isOpen(String path) {
        if (!path.startsWith("/api/")) return true; // actuator, internal, webhooks are handled by their own rules
        for (String p : OPEN) {
            if (p.endsWith("/") ? path.startsWith(p) : path.equals(p)) return true;
        }
        return false;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) {
        if (isOpen(req.getRequestURI())) return true;
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (!(auth instanceof JwtAuthenticationToken token)) return true; // anonymous/internal: other rules apply
        CurrentUser u = AccessGuard.fromJwt(token.getToken());
        if (u.isPlatformSupport()) return true;
        if (!resolver.isComplete(u.userId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "PROFILE_INCOMPLETE",
                    "Please complete your profile to continue");
        }
        return true;
    }
}
