package org.ngoplatform.receipt.app;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structured receipt template (spec §15.5). Coordinates are PDF points measured from the TOP-LEFT of the page.
 * Rendering is always done on the backend.
 */
public record TemplateDefinition(Page page, List<Field> fields, boolean showLogo) {
    public record Page(float width, float height) {}
    public record Condition(String field, String equals) {}
    public record Field(String key, float x, float y, float width, float fontSize, String align, boolean bold,
                        String color, String font, String text, String label, Condition condition) {}

    public static final Set<String> BASIC_KEYS = Set.of("receiptNumber", "donorName", "amount", "amountInWords", "date",
            "signatoryName", "paymentReference");
    public static final Set<String> ALL_KEYS = Set.of("receiptNumber", "donorName", "donorPan", "donorAddress", "amount",
            "amountInWords", "date", "paymentReference", "purpose", "channel", "ngoName", "ngoAddress", "ngoPan",
            "ngoRegistrationNumber", "tax80gText", "signatoryName", "signatoryDesignation", "staticText");
    public static final Set<String> REQUIRED_KEYS = Set.of("receiptNumber", "donorName", "amount", "date");
    public static final Set<String> FONTS = Set.of("HELVETICA", "TIMES", "COURIER");
    public static final Set<String> CONDITION_FIELDS = Set.of("tax80g", "channel", "hasPan");

    public record Result(List<String> errors, List<String> warnings) {
        public boolean ok() { return errors.isEmpty(); }
    }

    /** Validates geometry, fields, fonts, mappings and plan entitlement (basic vs advanced). */
    public Result validate(boolean advanced) {
        List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
        if (page == null) { errors.add("PAGE_MISSING"); return new Result(errors, warnings); }
        if (page.width() < 200 || page.width() > 1200 || page.height() < 200 || page.height() > 1200) {
            errors.add("PAGE_SIZE_OUT_OF_RANGE: 200-1200 pt");
        }
        if (fields == null || fields.isEmpty()) { errors.add("NO_FIELDS"); return new Result(errors, warnings); }
        if (fields.size() > 60) errors.add("TOO_MANY_FIELDS: max 60");
        Set<String> present = new java.util.HashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            String where = "field[" + i + "] " + f.key();
            if (f.key() == null || !ALL_KEYS.contains(f.key())) { errors.add("INVALID_MAPPING: " + where); continue; }
            if (!advanced && !BASIC_KEYS.contains(f.key())) errors.add("ADVANCED_FIELD_REQUIRES_ENTERPRISE: " + where);
            if (!advanced && f.condition() != null) errors.add("CONDITIONS_REQUIRE_ENTERPRISE: " + where);
            if ("staticText".equals(f.key()) && (f.text() == null || f.text().isBlank() || f.text().length() > 300)) {
                errors.add("STATIC_TEXT_INVALID: " + where);
            }
            if (f.fontSize() < 6 || f.fontSize() > 48) errors.add("FONT_SIZE_OUT_OF_RANGE (6-48): " + where);
            if (f.font() != null && !FONTS.contains(f.font())) errors.add("UNSUPPORTED_FONT: " + where);
            if (f.align() != null && !Set.of("LEFT", "CENTER", "RIGHT").contains(f.align())) errors.add("INVALID_ALIGN: " + where);
            if (f.color() != null && !f.color().matches("^#[0-9A-Fa-f]{6}$")) errors.add("INVALID_COLOR: " + where);
            if (f.width() <= 0) errors.add("INVALID_WIDTH: " + where);
            if (f.x() < 0 || f.y() < 0 || f.x() + f.width() > page.width() || f.y() + f.fontSize() > page.height()) {
                errors.add("OUT_OF_BOUNDS: " + where);
            }
            if (f.condition() != null && !CONDITION_FIELDS.contains(f.condition().field())) {
                errors.add("INVALID_CONDITION: " + where);
            }
            if (f.label() != null && f.label().length() > 60) errors.add("LABEL_TOO_LONG: " + where);
            if (f.condition() == null) present.add(f.key());
        }
        for (String r : REQUIRED_KEYS) if (!present.contains(r)) errors.add("REQUIRED_FIELD_MISSING: " + r);
        for (int i = 0; i < fields.size(); i++) {
            for (int j = i + 1; j < fields.size(); j++) {
                if (overlap(fields.get(i), fields.get(j))) {
                    warnings.add("OVERLAPPING_FIELDS: " + fields.get(i).key() + " / " + fields.get(j).key());
                }
            }
        }
        return new Result(errors, warnings);
    }

    static boolean overlap(Field a, Field b) {
        float ah = a.fontSize() * 1.3f, bh = b.fontSize() * 1.3f;
        return a.x() < b.x() + b.width() && b.x() < a.x() + a.width() && a.y() < b.y() + bh && b.y() < a.y() + ah;
    }

    public static boolean conditionMet(Condition c, Map<String, String> facts) {
        return c == null || c.equals() == null || c.equals().equalsIgnoreCase(facts.getOrDefault(c.field(), ""));
    }
}
