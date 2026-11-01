package org.ngoplatform.campaign.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.ZoneId;
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

/** A4 landscape PDF of {@link AccountSheet.Sheet}, with signature lines for the NGO's records / auditor. */
public final class AccountSheetPdf {
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("dd MMM yyyy", Locale.ENGLISH);
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("dd MMM yyyy, hh:mm a", Locale.ENGLISH).withZone(ZoneId.of("Asia/Kolkata"));
    /** Fonts with ₹ and wide Latin coverage, tried in order (Windows dev PC, Linux images). */
    static final List<String[]> FONT_FILES = List.of(
            new String[]{"C:/Windows/Fonts/arial.ttf", "C:/Windows/Fonts/arialbd.ttf"},
            new String[]{"/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"},
            new String[]{"/usr/share/fonts/dejavu/DejaVuSans.ttf", "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf"});

    private static final float W = PDRectangle.A4.getHeight(), H = PDRectangle.A4.getWidth(); // landscape
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

    private AccountSheetPdf(String footer) { this.footer = footer; }

    public static byte[] render(AccountSheet.Sheet s) {
        AccountSheetPdf p = new AccountSheetPdf(s.ngoName() + " · " + s.campaign().title());
        try {
            p.fonts();
            p.write(s);
            return p.finish();
        } catch (IOException e) {
            throw new IllegalStateException("Account sheet PDF failed", e);
        }
    }

    private void fonts() throws IOException {
        for (String[] f : FONT_FILES) {
            if (new File(f[0]).isFile()) {
                regular = PDType0Font.load(doc, new File(f[0]));
                bold = new File(f[1]).isFile() ? PDType0Font.load(doc, new File(f[1])) : regular;
                rupee = supports(regular, '₹') && supports(bold, '₹');
                return;
            }
        }
        regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        rupee = false;
    }

    private void write(AccountSheet.Sheet s) throws IOException {
        newPage();
        text(bold, 18, GREEN, M, y, "Campaign account sheet");
        String badge = s.finalSheet() ? "FINAL" : "PROVISIONAL";
        float bw = width(bold, 10, badge) + 16;
        fill(s.finalSheet() ? GREEN : new float[]{0.80f, 0.45f, 0.10f}, W - M - bw, y - 4, bw, 18);
        text(bold, 10, new float[]{1, 1, 1}, W - M - bw + 8, y + 1, badge);
        y -= 24;
        text(bold, 13, INK, M, y, s.campaign().title());
        y -= 16;
        AccountSheet.CampaignInfo c = s.campaign();
        text(regular, 10, MUTED, M, y, s.ngoName() + "   ·   " + DATE.format(c.startDate()) + " to "
                + (c.endDate() == null ? "open" : DATE.format(c.endDate())) + "   ·   Status: " + AccountSheet.statusLabel(c.status())
                + "   ·   Goal " + money(c.goal()));
        y -= 14;
        text(regular, 9, MUTED, M, y, "Generated " + STAMP.format(s.generatedAt()) + (s.generatedBy() == null ? "" : " by " + s.generatedBy())
                + (s.finalSheet() ? "" : "   ·   Not final until the campaign is archived. Pending items are not counted."));
        y -= 22;

        // Summary tiles
        AccountSheet.Totals t = s.totals();
        AccountSheet.Income in = s.income();
        String[][] tiles = {
                {"Money received", money(in.net()), (in.onlineCount() + in.testCount()) + " donations" + (in.refunded().signum() > 0 ? ", " + money(in.refunded()) + " refunded" : "")},
                {"Spent (approved)", money(t.spent()), t.approvedCount() + " expenses"},
                {"Balance", money(t.balance()), "received minus spent"},
                {"Awaiting approval", money(t.pendingAmount()), t.pendingCount() + " expenses (not counted)"}};
        float tw = (W - 2 * M - 3 * 10) / 4;
        for (int i = 0; i < tiles.length; i++) {
            float x = M + i * (tw + 10);
            fill(SHADE, x, y - 40, tw, 52);
            text(regular, 9, MUTED, x + 10, y, tiles[i][0]);
            text(bold, 15, i == 2 && t.balance().signum() < 0 ? new float[]{0.75f, 0.15f, 0.15f} : INK, x + 10, y - 18, tiles[i][1]);
            text(regular, 8, MUTED, x + 10, y - 32, tiles[i][2]);
        }
        y -= 62;
        if (in.testCount() > 0) {
            text(regular, 8, MUTED, M, y, "Includes " + in.testCount() + " test donations (" + money(in.test()) + ") recorded during development.");
            y -= 14;
        }

        heading("Budget vs actual");
        float[] bwds = {300, 120, 120, 120, 80};
        List<String[]> brows = new ArrayList<>();
        for (AccountSheet.BudgetRow b : s.budget()) {
            brows.add(new String[]{b.category() + (b.budgeted() ? "" : "  (not in budget)"), b.budgeted() ? money(b.planned()) : "-",
                    money(b.spent()), b.budgeted() ? money(b.remaining()) : "-", String.valueOf(b.count())});
        }
        if (brows.isEmpty()) brows.add(new String[]{"No budget and no approved expenses yet", "", "", "", ""});
        table(new String[]{"Category", "Planned", "Spent", "Remaining", "Items"}, bwds, brows, new boolean[]{false, true, true, true, true},
                new String[]{"Total", money(t.planned()), money(t.spent()), money(t.budgetRemaining()), String.valueOf(t.approvedCount())});

        heading("Approved expenses");
        float[] ew = {86, 60, 82, 176, 104, 58, 62, 78, 64};
        List<String[]> erows = new ArrayList<>();
        for (AccountSheet.ExpenseRow e : s.expenses()) {
            erows.add(new String[]{e.voucherNo(), DATE.format(e.date()), e.category(), e.description()
                    + (e.bills() == 0 ? " [no bill: " + nz(e.noBillReason()) + "]" : ""), nz(e.vendor()), nz(e.billNumber()),
                    AccountSheet.modeLabel(e.mode()), e.paidBy(), money(e.amount())});
        }
        if (erows.isEmpty()) erows.add(new String[]{"No approved expenses yet", "", "", "", "", "", "", "", ""});
        table(new String[]{"Voucher", "Date", "Category", "Description", "Paid to", "Bill no.", "Mode", "Paid by", "Amount"}, ew, erows,
                new boolean[]{false, false, false, false, false, false, false, false, true},
                new String[]{"Total", "", "", "", "", "", "", "", money(t.spent())});
        if (s.expenses().stream().anyMatch(AccountSheet.ExpenseRow::selfApproved)) {
            text(regular, 8, MUTED, M, y, "Some expenses were approved by the person who submitted them (the NGO had a single administrator).");
            y -= 14;
        }

        if (!s.pending().isEmpty()) {
            heading("Awaiting approval (not counted above)");
            List<String[]> prows = new ArrayList<>();
            for (AccountSheet.ExpenseRow e : s.pending()) {
                prows.add(new String[]{DATE.format(e.date()), e.category(), e.description(), nz(e.vendor()), nz(e.submittedBy()), money(e.amount())});
            }
            table(new String[]{"Date", "Category", "Description", "Paid to", "Submitted by", "Amount"}, new float[]{70, 100, 290, 140, 110, 60},
                    prows, new boolean[]{false, false, false, false, false, true}, null);
        }
        if (!s.voided().isEmpty()) {
            heading("Voided expenses (not counted)");
            List<String[]> vrows = new ArrayList<>();
            for (AccountSheet.ExpenseRow e : s.voided()) {
                vrows.add(new String[]{e.voucherNo(), DATE.format(e.date()), e.description(), nz(e.voidReason()), money(e.amount())});
            }
            table(new String[]{"Voucher", "Date", "Description", "Reason", "Amount"}, new float[]{90, 70, 280, 260, 70}, vrows,
                    new boolean[]{false, false, false, false, true}, null);
        }
        if (!s.staff().isEmpty()) {
            heading("Staff cash for this campaign");
            List<String[]> srows = new ArrayList<>();
            for (AccountSheet.StaffRow r : s.staff()) {
                String pos = r.balance().signum() > 0 ? "NGO owes " + money(r.balance()) : r.balance().signum() < 0 ? "Holds " + money(r.balance().negate()) : "Settled";
                srows.add(new String[]{nz(r.name()), money(r.advanced()), money(r.spent()), money(r.returned()), money(r.reimbursed()), pos});
            }
            table(new String[]{"Staff member", "Advance given", "Spent for NGO", "Returned", "Reimbursed", "Position"},
                    new float[]{200, 100, 100, 100, 100, 170}, srows, new boolean[]{false, true, true, true, true, false}, null);
        }

        // Signatures
        if (y < M + 70) newPage();
        y -= 40;
        float sw = (W - 2 * M - 2 * 40) / 3;
        String[] sig = {"Prepared by", "Verified by", "Approved by (Authorised signatory)"};
        for (int i = 0; i < 3; i++) {
            float x = M + i * (sw + 40);
            line(x, y, x + sw, y);
            text(regular, 9, MUTED, x, y - 12, sig[i]);
        }
    }

    // ---------------- drawing helpers

    private void heading(String title) throws IOException {
        if (y < M + 80) newPage();
        y -= 6;
        text(bold, 11, GREEN, M, y, title);
        y -= 8;
    }

    private void table(String[] header, float[] widths, List<String[]> rows, boolean[] right, String[] total) throws IOException {
        float scale = (W - 2 * M) / sum(widths);
        float[] w = new float[widths.length];
        for (int i = 0; i < w.length; i++) w[i] = widths[i] * scale;
        row(header, w, right, bold, 8.5f, SHADE);
        for (String[] r : rows) {
            if (y < M + 30) { newPage(); row(header, w, right, bold, 8.5f, SHADE); }
            row(r, w, right, regular, 8.5f, null);
        }
        if (total != null) row(total, w, right, bold, 8.5f, SHADE);
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
                String p = "Page " + (i + 1) + " of " + n + "  ·  Yogdaan";
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
        while (v.length() > 1 && width(font, size, v + "…") > max) v = v.substring(0, v.length() - 1);
        return supports(font, '…') ? v + "…" : v + "...";
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
                byte[] enc = font.encode(String.valueOf(c)); // Type0 / TrueType throw when the font has no glyph
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
        NumberFormat f = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"));
        f.setMinimumFractionDigits(2);
        f.setMaximumFractionDigits(2);
        String n = f.format(v.abs());
        return (v.signum() < 0 ? "-" : "") + (rupee ? "₹" : "Rs. ") + n;
    }

    private static float sum(float[] a) { float s = 0; for (float v : a) s += v; return s; }

    private static String nz(String s) { return s == null ? "" : s; }
}
