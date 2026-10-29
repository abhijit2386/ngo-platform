package org.ngoplatform.commons.security;

import java.util.UUID;

public interface EntitlementResolver {
    Entitlements get(UUID ngoId);
    default void evict(UUID ngoId) {}
}
