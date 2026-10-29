package org.ngoplatform.identity.app;

import java.time.Duration;
import java.time.Instant;
import org.ngoplatform.commons.error.ApiException;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Sensitive admin changes require a recent interactive login (auth_time within 10 minutes). The app reacts to
 * STEP_UP_REQUIRED by re-running the OIDC flow with prompt=login / max_age.
 */
public final class StepUp {
    static final Duration MAX_AGE = Duration.ofMinutes(10);
    private StepUp() {}

    public static void require() {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken t)) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Sign in required");
        }
        Object authTime = t.getToken().getClaims().get("auth_time");
        Instant at = null;
        if (authTime instanceof Number n) at = Instant.ofEpochSecond(n.longValue());
        else if (authTime instanceof Instant i) at = i;
        if (at == null || at.isBefore(Instant.now().minus(MAX_AGE))) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "STEP_UP_REQUIRED",
                    "Please sign in again to confirm this sensitive change");
        }
    }
}
