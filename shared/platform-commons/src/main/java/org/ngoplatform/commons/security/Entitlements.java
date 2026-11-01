package org.ngoplatform.commons.security;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Effective entitlements of an NGO, computed by subscription-service.
 * Limit values: integer as string, "-1" = unlimited. Flags: "true"/"false". Levels: BASIC/STANDARD/ADVANCED/NONE.
 */
public record Entitlements(UUID ngoId, String plan, String subscriptionStatus, Map<String, String> values) {
    public static final String STAFF_MAX = "STAFF_MAX";
    public static final String CAMPAIGN_ACTIVE_MAX = "CAMPAIGN_ACTIVE_MAX";
    public static final String PROJECT_ACTIVE_MAX = "PROJECT_ACTIVE_MAX";
    public static final String RECEIPT_BASIC_DESIGNER = "RECEIPT_BASIC_DESIGNER";
    public static final String RECEIPT_ADVANCED_DESIGNER = "RECEIPT_ADVANCED_DESIGNER";
    public static final String REPORTING_LEVEL = "REPORTING_LEVEL";
    public static final String VOLUNTEER_FEATURE_LEVEL = "VOLUNTEER_FEATURE_LEVEL";
    public static final String DONOR_SEGMENTATION_LEVEL = "DONOR_SEGMENTATION_LEVEL";
    public static final String CUSTOM_REPORTING = "CUSTOM_REPORTING";
    public static final String CUSTOM_INTEGRATION_ALLOWED = "CUSTOM_INTEGRATION_ALLOWED";
    public static final String PRIORITY_SUPPORT = "PRIORITY_SUPPORT";
    public static final String ADMIN_MAX = "ADMIN_MAX";
    /** Document storage per NGO in MB (owner 2026-11-01: 2 / 10 / 50 / 100 GB by plan). */
    public static final String STORAGE_MB_MAX = "STORAGE_MB_MAX";

    private static final Set<String> WRITABLE = Set.of("TRIAL", "ACTIVE", "GRACE");

    public boolean flag(String key) { return "true".equalsIgnoreCase(values.getOrDefault(key, "false")); }

    /** -1 means unlimited. Missing limit is treated as 0 (deny by default). */
    public int limit(String key) {
        try { return Integer.parseInt(values.getOrDefault(key, "0")); } catch (NumberFormatException e) { return 0; }
    }

    /** Like {@link #limit} but with a default when the plan does not define the key yet. */
    public int limitOr(String key, int fallback) {
        String v = values == null ? null : values.get(key);
        if (v == null) return fallback;
        try { return Integer.parseInt(v); } catch (NumberFormatException e) { return fallback; }
    }

    public boolean withinLimit(String key, long currentCount) {
        int max = limit(key);
        return max < 0 || currentCount < max;
    }

    public String level(String key) { return values.getOrDefault(key, "NONE"); }

    /** New paid operations (create campaign, invite staff, accept donations...) need a live subscription. */
    public boolean operationsAllowed() { return WRITABLE.contains(subscriptionStatus); }
}
