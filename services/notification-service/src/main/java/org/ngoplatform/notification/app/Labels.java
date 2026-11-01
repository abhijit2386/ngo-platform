package org.ngoplatform.notification.app;

import java.util.Map;

/** Display names for codes that appear inside messages, per language (fallback: English). */
final class Labels {
    private Labels() {}

    private static final Map<String, Map<String, String>> ROLES = Map.of(
            "en", Map.of("PRIMARY_ADMIN", "Primary admin", "SECONDARY_ADMIN", "Secondary admin", "STAFF", "Staff",
                    "FINANCE", "Finance", "CAMPAIGN_MANAGER", "Campaign manager",
                    "VOLUNTEER_COORDINATOR", "Volunteer coordinator", "VOLUNTEER", "Volunteer"),
            "hi", Map.of("PRIMARY_ADMIN", "मुख्य प्रशासक", "SECONDARY_ADMIN", "सह-प्रशासक", "STAFF", "कर्मचारी",
                    "FINANCE", "वित्त", "CAMPAIGN_MANAGER", "अभियान प्रबंधक",
                    "VOLUNTEER_COORDINATOR", "स्वयंसेवक समन्वयक", "VOLUNTEER", "स्वयंसेवक"),
            "mr", Map.of("PRIMARY_ADMIN", "मुख्य प्रशासक", "SECONDARY_ADMIN", "सह-प्रशासक", "STAFF", "कर्मचारी",
                    "FINANCE", "वित्त", "CAMPAIGN_MANAGER", "मोहीम व्यवस्थापक",
                    "VOLUNTEER_COORDINATOR", "स्वयंसेवक समन्वयक", "VOLUNTEER", "स्वयंसेवक"));

    private static final Map<String, String> PLANS = Map.of("COMMUNITY", "Community", "GROWTH", "Growth",
            "PROFESSIONAL", "Professional", "ENTERPRISE", "Enterprise");

    static String role(String code, String language) {
        if (code == null) return "";
        Map<String, String> m = ROLES.getOrDefault(language, ROLES.get("en"));
        return m.getOrDefault(code, ROLES.get("en").getOrDefault(code, code));
    }

    static String plan(String code) { return code == null ? "" : PLANS.getOrDefault(code, code); }

    /** "1500.00" -> "₹1,500"; "1500.50" -> "₹1,500.50" (Indian grouping). */
    static String inr(String amount) {
        if (amount == null) return "";
        java.math.BigDecimal v = new java.math.BigDecimal(amount);
        java.text.NumberFormat f = java.text.NumberFormat.getCurrencyInstance(java.util.Locale.forLanguageTag("en-IN"));
        f.setMaximumFractionDigits(v.stripTrailingZeros().scale() > 0 ? 2 : 0);
        f.setMinimumFractionDigits(v.stripTrailingZeros().scale() > 0 ? 2 : 0);
        return f.format(v);
    }

    private static final java.time.ZoneId INDIA = java.time.ZoneId.of("Asia/Kolkata");

    /** ISO instant -> "Sat, 15 Nov 2026, 11:00 AM" (India time). */
    static String dateTime(String iso) {
        if (iso == null || iso.isBlank()) return "";
        return java.time.format.DateTimeFormatter.ofPattern("EEE, d MMM yyyy, h:mm a", java.util.Locale.ENGLISH)
                .format(java.time.Instant.parse(iso).atZone(INDIA));
    }

    /** ISO date -> "15 Nov 2026". */
    static String date(String iso) {
        if (iso == null || iso.isBlank()) return "";
        return java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy", java.util.Locale.ENGLISH).format(java.time.LocalDate.parse(iso));
    }

    static String language(String lang) { return lang != null && (lang.equals("hi") || lang.equals("mr")) ? lang : "en"; }
}
