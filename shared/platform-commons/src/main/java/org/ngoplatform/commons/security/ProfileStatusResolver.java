package org.ngoplatform.commons.security;

import java.util.UUID;

/**
 * Whether a user may use the platform: profile completed with a date of birth showing 18+ and account active.
 * identity-service answers locally; other services ask identity (see {@link RestProfileStatusResolver}).
 */
public interface ProfileStatusResolver {
    boolean isComplete(UUID userId);
    default void evict(UUID userId) {}
}
