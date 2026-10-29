package org.ngoplatform.commons.security;

import java.util.Set;
import java.util.UUID;

/** A user's effective membership in one NGO tenant, as resolved by identity-service. */
public record Membership(UUID ngoId, UUID userId, String role, String status, Set<String> permissions) {
    public boolean isActive() { return "ACTIVE".equals(status); }
    public boolean can(String permission) { return isActive() && permissions.contains(permission); }
    public boolean isAdmin() { return isActive() && Permissions.isAdminRole(role); }
}
