package org.ngoplatform.campaign.app;

import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.ngoplatform.commons.config.InternalClients;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.Membership;
import org.ngoplatform.commons.security.MembershipResolver;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Team facts from identity-service: member check, number of administrators and display names (for exports). */
@Component
public class TeamDirectory {
    private final RestClient identity;
    private final MembershipResolver memberships;

    public TeamDirectory(InternalClients clients, @Value("${ngo.services.identity}") String url, MembershipResolver memberships) {
        this.identity = clients.forBaseUrl(url);
        this.memberships = memberships;
    }

    /** The user is an active member of the NGO (e.g. the staff member who paid). */
    public Membership activeMember(UUID ngoId, UUID userId) {
        return memberships.find(userId, ngoId).filter(Membership::isActive)
                .orElseThrow(() -> ApiException.badRequest("NOT_A_TEAM_MEMBER", "That person is not an active member of this NGO"));
    }

    public long activeAdmins(UUID ngoId) {
        try {
            Map<String, Object> m = identity.get().uri("/internal/v1/tenants/{id}/usage", ngoId).retrieve()
                    .body(new ParameterizedTypeReference<>() {});
            Object v = m == null ? null : m.get("activeAdmins");
            return v instanceof Number n ? n.longValue() : 2; // unknown: assume another admin exists (no self-approval)
        } catch (RuntimeException e) {
            return 2;
        }
    }

    /** Display names (falls back to e-mail, then to a short id) for PDF / Excel exports. */
    public Map<UUID, String> names(Collection<UUID> ids) {
        Map<UUID, String> out = new HashMap<>();
        for (UUID id : ids.stream().filter(Objects::nonNull).distinct().toList()) {
            String n;
            try {
                Map<String, String> c = identity.get().uri("/internal/v1/users/{id}/contact", id).retrieve()
                        .body(new ParameterizedTypeReference<>() {});
                n = c == null ? null : (c.get("name") != null && !c.get("name").isBlank() ? c.get("name") : c.get("email"));
            } catch (RuntimeException e) {
                n = null;
            }
            out.put(id, n == null ? "Member " + id.toString().substring(0, 8) : n);
        }
        return out;
    }
}
