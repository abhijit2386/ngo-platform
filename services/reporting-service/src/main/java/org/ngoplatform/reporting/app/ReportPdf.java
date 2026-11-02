package org.ngoplatform.reporting.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/** A4 portrait PDF of the NGO-wide report ({@link Reports.NgoReport}). */
public final class ReportPdf {
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH).withZone(Period.IST);
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH).withZone(Period.IST);
    /** Fonts with the rupee sign, tried in order (Windows dev PC, Linux images). */
    static final List<String[]> FONT_FILES = List.of(
            new String[]{"C:/Windows/Fonts/arial.ttf", "C:/Windows/Fonts/arialbd.ttf"},
            new String[]{"/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"},
            new String[]{"/usr/share/fonts/dejavu/DejaVuSans.ttf", "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf"});

    private static final float W = PDRectangle.A4.getWidth(), H = PDRectangle.A4.getHeight();
    private static final float M = 36;
    private static final float[] GREEN = {8 / 255f, 122 / 255f, 75 / 255f};
    private static final float[] INK = {20 / 255f, 33 / 255f, 61 / 255f};
    private static final float[] MUTED = {91 / 255f, 100 / 255f, 120 / 255f};
    private static final float[] LINE = {0.85f, 0.87f, 0.84f};
    private static final float[] SHADE = {0.95f, 0.97f, 0.95f};

    private final PDDocument doc = new PDDocument();
    private PDFont regular, bold;
    private boolean rupee;
    private final Map<PDFont, Map<Character, Boolean>> glyphs = new HashMap<>();
    private final List<PDPage> pages = new ArrayList<>();
    private PDPageContentStream cs;
    private float y;
    private final String footer;

    private ReportPdf(String footer) { this.footer = footer; }

    public static byte[] render(Reports.NgoReport r) {
        ReportPdf p = new ReportPdf(r.ngoName() + " · " + r.period().label());
        try {
            p.fonts();
            p.write(r);
            return p.finish();
        } catch (IOException e) {
            throw new IllegalStateException("Report PDF failed", e);
        }
    }

    private void fonts() throws IOException {
        for (String[] f : FONT_FILES) {
            if (new File(f[0]).isFile()) {
                regular = PDType0Font.load(doc, new File(f[0]));
                bold = new File(f[1]).isFile() ? PDType0Font.load(doc, new File(f[1])) : regular;
                rupee = supports(regular, '\u20B9') && supports(bold, '\u20B9');
                return;
            }
        }
        regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        rupee = false;
    }

    private void write(Reports.NgoReport r) throws IOException {
        newPage();
        text(bold, 18, GREEN, M, y, "NGO report");
        y -= 20;
        text(bold, 12, INK, M, y, r.ngoName());
        y -= 15;
        text(regular, 9.5f, MUTED, M, y, "Period: " + r.period().label() + "   ·   Generated " + STAMP.format(r.generatedAt()));
        y -= 22;

        Reports.Donations d = r.donations();
        heading("Donations");
        kv(new String[][]{{"Total received (net of refunds)", money(d.total())}, {"Donations", String.valueOf(d.count())},
                {"Donors", String.valueOf(d.donors())}});
        table(new String[]{"Month", "Donations", "Amount"}, new float[]{3, 1.2f, 2}, rows(d.byMonth(), Labels::month),
                new boolean[]{false, true, true});
        table(new String[]{"Mode", "Donations", "Amount"}, new float[]{3, 1.2f, 2}, rows(d.byChannel(), Labels::channel),
                new boolean[]{false, true, true});
        table(new String[]{"Campaign", "Donations", "Amount"}, new float[]{3, 1.2f, 2},
                rows(d.byCampaign(), k -> null), new boolean[]{false, true, true});

        if (r.donors() != null) {
            heading("Donors");
            if (r.donors().newDonors() != null) kv(new String[][]{{"New donors in this period", String.valueOf(r.donors().newDonors())}});
            List<String[]> top = new ArrayList<>();
            int i = 0;
            for (Data.Row x : r.donors().top()) top.add(new String[]{String.valueOf(++i), nz(x.label()), String.valueOf(x.count()), money(x.amount())});
            table(new String[]{"#", "Top donors", "Donations", "Amount"}, new float[]{0.5f, 3, 1.2f, 2}, top, new boolean[]{true, false, true, true});
        }
        if (r.campaigns() != null) {
            heading("Campaigns: target vs raised, budget vs actual (whole campaign)");
            List<String[]> c = new ArrayList<>();
            for (Data.CampaignRow x : r.campaigns()) {
                c.add(new String[]{nz(x.title()), Labels.campaignStatus(x.status()), money(x.goal()), money(x.raised()), percent(x.raised(), x.goal()),
                        money(x.planned()), money(x.spent()), percent(x.spent(), x.planned())});
            }
            table(new String[]{"Campaign", "Status", "Target", "Raised", "%", "Budget", "Spent", "%"},
                    new float[]{3, 1.2f, 1.6f, 1.6f, 0.8f, 1.6f, 1.6f, 0.8f}, c, new boolean[]{false, false, true, true, true, true, true, true});
        }
        if (r.staffCash() != null) {
            heading("Staff cash");
            List<String[]> s = new ArrayList<>();
            for (Reports.StaffCash x : r.staffCash()) {
                s.add(new String[]{nz(x.name()), money(x.advanced()), money(x.spent()), money(x.returned()), money(x.reimbursed()),
                        money(x.holds()), money(x.owed())});
            }
            table(new String[]{"Member", "Advanced", "Spent", "Returned", "Reimbursed", "Holds", "NGO owes"},
                    new float[]{2.6f, 1.4f, 1.4f, 1.4f, 1.4f, 1.4f, 1.4f}, s, new boolean[]{false, true, true, true, true, true, true});
        }
        if (r.pending() != null) {
            heading("Waiting for approval");
            kv(new String[][]{{"Expense claims", r.pending().expenses() + " (" + money(r.pending().expenseAmount()) + ")"},
                    {"Campaigns", String.valueOf(r.pending().campaigns())}});
        }
        if (!r.locked().isEmpty()) {
            y -= 4;
            text(regular, 8.5f, MUTED, M, y, "More reports (donors, campaigns, staff cash, 80G / Form 10BD) are available on higher plans.");
            y -= 12;
        }
    }

    private List<String[]> rows(List<Data.Row> rows, java.util.function.Function<String, String> label) {
        List<String[]> out = new ArrayList<>();
        for (Data.Row x : rows) {
            String l = label.apply(x.key());
            if (l == null) l = x.label() != null ? x.label() : "General (no campaign)";
            out.add(new String[]{l, String.valueOf(x.count()), money(x.amount())});
        }
        if (out.isEmpty()) out.add(new String[]{"No donations in this period", "", ""});
        return out;
    }

    private void kv(String[][] pairs) throws IOException {
        for (String[] p : pairs) {
            if (y < M + 30) newPage();
            text(regular, 9.5f, MUTED, M, y, p[0]);
            text(bold, 9.5f, INK, M + 200, y, p[1]);
            y -= 14;
        }
        y -= 6;
    }

    private void heading(String title) throws IOException {
        if (y < M + 80) newPage();
        y -= 6;
        text(bold, 11, GREEN, M, y, title);
        y -= 10;
    }

    private void table(String[] header, float[] widths, List<String[]> rows, boolean[] right) throws IOException {
        float scale = (W - 2 * M) / sum(widths);
        float[] w = new float[widths.length];
        for (int i = 0; i < w.length; i++) w[i] = widths[i] * scale;
        row(header, w, right, bold, 8.5f, SHADE);
        for (String[] r : rows) {
            if (y < M + 30) { newPage(); row(header, w, right, bold, 8.5f, SHADE); }
            row(r, w, right, regular, 8.5f, null);
        }
        y -= 10;
    }

    private void row(String[] cells, float[] w, boolean[] right, PDFont font, float size, float[] shade) throws IOException {
        float h = 16;
        if (shade != null) fill(shade, M, y - h + 4, sum(w), h);
        float x = M;
        for (int i = 0; i < cells.length && i < w.length; i++) {
            String v = fit(font, size, cells[i] == null ? "" : cells[i], w[i] - 8);
            float tx = right[i] ? x + w[i] - 4 - width(font, size, v) : x + 4;
            text(font, size, INK, tx, y - 7, v);
            x += w[i];
        }
        stroke(LINE);
        line(M, y - h + 4, M + sum(w), y - h + 4);
        y -= h;
    }

    private void newPage() throws IOException {
        if (cs != null) cs.close();
        PDPage page = new PDPage(new PDRectangle(W, H));
        doc.addPage(page);
        pages.add(page);
        cs = new PDPageContentStream(doc, page);
        y = H - M - 10;
    }

    private byte[] finish() throws IOException {
        if (cs != null) cs.close();
        int n = pages.size();
        for (int i = 0; i < n; i++) {
            try (PDPageContentStream f = new PDPageContentStream(doc, pages.get(i), PDPageContentStream.AppendMode.APPEND, true)) {
                cs = f;
                text(regular, 8, MUTED, M, 18, footer);
                String p = "Page " + (i + 1) + " of " + n + "  ·  Generated with Yogdaan";
                text(regular, 8, MUTED, W - M - width(regular, 8, p), 18, p);
            }
        }
        cs = null;
        PDDocument d = doc;
        try (d; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            d.save(out);
            return out.toByteArray();
        }
    }

    private void text(PDFont font, float size, float[] color, float x, float yy, String s) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.setNonStrokingColor(color[0], color[1], color[2]);
        cs.newLineAtOffset(x, yy);
        cs.showText(safe(font, s));
        cs.endText();
    }

    private void fill(float[] c, float x, float yy, float w, float h) throws IOException {
        cs.setNonStrokingColor(c[0], c[1], c[2]);
        cs.addRect(x, yy, w, h);
        cs.fill();
    }

    private void stroke(float[] c) throws IOException { cs.setStrokingColor(c[0], c[1], c[2]); cs.setLineWidth(0.5f); }

    private void line(float x1, float y1, float x2, float y2) throws IOException {
        cs.moveTo(x1, y1);
        cs.lineTo(x2, y2);
        cs.stroke();
    }

    private float width(PDFont font, float size, String s) throws IOException { return font.getStringWidth(safe(font, s)) / 1000f * size; }

    private String fit(PDFont font, float size, String s, float max) throws IOException {
        String v = s.replaceAll("\\s+", " ");
        if (width(font, size, v) <= max) return v;
        while (v.length() > 1 && width(font, size, v + "...") > max) v = v.substring(0, v.length() - 1);
        return v + "...";
    }

    /** Replaces characters the font cannot draw (e.g. Devanagari with Arial) instead of failing the whole export. */
    private String safe(PDFont font, String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            if (ch == '\n' || ch == '\r' || ch == '\t') { b.append(' '); continue; }
            b.append(supports(font, ch) ? ch : '?');
        }
        return b.toString();
    }

    private boolean supports(PDFont font, char ch) {
        return glyphs.computeIfAbsent(font, f -> new HashMap<>()).computeIfAbsent(ch, c -> {
            try {
                byte[] enc = font.encode(String.valueOf(c));
                return enc.length > 0 && (!(font instanceof PDType0Font t0) || t0.hasGlyph(codeOf(enc)));
            } catch (IllegalArgumentException | IOException e) {
                return false;
            }
        });
    }

    private static int codeOf(byte[] b) {
        int v = 0;
        for (byte x : b) v = (v << 8) | (x & 0xFF);
        return v;
    }

    private String money(BigDecimal v) {
        if (v == null) v = BigDecimal.ZERO;
        NumberFormat f = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"));
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        return (v.signum() < 0 ? "-" : "") + (rupee ? "\u20B9" : "Rs. ") + f.format(v.abs());
    }

    static String percent(BigDecimal part, BigDecimal whole) {
        if (part == null || whole == null || whole.signum() <= 0) return "";
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 0, RoundingMode.HALF_UP) + "%";
    }

    private static float sum(float[] a) { float s = 0; for (float v : a) s += v; return s; }

    private static String nz(String s) { return s == null ? "" : s; }
}
