package org.ngoplatform.receipt.app;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.TextLayout;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Authoritative receipt PDFs (spec §3.8, §15), always rendered on the server.
 * <p>
 * Text is English (owner decision 2026-11-01) in DejaVu Sans (embedded, has the rupee sign). The donor's name is
 * printed exactly as in the donor's profile: names in Hindi, Marathi or another script are shaped by Java's text
 * engine (HarfBuzz) with GNU FreeSans and placed as a high-resolution image, because PDFBox does not join Indic
 * letters. Fonts ship inside the service (licences in resources/fonts).
 */
@Component
public class PdfRenderer {
    public record Signatory(String name, String designation, byte[] png) {}

    /** Values already formatted for printing. */
    public record Data(Map<String, String> values, byte[] logo, boolean tax80g, List<Signatory> signatories, String verifyUrl) {
        String v(String k) { return values.get(k); }
    }

    /**
     * App credit printed small at the foot of every receipt (owner request 2026-11-02: every receipt advertises the app).
     * Text from ngo.receipts.branding; blank turns it off. Kept out of the way of the NGO's own content.
     */
    @Value("${ngo.receipts.branding:Generated with Yogdaan (yogdaan.co.in) - donations and accounts app for NGOs}")
    String branding = "Generated with Yogdaan (yogdaan.co.in) - donations and accounts app for NGOs";

    static final Color INK = new Color(20, 33, 61), MUTED = new Color(91, 100, 120), BRAND = new Color(8, 122, 75),
            BRAND_LIGHT = new Color(230, 243, 236), RULE = new Color(210, 214, 222);
    static final float IMAGE_SCALE = 4f; // pixels per point for shaped text (288 dpi)

    private static final Font SHAPING_FONT = loadAwtFont("/fonts/FreeSans.ttf");
    private static final Object SHAPING_LOCK = new Object();

    private static Font loadAwtFont(String path) {
        try (InputStream in = PdfRenderer.class.getResourceAsStream(path)) {
            if (in == null) throw new IllegalStateException("missing font " + path);
            return Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception e) {
            throw new IllegalStateException("cannot load " + path, e);
        }
    }

    /** One document's fonts and drawing helpers. */
    static final class Pen {
        final PDDocument doc;
        final PDPageContentStream cs;
        final PDFont regular, bold;

        Pen(PDDocument doc, PDPageContentStream cs) throws IOException {
            this.doc = doc; this.cs = cs;
            this.regular = font(doc, "/fonts/DejaVuSans.ttf");
            this.bold = font(doc, "/fonts/DejaVuSans-Bold.ttf");
        }

        private static PDFont font(PDDocument doc, String path) throws IOException {
            try (InputStream in = PdfRenderer.class.getResourceAsStream(path)) {
                if (in == null) throw new IOException("missing font " + path);
                return PDType0Font.load(doc, in, true);
            }
        }

        static boolean printable(PDFont f, String s) {
            try { f.encode(s); return true; } catch (IllegalArgumentException | IOException e) { return false; }
        }

        float width(PDFont f, float size, String s) throws IOException {
            if (s == null || s.isEmpty()) return 0;
            if (printable(f, s)) return f.getStringWidth(s) / 1000 * size;
            return shapedWidth(s, size, f == bold);
        }

        /** Draws text with its baseline at y; returns the width used. */
        float text(PDFont f, float size, float x, float y, String s, Color color) throws IOException {
            if (s == null || s.isEmpty()) return 0;
            String clean = s.replaceAll("[\\r\\n\\t]+", " ");
            if (printable(f, clean)) {
                cs.setNonStrokingColor(color);
                cs.beginText();
                cs.setFont(f, size);
                cs.newLineAtOffset(x, y);
                cs.showText(clean);
                cs.endText();
                return f.getStringWidth(clean) / 1000 * size;
            }
            return shaped(clean, size, f == bold, x, y, color);
        }

        float textRight(PDFont f, float size, float right, float y, String s, Color c) throws IOException {
            return text(f, size, right - width(f, size, s), y, s, c);
        }

        float textCenter(PDFont f, float size, float cx, float y, String s, Color c) throws IOException {
            return text(f, size, cx - width(f, size, s) / 2, y, s, c);
        }

        float shapedWidth(String s, float size, boolean bold) {
            synchronized (SHAPING_LOCK) {
                Font font = SHAPING_FONT.deriveFont(bold ? Font.BOLD : Font.PLAIN, size * IMAGE_SCALE);
                TextLayout layout = new TextLayout(s, font, new FontRenderContext(null, true, true));
                return layout.getAdvance() / IMAGE_SCALE;
            }
        }

        private float shaped(String s, float size, boolean bold, float x, float baseline, Color color) throws IOException {
            BufferedImage img;
            int pad = 2, desc;
            float advance;
            // Native shaping/rasterising (HarfBuzz, font scaler) runs one at a time: the worker and a request thread can
            // render together, and this path is rare (non-Latin names), so serialising it costs nothing.
            synchronized (SHAPING_LOCK) {
                Font font = SHAPING_FONT.deriveFont(bold ? Font.BOLD : Font.PLAIN, size * IMAGE_SCALE);
                FontRenderContext frc = new FontRenderContext(null, true, true);
                TextLayout layout = new TextLayout(s, font, frc);
                advance = layout.getAdvance();
                int w = (int) Math.ceil(layout.getAdvance()) + 2 * pad;
                int asc = (int) Math.ceil(layout.getAscent());
                desc = (int) Math.ceil(layout.getDescent());
                img = new BufferedImage(Math.max(1, w), Math.max(1, asc + desc + 2 * pad), BufferedImage.TYPE_INT_ARGB);
                Graphics2D g = img.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
                g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g.setColor(color);
                layout.draw(g, pad, pad + asc);
                g.dispose();
            }
            PDImageXObject x0 = LosslessFactory.createFromImage(doc, img);
            float pw = img.getWidth() / IMAGE_SCALE, ph = img.getHeight() / IMAGE_SCALE;
            cs.drawImage(x0, x - pad / IMAGE_SCALE, baseline - (desc + pad) / IMAGE_SCALE, pw, ph);
            return advance / IMAGE_SCALE;
        }

        void rect(float x, float y, float w, float h, Color fill) throws IOException {
            cs.setNonStrokingColor(fill);
            cs.addRect(x, y, w, h);
            cs.fill();
        }

        void line(float x1, float y1, float x2, float y2, Color c, float width) throws IOException {
            cs.setStrokingColor(c);
            cs.setLineWidth(width);
            cs.moveTo(x1, y1);
            cs.lineTo(x2, y2);
            cs.stroke();
        }

        /** Image fitted inside the box (keeps proportions), anchored bottom-centre. */
        void image(byte[] bytes, float x, float y, float maxW, float maxH) throws IOException {
            if (bytes == null) return;
            PDImageXObject img;
            try { img = PDImageXObject.createFromByteArray(doc, bytes, "img"); } catch (IOException e) { return; }
            float f = Math.min(maxW / img.getWidth(), maxH / img.getHeight());
            float w = img.getWidth() * f, h = img.getHeight() * f;
            cs.drawImage(img, x + (maxW - w) / 2, y, w, h);
        }

        /** Greedy word wrap into lines no wider than width. */
        List<String> wrap(PDFont f, float size, String s, float width) throws IOException {
            List<String> lines = new ArrayList<>();
            if (s == null || s.isBlank()) return lines;
            StringBuilder cur = new StringBuilder();
            for (String word : s.trim().split("\\s+")) {
                String trial = cur.isEmpty() ? word : cur + " " + word;
                if (width(f, size, trial) <= width || cur.isEmpty()) {
                    cur.setLength(0);
                    cur.append(trial);
                } else {
                    lines.add(cur.toString());
                    cur.setLength(0);
                    cur.append(word);
                }
            }
            if (!cur.isEmpty()) lines.add(cur.toString());
            return lines;
        }

        /** Shortens with an ellipsis instead of overflowing. */
        String fit(PDFont f, float size, String s, float width) throws IOException {
            if (s == null || width(f, size, s) <= width) return s;
            String t = s;
            while (t.length() > 1 && width(f, size, t + "…") > width) t = t.substring(0, t.length() - 1);
            return t.strip() + "…";
        }
    }

    // ------------------------------------------------------------------ standard receipt (all plans)

    /** A5 landscape: two receipts fit on one A4 sheet. */
    public byte[] standard(Data d) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(new PDRectangle(PDRectangle.A5.getHeight(), PDRectangle.A5.getWidth()));
            doc.addPage(page);
            float w = page.getMediaBox().getWidth(), h = page.getMediaBox().getHeight(), m = 28;
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                Pen p = new Pen(doc, cs);
                // Header band with logo, name, address and registration numbers.
                float band = 66;
                p.rect(0, h - band, w, band, BRAND);
                float tx = m;
                if (d.logo() != null) {
                    p.rect(m - 2, h - band + 9, 50, 50, Color.WHITE);
                    p.image(d.logo(), m, h - band + 11, 46, 46);
                    tx = m + 60;
                }
                float maxText = w - tx - m;
                p.text(p.bold, 15, tx, h - 28, p.fit(p.bold, 15, d.v("ngoName"), maxText), Color.WHITE);
                p.text(p.regular, 7.5f, tx, h - 41, p.fit(p.regular, 7.5f, d.v("ngoAddress"), maxText), Color.WHITE);
                p.text(p.regular, 7.5f, tx, h - 53, p.fit(p.regular, 7.5f, ids(d), maxText), Color.WHITE);

                // Title, number and date.
                float y = h - band - 26;
                p.text(p.bold, 13, m, y, "DONATION RECEIPT", INK);
                float numberW = Math.max(p.width(p.bold, 10, d.v("receiptNumber")), p.width(p.bold, 10, d.v("date")));
                p.textRight(p.regular, 8, w - m - numberW - 10, y + 2, "Receipt No.", MUTED);
                p.textRight(p.bold, 10, w - m, y + 2, d.v("receiptNumber"), INK);
                p.textRight(p.regular, 8, w - m - numberW - 10, y - 11, "Date", MUTED);
                p.textRight(p.bold, 10, w - m, y - 11, d.v("date"), INK);
                p.line(m, y - 20, w - m, y - 20, RULE, 0.6f);

                // Body rows.
                y -= 38;
                float vx = m + 128, vw = w - m - vx;
                y = row(p, "Received with thanks from", d.v("donorName"), 11, vx, vw, y, m);
                if (d.tax80g() && d.v("donorAddress") != null) y = row(p, "Address", d.v("donorAddress"), 9, vx, vw, y, m);
                if (d.tax80g() && d.v("donorPan") != null) y = row(p, "PAN of donor", d.v("donorPan"), 10, vx, vw, y, m);
                p.text(p.regular, 8, m, y, "Amount", MUTED);
                p.text(p.bold, 14, vx, y - 1, "₹ " + d.v("amount"), BRAND);
                y -= 21;
                y = row(p, "The sum of", d.v("amountInWords"), 9.5f, vx, vw, y, m);
                y = row(p, "Payment", d.v("mode"), 9.5f, vx, vw, y, m);
                y = row(p, "Towards", d.v("towards"), 9.5f, vx, vw, y, m);

                // 80G statement only for eligible receipts.
                if (d.tax80g() && d.v("tax80gText") != null) {
                    List<String> lines = p.wrap(p.regular, 7.5f, d.v("tax80gText"), w - 2 * m - 16);
                    float bh = 8 + lines.size() * 10;
                    p.rect(m, y - bh + 8, w - 2 * m, bh, BRAND_LIGHT);
                    float ly = y;
                    for (String l : lines) { p.text(p.regular, 7.5f, m + 8, ly, l, new Color(6, 78, 59)); ly -= 10; }
                    y -= bh + 4;
                }
                if (d.v("thankYou") != null) {
                    p.text(p.regular, 8.5f, m, y, p.fit(p.regular, 8.5f, d.v("thankYou"), w - 2 * m), INK);
                }

                // QR code to verify, bottom left.
                float qr = 62;
                if (d.verifyUrl() != null) {
                    cs.drawImage(LosslessFactory.createFromImage(doc, qr(d.verifyUrl(), 4)), m - 4, 26, qr, qr);
                    p.text(p.regular, 6.5f, m, 18, "Scan to verify · " + d.v("verifyCode"), MUTED);
                }
                // Signatures, bottom right.
                signatures(p, d.signatories(), m + qr + 20, w - m, 34);
                String foot = "This is a computer-generated receipt."
                        + (branding == null || branding.isBlank() ? "" : "  ·  " + branding.trim());
                p.textCenter(p.regular, 6, w / 2 + 40, 8, p.fit(p.regular, 6, foot, w - 2 * m), MUTED);
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PDF rendering failed", e);
        }
    }

    static String ids(Data d) {
        List<String> parts = new ArrayList<>();
        if (d.v("ngoRegistrationNumber") != null) parts.add("Reg. No. " + d.v("ngoRegistrationNumber"));
        if (d.v("ngoPan") != null) parts.add("PAN " + d.v("ngoPan"));
        if (d.v("ngo12a") != null) parts.add("12A " + d.v("ngo12a"));
        return String.join("   ·   ", parts);
    }

    private float row(Pen p, String label, String value, float size, float vx, float vw, float y, float m) throws IOException {
        p.text(p.regular, 8, m, y, label, MUTED);
        List<String> lines = p.wrap(p.bold, size, value == null || value.isBlank() ? "-" : value, vw);
        if (lines.size() > 2) lines = List.of(lines.get(0), p.fit(p.bold, size, String.join(" ", lines.subList(1, lines.size())), vw));
        for (String l : lines) { p.text(p.bold, size, vx, y, l, INK); y -= size + 4; }
        return y - 7;
    }

    /** Up to three signatories side by side: signature image above a line, name and designation below. */
    void signatures(Pen p, List<Signatory> list, float left, float right, float baseY) throws IOException {
        List<Signatory> s = list == null || list.isEmpty() ? List.of(new Signatory(null, "Authorised signatory", null))
                : list.subList(0, Math.min(ReceiptRules.MAX_SIGNATORIES, list.size()));
        float col = Math.min(150, (right - left) / s.size());
        float x = right - col * s.size();
        for (Signatory sig : s) {
            float cx = x + col / 2, lw = col - 16;
            p.image(sig.png(), cx - lw / 2, baseY + 20, lw, 34);
            p.line(cx - lw / 2, baseY + 17, cx + lw / 2, baseY + 17, INK, 0.5f);
            if (sig.name() != null) p.textCenter(p.bold, 8, cx, baseY + 6, p.fit(p.bold, 8, sig.name(), lw), INK);
            p.textCenter(p.regular, 7, cx, baseY - 4, p.fit(p.regular, 7, sig.designation(), lw), MUTED);
            x += col;
        }
    }

    /** QR code as a black-on-white image (margin 1 module, error correction M). */
    static BufferedImage qr(String content, int modulePx) {
        try {
            BitMatrix m = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0,
                    Map.of(EncodeHintType.MARGIN, 1, EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M));
            BufferedImage img = new BufferedImage(m.getWidth() * modulePx, m.getHeight() * modulePx, BufferedImage.TYPE_INT_RGB);
            for (int y = 0; y < img.getHeight(); y++) {
                for (int x = 0; x < img.getWidth(); x++) {
                    img.setRGB(x, y, m.get(x / modulePx, y / modulePx) ? 0x000000 : 0xFFFFFF);
                }
            }
            return img;
        } catch (Exception e) {
            throw new IllegalStateException("QR encoding failed", e);
        }
    }

    // ------------------------------------------------------------------ custom template (designer, R2)

    /**
     * The NGO's own receipt: its background picture (PNG/JPEG, already re-encoded) with the fields placed by the
     * designer. Values are the same as on the standard receipt; empty values (e.g. 80G text on a non-80G receipt) are
     * simply not printed.
     */
    public byte[] custom(TemplateDefinition t, byte[] background, Data d, Map<String, String> facts) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            float pw = t.page().width(), ph = t.page().height();
            PDPage page = new PDPage(new PDRectangle(pw, ph));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                Pen p = new Pen(doc, cs);
                if (background != null) {
                    cs.drawImage(PDImageXObject.createFromByteArray(doc, background, "background"), 0, 0, pw, ph);
                }
                if (t.showLogo() && d.logo() != null && t.fields().stream().noneMatch(f -> "logo".equals(f.key()))) {
                    p.image(d.logo(), 20, ph - 70, 50, 50);
                }
                List<Signatory> sigs = d.signatories() == null ? List.of() : d.signatories();
                for (TemplateDefinition.Field field : t.fields()) {
                    if (!TemplateDefinition.conditionMet(field.condition(), facts)) continue;
                    float top = ph - field.y();                 // template origin is top-left
                    if (field.image()) {
                        float bh = field.height() == null ? 40 : field.height();
                        float bottom = top - bh;
                        switch (field.key()) {
                            case "qrCode" -> {
                                if (d.verifyUrl() == null) break;
                                float size = Math.min(field.width(), bh);
                                cs.drawImage(LosslessFactory.createFromImage(doc, qr(d.verifyUrl(), 4)),
                                        field.x() + (field.width() - size) / 2, top - size, size, size);
                            }
                            case "logo" -> p.image(d.logo(), field.x(), bottom, field.width(), bh);
                            default -> {
                                int n = Integer.parseInt(field.key().substring("signatory".length())) - 1;
                                if (n < sigs.size()) signatoryBox(p, sigs.get(n), field, top, bh);
                            }
                        }
                        continue;
                    }
                    String value = "staticText".equals(field.key()) ? field.text() : d.values().get(field.key());
                    if (value == null || value.isBlank()) continue;
                    if ("amount".equals(field.key())) value = "₹ " + value;
                    if (field.label() != null && !field.label().isBlank()) value = field.label() + " " + value;
                    PDFont font = field.bold() ? p.bold : p.regular;
                    Color color = Color.decode(field.color() == null ? "#14213D" : field.color());
                    int max = field.maxLines() == null || field.maxLines() < 1 ? 1 : field.maxLines();
                    List<String> lines = p.wrap(font, field.fontSize(), value, field.width());
                    if (lines.size() > max) {
                        List<String> keep = new ArrayList<>(lines.subList(0, max - 1));
                        keep.add(p.fit(font, field.fontSize(), String.join(" ", lines.subList(max - 1, lines.size())), field.width()));
                        lines = keep;
                    }
                    float y = top - field.fontSize();
                    for (String line : lines) {
                        String l = p.fit(font, field.fontSize(), line, field.width());
                        float tw = p.width(font, field.fontSize(), l);
                        float x = switch (field.align() == null ? "LEFT" : field.align()) {
                            case "CENTER" -> field.x() + (field.width() - tw) / 2;
                            case "RIGHT" -> field.x() + field.width() - tw;
                            default -> field.x();
                        };
                        p.text(font, field.fontSize(), x, y, l, color);
                        y -= field.fontSize() * 1.25f;
                    }
                }
                // App credit on a white strip at the foot so it reads on any background picture.
                if (branding != null && !branding.isBlank()) {
                    String foot = p.fit(p.regular, 5.5f, branding.trim(), pw - 40);
                    float fw = p.width(p.regular, 5.5f, foot) + 10;
                    p.rect((pw - fw) / 2, 1.5f, fw, 9, Color.WHITE);
                    p.textCenter(p.regular, 5.5f, pw / 2, 4, foot, MUTED);
                }
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PDF rendering failed", e);
        }
    }

    /** Signature image in the upper part of the box, a line, then name and designation. */
    private void signatoryBox(Pen p, Signatory s, TemplateDefinition.Field f, float top, float bh) throws IOException {
        float size = Math.max(6, Math.min(f.fontSize(), 10));
        float textH = size * 2.4f;
        float imgH = Math.max(10, bh - textH - 4);
        float lineY = top - imgH - 2;
        p.image(s.png(), f.x(), lineY + 1, f.width(), imgH);
        p.line(f.x() + 4, lineY, f.x() + f.width() - 4, lineY, INK, 0.5f);
        float cx = f.x() + f.width() / 2;
        if (s.name() != null) p.textCenter(p.bold, size, cx, lineY - size - 1, p.fit(p.bold, size, s.name(), f.width()), INK);
        p.textCenter(p.regular, size - 1, cx, lineY - 2 * size - 2, p.fit(p.regular, size - 1, s.designation(), f.width()), MUTED);
    }
}
