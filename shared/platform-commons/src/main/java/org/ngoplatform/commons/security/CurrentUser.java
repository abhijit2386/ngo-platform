package org.ngoplatform.commons.security;

import java.util.Set;
import java.util.UUID;

/** Authenticated caller. userId is the Keycloak subject (UUID) and is the platform-wide user identifier. */
public record CurrentUser(UUID userId, String email, String name, Set<String> platformRoles) {
    public static final String PLATFORM_ADMIN = "PLATFORM_ADMIN";
    public static final String PLATFORM_SUPPORT = "PLATFORM_SUPPORT";

    public boolean isPlatformAdmin() { return platformRoles.contains(PLATFORM_ADMIN); }
    public boolean isPlatformSupport() { return platformRoles.contains(PLATFORM_SUPPORT) || isPlatformAdmin(); }
}
