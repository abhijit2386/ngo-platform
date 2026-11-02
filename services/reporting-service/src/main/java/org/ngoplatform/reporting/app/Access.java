package org.ngoplatform.reporting.app;

import java.util.ArrayList;
import java.util.List;
import org.ngoplatform.commons.security.Membership;
import org.ngoplatform.commons.security.Permissions;

/**
 * Who sees which report (owner decisions, 2 Nov 2026).
 * <ul>
 *   <li>NGO-wide reports: administrators and Finance staff (the roles that hold REPORT_EXPORT). Everyone else with
 *       REPORT_VIEW gets "My report" (their own collections, campaigns, cash and claims).</li>
 *   <li>Plan (REPORTING_LEVEL): BASIC (Community) - dashboard, donations summary, My report; STANDARD (Growth) - also
 *       donors, campaigns, budget vs actual, staff cash, approvals and PDF/Excel export; ADVANCED (Professional,
 *       Enterprise) - also the 80G register and Form 10BD data.</li>
 * </ul>
 */
public final class Access {
    private Access() {}

    public enum Level {
        BASIC, STANDARD, ADVANCED;

        public static Level of(String planValue) {
            if (planValue == null) return BASIC;
            return switch (planValue.trim().toUpperCase()) {
                case "STANDARD" -> STANDARD;
                case "ADVANCED", "FULL", "PREMIUM" -> ADVANCED;
                default -> BASIC;
            };
        }

        public boolean atLeast(Level other) { return compareTo(other) >= 0; }
    }

    public static final String DONATIONS = "donations", DONORS = "donors", CAMPAIGNS = "campaigns", STAFF_CASH = "staffCash",
            APPROVALS = "approvals", EXPORT = "export", TAX_80G = "tax80g";

    public static boolean ngoWide(Membership m) { return m.isAdmin() || m.can(Permissions.REPORT_EXPORT); }

    public static boolean canExport(Membership m) { return m.isAdmin() || m.can(Permissions.REPORT_EXPORT); }

    /** Sections of the NGO-wide report this plan includes. */
    public static List<String> sections(Level level) {
        List<String> s = new ArrayList<>(List.of(DONATIONS));
        if (level.atLeast(Level.STANDARD)) s.addAll(List.of(DONORS, CAMPAIGNS, STAFF_CASH, APPROVALS, EXPORT));
        if (level.atLeast(Level.ADVANCED)) s.add(TAX_80G);
        return s;
    }

    /** Sections a higher plan would add (the app shows them as "available on a higher plan"). */
    public static List<String> locked(Level level) {
        List<String> all = new ArrayList<>(sections(Level.ADVANCED));
        all.removeAll(sections(level));
        return all;
    }
}
