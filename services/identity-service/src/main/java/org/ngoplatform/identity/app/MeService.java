package org.ngoplatform.identity.app;

import java.util.List;
import java.util.Map;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.CurrentUser;
import org.ngoplatform.identity.domain.NgoMembership;
import org.ngoplatform.identity.domain.Repositories;
import org.ngoplatform.identity.domain.UserAccount;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class MeService {
    private final Repositories.Users users;
    private final Repositories.Memberships memberships;
    private final AuditLog audit;
    private final org.springframework.jdbc.core.JdbcTemplate jdbc;

    public MeService(Repositories.Users users, Repositories.Memberships memberships, AuditLog audit,
                     org.springframework.jdbc.core.JdbcTemplate jdbc) {
        this.users = users; this.memberships = memberships; this.audit = audit; this.jdbc = jdbc;
    }

    /** True while an under-age refusal is recent: a still-valid token must not recreate the deleted record. */
    boolean isRefused(java.util.UUID subject) {
        Integer n = jdbc.queryForObject("select count(*) from refused_subjects where subject = ? and expires_at > now()",
                Integer.class, subject);
        return n != null && n > 0;
    }

    /** Upserts the local profile from the verified token on every /me call (first login creates the user). */
    @Transactional
    public UserAccount sync(CurrentUser cu) {
        if (isRefused(cu.userId())) {
            throw ApiException.forbidden("ACCOUNT_REMOVED", "This account has been removed");
        }
        boolean verified = false;
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken t) {
            verified = Boolean.TRUE.equals(t.getToken().getClaimAsBoolean("email_verified"));
        }
        if (cu.email() == null) throw ApiException.badRequest("EMAIL_REQUIRED", "Your account has no email address");
        final boolean v = verified;
        return users.findById(cu.userId())
                .map(u -> { u.syncFromToken(cu.email(), v, cu.name()); return u; })
                .orElseGet(() -> {
                    // A different sign-in account already owns this e-mail here (e.g. it was deleted in Keycloak
                    // outside the app's deletion flow). Never merge silently: support resolves it.
                    if (users.findByEmailIgnoreCase(cu.email()).isPresent()) {
                        throw ApiException.conflict("ACCOUNT_CONFLICT",
                                "An older account with this e-mail exists. Please contact support.");
                    }
                    return users.save(new UserAccount(cu.userId(), cu.email().toLowerCase(), v, cu.name()));
                });
    }

    /**
     * Whether the user may use the platform: adult profile complete and, for anyone in an NGO team (active member),
     * a verified mobile number (owner rule 2026-10-29).
     */
    @Transactional(readOnly = true)
    public boolean isUsable(java.util.UUID userId) {
        return users.findById(userId).map(u -> u.isProfileComplete() && (u.hasVerifiedPhone() || !needsPhone(userId)))
                .orElse(false);
    }

    /** NGO team members (any active membership) must have a verified mobile number. */
    public boolean needsPhone(java.util.UUID userId) {
        return memberships.findByUserIdAndStatusNot(userId, NgoMembership.Status.REMOVED).stream().anyMatch(NgoMembership::isActive);
    }

    @Transactional(readOnly = true)
    public List<NgoMembership> memberships(CurrentUser cu) {
        return memberships.findByUserIdAndStatusNot(cu.userId(), NgoMembership.Status.REMOVED);
    }

    @Transactional
    public UserAccount updateProfile(CurrentUser cu, String name, String language) {
        UserAccount u = sync(cu);
        u.updateProfile(name, language);
        audit.record(null, cu.userId(), "PROFILE_UPDATED", "USER", cu.userId(), Map.of());
        return u;
    }

    /** AUTH-016: deletion is a request, processed after checks (sole admin, legal retention of receipts, ...). */
    @Transactional
    public UserAccount requestDeletion(CurrentUser cu) {
        UserAccount u = sync(cu);
        boolean soleAdmin = memberships(cu).stream()
                .filter(m -> "PRIMARY_ADMIN".equals(m.getRole()) && m.isActive())
                .anyMatch(m -> memberships.countActiveAdmins(m.getNgoId()) <= 1);
        if (soleAdmin) {
            throw ApiException.rule("SOLE_ADMIN", "Transfer administration of your organization before deleting your account");
        }
        u.requestDeletion();
        audit.record(null, cu.userId(), "ACCOUNT_DELETION_REQUESTED", "USER", cu.userId(), Map.of());
        return u;
    }

    @Transactional
    public UserAccount cancelDeletion(CurrentUser cu) {
        UserAccount u = sync(cu);
        u.cancelDeletion();
        audit.record(null, cu.userId(), "ACCOUNT_DELETION_CANCELLED", "USER", cu.userId(), Map.of());
        return u;
    }
}
