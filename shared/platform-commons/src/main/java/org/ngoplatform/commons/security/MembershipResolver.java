package org.ngoplatform.commons.security;

import java.util.Optional;
import java.util.UUID;

public interface MembershipResolver {
    Optional<Membership> find(UUID userId, UUID ngoId);
    default void evict(UUID userId, UUID ngoId) {}
}
