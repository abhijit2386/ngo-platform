package org.ngoplatform.receipt.app;

import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Authoritative receipt PDFs (spec §3.8, §15). Standard layout for all plans; template-driven layout for custom
 * designers. If a Unicode font (Noto Sans, SIL OFL) is installed in ngo.receipts.font-dir it is embedded so Hindi/Marathi
 * names print; otherwise characters outside Latin-1 are replaced. PDFBox does not perform complex-script shaping, so
 * some Devanagari conjuncts may render unjoined (documented limitation).
 */
@Component
public class PdfRenderer {
    /** Values available to templates, already formatted. */
    public record Data(Map<String, String> values, byte[] logo, boolean tax80g) {}

    private final Path fontDir;

    public PdfRenderer(@Value("${ngo.receipts.font-dir:}") String fontDir) {
        this.fontDir = fontDir == null || fontDir.isBlank() ? null : Path.of(fontDir);
    }

    private record Fonts(PDFont regular, PDFont bold, boolean unicode) {}

    private Fonts fonts(PDDocument doc, String family) throws IOException {
        if (fontDir != null && Files.exists(fontDir.resolve("NotoSans-Regular.ttf"))) {
            PDFont r = PDType0Font.load(doc, fontDir.resolve("NotoSans-Regular.ttf").toFile());
            Path b = fontDir.resolve("NotoSans-Bold.ttf");
            return new Fonts(r, Files.exists(b) ? PDType0Font.load(doc, b.toFile()) : r, true);
        }
        Standard14Fonts.FontName reg = switch (family == null ? "HELVETICA" : family) {
            case "TIMES" -> Standard14Fonts.FontName.TIMES_ROMAN;
            case "COURIER" -> Standard14Fonts.FontName.COURIER;
            default -> Standard14Fonts.FontName.HELVETICA;
        };
        Standard14Fonts.FontName bold = switch (family == null ? "HELVETICA" : family) {
            case "TIMES" -> Standard14Fonts.FontName.TIMES_BOLD;
            case "COURIER" -> Standard14Fonts.FontName.COURIER_BOLD;
            default -> Standard14Fonts.FontName.HELVETICA_BOLD;
        };
        return new Fonts(new PDType1Font(reg), new PDType1Font(bold), false);
    }

    static String safe(String s, boolean unicode) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c == '\n' || c == '\r' || c == '\t') b.append(' ');
            else if (!unicode && (c < 0x20 || c > 0xFF)) b.append('?');
            else b.append(c);
        }
        return b.toString();
    }

    // ------------------------------------------------------------------ standard receipt (all plans)

    public byte[] standard(Data d) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A5);
            doc.addPage(page);
            Fonts f = fonts(doc, "HELVETICA");
            Map<String, String> v = d.values();
            float w = page.getMediaBox().getWidth(), h = page.getMediaBox().getHeight();
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                cs.setNonStrokingColor(new Color(8, 122, 75));
                cs.addRect(0, h - 70, w, 70);
                cs.fill();
                float textX = 24;
                if (d.logo() != null) {
                    try {
                        PDImageXObject img = PDImageXObject.createFromByteArray(doc, d.logo(), "logo");
                        cs.drawImage(img, 20, h - 60, 50, 50);
                        textX = 80;
                    } catch (IOException ignored) { /* unreadable logo: omit */ }
                }
                cs.setNonStrokingColor(Color.WHITE);
                text(cs, f.bold(), 15, textX, h - 32, safe(v.get("ngoName"), f.unicode()));
                text(cs, f.regular(), 8, textX, h - 46, safe(v.get("ngoAddress"), f.unicode()));
                String ids = join(v.get("ngoRegistrationNumber") == null ? null : "Reg. No. " + v.get("ngoRegistrationNumber"),
                        v.get("ngoPan") == null ? null : "PAN " + v.get("ngoPan"));
                text(cs, f.regular(), 8, textX, h - 58, safe(ids, f.unicode()));

                cs.setNonStrokingColor(new Color(20, 33, 61));
                float y = h - 100;
                text(cs, f.bold(), 13, 24, y, "DONATION RECEIPT");
                text(cs, f.regular(), 9, w - 190, y, "Receipt No: " + safe(v.get("receiptNumber"), f.unicode()));
                y -= 14;
                text(cs, f.regular(), 9, w - 190, y, "Date: " + safe(v.get("date"), f.unicode()));
                y -= 26;
                y = row(cs, f, "Received with thanks from", v.get("donorName"), y);
                if (v.get("donorAddress") != null) y = row(cs, f, "Address", v.get("donorAddress"), y);
                if (d.tax80g() && v.get("donorPan") != null) y = row(cs, f, "Donor PAN", v.get("donorPan"), y);
                y = row(cs, f, "Amount", "INR " + v.get("amount"), y);
                y = row(cs, f, "In words", v.get("amountInWords"), y);
                y = row(cs, f, "Mode / Reference", join(v.get("channel"), v.get("paymentReference")), y);
                if (v.get("purpose") != null) y = row(cs, f, "Purpose", v.get("purpose"), y);
                y -= 10;
                if (d.tax80g() && v.get("tax80gText") != null) {
                    cs.setNonStrokingColor(new Color(230, 243, 236));
                    cs.addRect(20, y - 30, w - 40, 36);
                    cs.fill();
                    cs.setNonStrokingColor(new Color(6, 78, 59));
                    text(cs, f.regular(), 7.5f, 26, y - 8, safe(v.get("tax80gText"), f.unicode()));
                    y -= 46;
                }
                cs.setNonStrokingColor(new Color(20, 33, 61));
                text(cs, f.bold(), 9, w - 200, 70, safe(v.getOrDefault("signatoryName", ""), f.unicode()));
                text(cs, f.regular(), 8, w - 200, 58, safe(v.getOrDefault("signatoryDesignation", "Authorised signatory"), f.unicode()));
                text(cs, f.regular(), 7, 24, 28, "Computer-generated receipt. Donations are paid directly to the organisation's account.");
                text(cs, f.regular(), 7, 24, 18, "Verify: " + safe(v.getOrDefault("verifyHint", ""), f.unicode()));
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PDF rendering failed", e);
        }
    }

    private float row(PDPageContentStream cs, Fonts f, String label, String value, float y) throws IOException {
        text(cs, f.regular(), 8, 24, y, label);
        text(cs, f.bold(), 10, 140, y, safe(value == null ? "-" : value, f.unicode()));
        return y - 18;
    }

    private static String join(String a, String b) {
        if (a == null || a.isBlank()) return b == null ? "" : b;
        if (b == null || b.isBlank()) return a;
        return a + "  |  " + b;
    }

    // ------------------------------------------------------------------ custom template

    public byte[] custom(TemplateDefinition t, byte[] background, String backgroundType, Data d, Map<String, String> facts) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(new PDRectangle(t.page().width(), t.page().height()));
            doc.addPage(page);
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                if (background != null) {
                    PDImageXObject bg = "application/pdf".equals(backgroundType)
                            ? LosslessFactory.createFromImage(doc, firstPage(background))
                            : PDImageXObject.createFromByteArray(doc, background, "background");
                    cs.drawImage(bg, 0, 0, t.page().width(), t.page().height());
                }
                if (t.showLogo() && d.logo() != null) {
                    try {
                        cs.drawImage(PDImageXObject.createFromByteArray(doc, d.logo(), "logo"), 20, t.page().height() - 70, 50, 50);
                    } catch (IOException ignored) { }
                }
                for (TemplateDefinition.Field field : t.fields()) {
                    if (!TemplateDefinition.conditionMet(field.condition(), facts)) continue;
                    Fonts fonts = fonts(doc, field.font());
                    PDFont font = field.bold() ? fonts.bold() : fonts.regular();
                    String value = "staticText".equals(field.key()) ? field.text() : d.values().get(field.key());
                    if (value == null) continue;
                    if ("amount".equals(field.key())) value = "INR " + value;
                    if (field.label() != null && !field.label().isBlank()) value = field.label() + " " + value;
                    value = fit(safe(value, fonts.unicode()), font, field.fontSize(), field.width());
                    float textWidth = font.getStringWidth(value) / 1000 * field.fontSize();
                    float x = switch (field.align() == null ? "LEFT" : field.align()) {
                        case "CENTER" -> field.x() + (field.width() - textWidth) / 2;
                        case "RIGHT" -> field.x() + field.width() - textWidth;
                        default -> field.x();
                    };
                    float y = t.page().height() - field.y() - field.fontSize(); // template origin is top-left
                    cs.setNonStrokingColor(Color.decode(field.color() == null ? "#14213D" : field.color()));
                    text(cs, font, field.fontSize(), x, y, value);
                }
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("PDF rendering failed", e);
        }
    }

    /** Truncates with an ellipsis instead of overflowing the field box. */
    static String fit(String s, PDFont font, float size, float width) throws IOException {
        if (font.getStringWidth(s) / 1000 * size <= width) return s;
        String t = s;
        while (t.length() > 1 && font.getStringWidth(t + "...") / 1000 * size > width) t = t.substring(0, t.length() - 1);
        return t + "...";
    }

    static BufferedImage firstPage(byte[] pdf) throws IOException {
        try (PDDocument src = Loader.loadPDF(pdf)) {
            if (src.getNumberOfPages() < 1) throw new IOException("Empty PDF");
            return new PDFRenderer(src).renderImageWithDPI(0, 150);
        }
    }

    public static PDRectangle pdfPageSize(byte[] pdf) throws IOException {
        try (PDDocument src = Loader.loadPDF(pdf)) { return src.getPage(0).getMediaBox(); }
    }

    private static void text(PDPageContentStream cs, PDFont font, float size, float x, float y, String s) throws IOException {
        if (s == null || s.isEmpty()) return;
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
    }

    @SuppressWarnings("unused")
    private static byte[] read(InputStream in) throws IOException { try (in) { return in.readAllBytes(); } }
}
