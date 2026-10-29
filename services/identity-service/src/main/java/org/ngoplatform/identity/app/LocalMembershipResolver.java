package org.ngoplatform.identity.app;

import java.util.Optional;
import java.util.UUID;
import org.ngoplatform.commons.security.Membership;
import org.ngoplatform.commons.security.MembershipResolver;
import org.ngoplatform.identity.domain.NgoMembership;
import org.ngoplatform.identity.domain.Repositories;
import org.springframework.stereotype.Component;

/** identity-service is the source of truth, so it resolves memberships from its own DB. */
@Component
public class LocalMembershipResolver implements MembershipResolver {
    private final Repositories.Memberships memberships;

    public LocalMembershipResolver(Repositories.Memberships memberships) { this.memberships = memberships; }

    @Override
    public Optional<Membership> find(UUID userId, UUID ngoId) {
        return memberships.findByNgoIdAndUserId(ngoId, userId)
                .filter(m -> m.getStatus() != NgoMembership.Status.REMOVED)
                .map(LocalMembershipResolver::toDto);
    }

    public static Membership toDto(NgoMembership m) {
        return new Membership(m.getNgoId(), m.getUserId(), m.getRole(), m.getStatus().name(), RoleCatalog.effective(m));
    }
}
