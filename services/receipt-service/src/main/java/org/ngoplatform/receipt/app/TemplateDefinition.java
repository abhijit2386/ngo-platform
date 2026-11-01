package org.ngoplatform.receipt.app;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Structured receipt template (spec §15.5): the NGO's background picture plus fields placed on it. Coordinates are
 * PDF points measured from the TOP-LEFT of the page; rendering is always done on the server (PdfRenderer.custom).
 * <p>
 * Text fields print English values (owner decision 2026-11-01); the donor's name as in their profile. Image fields:
 * signatory1..3 (signature, line, name and designation), qrCode (verification), logo.
 * Basic designer (Professional): every field, one active template. Advanced (Enterprise): conditions, several templates.
 */
public record TemplateDefinition(Page page, List<Field> fields, boolean showLogo) {
    public record Page(float width, float height) {}
    public record Condition(String field, String equals) {}

    /**
     * @param height   image fields: box height (text fields: ignored)
     * @param maxLines text fields: wrap inside the width up to this many lines (default 1)
     */
    public record Field(String key, float x, float y, float width, float fontSize, String align, boolean bold,
                        String color, String font, String text, String label, Condition condition, Float height, Integer maxLines) {
        public boolean image() { return IMAGE_KEYS.contains(key); }
        public float boxHeight() {
            if (image()) return height == null ? 0 : height;
            int lines = maxLines == null || maxLines < 1 ? 1 : maxLines;
            return fontSize * 1.25f * lines;
        }
    }

    public static final Set<String> TEXT_KEYS = Set.of("receiptNumber", "date", "donorName", "donorPan", "donorAddress", "amount",
            "amountInWords", "mode", "towards", "ngoName", "ngoAddress", "ngoPan", "ngoRegistrationNumber", "ngo12a", "tax80gText",
            "thankYou", "verifyCode", "staticText");
    public static final Set<String> IMAGE_KEYS = Set.of("signatory1", "signatory2", "signatory3", "qrCode", "logo");
    public static final Set<String> ALL_KEYS;
    static {
        Set<String> all = new HashSet<>(TEXT_KEYS);
        all.addAll(IMAGE_KEYS);
        ALL_KEYS = Set.copyOf(all);
    }
    /** Owner request 2026-11-01: name, amount, amount in words, number with prefix, 80G number, signatures - all on Basic. */
    public static final Set<String> BASIC_KEYS = ALL_KEYS;
    public static final Set<String> REQUIRED_KEYS = Set.of("receiptNumber", "donorName", "amount", "date");
    public static final Set<String> FONTS = Set.of("SANS");
    /** Height kept for the app credit line at the foot of a custom receipt. */
    public static final float FOOTER_PT = 11;
    public static final Set<String> CONDITION_FIELDS = Set.of("tax80g", "channel", "hasPan");

    /**
     * Page in PDF points from a background image: always 595.28 pt wide (A4 width = A5 landscape width), height from
     * the image's proportions. A landscape receipt-book page becomes A5 landscape, a portrait letterhead A4 portrait.
     */
    public static Page pageFor(int pixelsW, int pixelsH) {
        float w = 595.28f;
        return new Page(w, Math.round(w * pixelsH / (float) pixelsW * 100) / 100f);
    }

    static Field text(String key, float x, float y, float w, float size, boolean bold, String label) {
        return new Field(key, x, y, w, size, "LEFT", bold, null, null, null, label, null, null, 1);
    }

    static Field image(String key, float x, float y, float w, float h) {
        return new Field(key, x, y, w, 8, "CENTER", false, null, null, null, null, null, h, null);
    }

    /**
     * Starting layout for a new design: the usual places on a printed receipt, scaled to the page. The NGO then drags
     * each field onto the matching blank of its own receipt book.
     */
    public static TemplateDefinition defaults(Page page) {
        float w = page.width(), h = page.height(), m = 28, sy = h / 419.53f;
        List<Field> f = new ArrayList<>();
        f.add(text("receiptNumber", w - m - 200, 90 * sy, 200, 10, true, "Receipt No."));
        f.add(text("date", w - m - 200, 106 * sy, 200, 10, true, "Date"));
        f.add(text("donorName", 160, 140 * sy, w - 160 - m, 11, true, null));
        f.add(text("amount", 160, 165 * sy, 200, 13, true, null));
        f.add(new Field("amountInWords", 160, 188 * sy, w - 160 - m, 9.5f, "LEFT", true, null, null, null, null, null, null, 2));
        f.add(text("mode", 160, 225 * sy, w - 160 - m, 9.5f, false, null));
        f.add(text("towards", 160, 245 * sy, w - 160 - m, 9.5f, false, null));
        f.add(new Field("tax80gText", m, 268 * sy, w - 2 * m, 7.5f, "LEFT", false, null, null, null, null,
                new Condition("tax80g", "true"), null, 2));
        f.add(image("qrCode", m, h - 92, 62, 62));
        f.add(image("signatory1", w - m - 300, h - 100, 140, 70));
        f.add(image("signatory2", w - m - 145, h - 100, 140, 70));
        return new TemplateDefinition(page, f, false);
    }

    public record Result(List<String> errors, List<String> warnings) {
        public boolean ok() { return errors.isEmpty(); }
    }

    /** Validates geometry, fields, mappings and plan entitlement (basic vs advanced). */
    public Result validate(boolean advanced) {
        List<String> errors = new ArrayList<>(), warnings = new ArrayList<>();
        if (page == null) { errors.add("PAGE_MISSING"); return new Result(errors, warnings); }
        if (page.width() < 200 || page.width() > 1200 || page.height() < 200 || page.height() > 1200) {
            errors.add("PAGE_SIZE_OUT_OF_RANGE: 200-1200 pt");
        }
        if (fields == null || fields.isEmpty()) { errors.add("NO_FIELDS"); return new Result(errors, warnings); }
        if (fields.size() > 60) errors.add("TOO_MANY_FIELDS: max 60");
        Set<String> present = new HashSet<>();
        Set<String> images = new HashSet<>();
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            String where = "field[" + i + "] " + f.key();
            if (f.key() == null || !ALL_KEYS.contains(f.key())) { errors.add("INVALID_MAPPING: " + where); continue; }
            if (!advanced && !BASIC_KEYS.contains(f.key())) errors.add("ADVANCED_FIELD_REQUIRES_ENTERPRISE: " + where);
            if (!advanced && f.condition() != null && !"tax80gText".equals(f.key())) errors.add("CONDITIONS_REQUIRE_ENTERPRISE: " + where);
            if ("staticText".equals(f.key()) && (f.text() == null || f.text().isBlank() || f.text().length() > 300)) {
                errors.add("STATIC_TEXT_INVALID: " + where);
            }
            if (f.image()) {
                if (!images.add(f.key())) errors.add("DUPLICATE_FIELD: " + where);
                float hh = f.height() == null ? 0 : f.height();
                if (f.width() < 20 || hh < 20) errors.add("IMAGE_TOO_SMALL (min 20 x 20 pt): " + where);
                if ("qrCode".equals(f.key()) && Math.min(f.width(), hh) < 45) errors.add("QR_TOO_SMALL (min 45 pt to scan): " + where);
            } else {
                if (f.fontSize() < 6 || f.fontSize() > 48) errors.add("FONT_SIZE_OUT_OF_RANGE (6-48): " + where);
                if (f.maxLines() != null && (f.maxLines() < 1 || f.maxLines() > 6)) errors.add("MAX_LINES_OUT_OF_RANGE (1-6): " + where);
                if (f.width() < 10) errors.add("INVALID_WIDTH: " + where);
            }
            if (f.font() != null && !FONTS.contains(f.font())) errors.add("UNSUPPORTED_FONT: " + where);
            if (f.align() != null && !Set.of("LEFT", "CENTER", "RIGHT").contains(f.align())) errors.add("INVALID_ALIGN: " + where);
            if (f.color() != null && !f.color().matches("^#[0-9A-Fa-f]{6}$")) errors.add("INVALID_COLOR: " + where);
            if (f.x() < 0 || f.y() < 0 || f.x() + f.width() > page.width() + 0.5f || f.y() + f.boxHeight() > page.height() + 0.5f) {
                errors.add("OUT_OF_BOUNDS: " + where);
            }
            if (f.condition() != null && !CONDITION_FIELDS.contains(f.condition().field())) errors.add("INVALID_CONDITION: " + where);
            if (f.label() != null && f.label().length() > 60) errors.add("LABEL_TOO_LONG: " + where);
            if (f.condition() == null) present.add(f.key());
        }
        for (String r : REQUIRED_KEYS) if (!present.contains(r)) errors.add("REQUIRED_FIELD_MISSING: " + r);
        if (!images.contains("qrCode")) warnings.add("QR_MISSING");
        if (images.stream().noneMatch(k -> k.startsWith("signatory"))) warnings.add("SIGNATORY_MISSING");
        if (fields.stream().noneMatch(f -> "tax80gText".equals(f.key()))) warnings.add("TAX80G_MISSING");
        // The app credit line sits centred in the bottom FOOTER_PT of the page (PdfRenderer.custom).
        for (int i = 0; i < fields.size(); i++) {
            Field f = fields.get(i);
            if (f.key() != null && ALL_KEYS.contains(f.key()) && f.y() + f.boxHeight() > page.height() - FOOTER_PT
                    && f.x() < page.width() / 2 + 140 && f.x() + f.width() > page.width() / 2 - 140) {
                warnings.add("FOOTER_OVERLAP: field[" + i + "] " + f.key());
            }
        }
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
        float ah = a.boxHeight(), bh = b.boxHeight();
        return a.x() < b.x() + b.width() && b.x() < a.x() + a.width() && a.y() < b.y() + bh && b.y() < a.y() + ah;
    }

    public static boolean conditionMet(Condition c, Map<String, String> facts) {
        return c == null || c.equals() == null || c.equals().equalsIgnoreCase(facts.getOrDefault(c.field(), ""));
    }

    /** Page changed (new background): fields keep their place relative to the page height and stay inside it. */
    public TemplateDefinition withPage(Page p) {
        if (page == null || fields == null) return new TemplateDefinition(p, fields, showLogo);
        float sx = p.width() / page.width(), sy = p.height() / page.height();
        List<Field> moved = new ArrayList<>();
        for (Field f : fields) {
            float w = Math.min(f.width() * sx, p.width());
            float x = Math.max(0, Math.min(f.x() * sx, p.width() - w));
            Float h = f.height() == null ? null : Math.min(f.height() * sy, p.height());
            float bh = h != null ? h : f.boxHeight();
            float y = Math.max(0, Math.min(f.y() * sy, p.height() - bh));
            moved.add(new Field(f.key(), x, y, w, f.fontSize(), f.align(), f.bold(), f.color(), f.font(), f.text(), f.label(),
                    f.condition(), h, f.maxLines()));
        }
        return new TemplateDefinition(p, moved, showLogo);
    }
}
