package org.ngoplatform.campaign.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
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
import org.apache.pdfbox.pdmodel.graphics.state.PDExtendedGraphicsState;
import org.apache.pdfbox.util.Matrix;

/**
 * Utilisation certificate PDF (A4 portrait): organisation, grant, statement by budget head, certificate wording,
 * the NGO's and the CA's sign-off, and an annexure with every expense. Drafts carry a "DRAFT - NOT CERTIFIED" mark.
 */
public final class UcPdf {
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM yyyy, h:mm a", Locale.ENGLISH).withZone(ZoneId.of("Asia/Kolkata"));
    private static final float W = PDRectangle.A4.getWidth(), H = PDRectangle.A4.getHeight(), M = 42;
    private static final float[] GREEN = {8 / 255f, 122 / 255f, 75 / 255f}, INK = {20 / 255f, 33 / 255f, 61 / 255f},
            MUTED = {91 / 255f, 100 / 255f, 120 / 255f}, LINE = {0.82f, 0.85f, 0.82f}, SHADE = {0.95f, 0.97f, 0.95f},
            RED = {0.75f, 0.15f, 0.15f};

    private final PDDocument doc = new PDDocument();
    private PDFont regular, bold;
    private boolean rupee;
    private PDPageContentStream cs;
    private float y;
    private final List<PDPage> pages = new ArrayList<>();
    private final boolean draft;
    private final String footer;

    private UcPdf(boolean draft, String footer) { this.draft = draft; this.footer = footer; }

    public static byte[] render(UcService.Uc uc, String project, Map<String, Object> ngo, UcRules.Figures f, boolean draft) {
        UcPdf p = new UcPdf(draft, (draft ? "DRAFT - NOT CERTIFIED   ·   " : "") + uc.number() + " · " + str(ngo.get("legalName"), str(ngo.get("displayName"), "")));
        try {
            p.fonts();
            p.write(uc, project, ngo, f);
            return p.finish();
        } catch (IOException e) {
            throw new IllegalStateException("Utilisation certificate PDF failed", e);
        }
    }

    private void fonts() throws IOException {
        for (String[] f : AccountSheetPdf.FONT_FILES) {
            if (new File(f[0]).isFile()) {
                regular = PDType0Font.load(doc, new File(f[0]));
                bold = new File(f[1]).isFile() ? PDType0Font.load(doc, new File(f[1])) : regular;
                try { regular.encode("₹"); bold.encode("₹"); rupee = true; } catch (Exception e) { rupee = false; }
                return;
            }
        }
        regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
        bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
    }

    private void write(UcService.Uc uc, String project, Map<String, Object> ngo, UcRules.Figures f) throws IOException {
        newPage();
        String ngoName = str(ngo.get("legalName"), str(ngo.get("displayName"), "Organisation"));
        // Organisation header
        fill(GREEN, 0, H - 86, W, 86);
        text(bold, 17, new float[]{1, 1, 1}, M, H - 38, ngoName);
        List<String> ids = new ArrayList<>();
        if (ngo.get("registrationNumber") != null) ids.add("Reg. No. " + ngo.get("registrationNumber"));
        if (ngo.get("pan") != null) ids.add("PAN " + ngo.get("pan"));
        if (ngo.get("tax80gNumber") != null) ids.add("80G " + ngo.get("tax80gNumber"));
        if (uc.csr1Number() != null) ids.add("CSR-1 " + uc.csr1Number());
        text(regular, 9, new float[]{1, 1, 1}, M, H - 56, str(ngo.get("address"), ""));
        text(regular, 9, new float[]{1, 1, 1}, M, H - 70, String.join("   ·   ", ids));
        y = H - 118;
        text(bold, 15, INK, M, y, "UTILISATION CERTIFICATE");
        textRight(bold, 10, INK, W - M, y + 2, uc.number());
        y -= 16;
        textRight(regular, 9, MUTED, W - M, y, "Period " + DATE.format(uc.periodFrom()) + " to " + DATE.format(uc.periodTo()));
        y -= 22;

        // Grant details
        String[][] rows = {
                {"CSR funder", uc.funderName()},
                {"Sanction / agreement", uc.funderReference() == null ? "-" : uc.funderReference()},
                {"Project", project},
                {"Grant received", money(f.grant()) + " on " + DATE.format(uc.grantReceivedOn())}};
        for (String[] r : rows) {
            text(regular, 9.5f, MUTED, M, y, r[0]);
            for (String l : wrap(bold, 10, r[1], W - 2 * M - 140)) { text(bold, 10, INK, M + 140, y, l); y -= 13; }
            y -= 3;
        }
        y -= 6;

        // Statement by budget head
        heading("Statement of utilisation");
        float[] cw = {W - 2 * M - 3 * 90, 90, 90, 90};
        tableHeader(new String[]{"Budget head", "Budget", "Utilised", "Entries"}, cw);
        for (UcRules.Head h : f.heads()) {
            row(new String[]{h.head(), h.budget() == null ? "-" : money(h.budget()), money(h.spent()), String.valueOf(h.count())}, cw, false);
        }
        row(new String[]{"Total expenditure in the period", "", money(f.spent()), String.valueOf(f.lines().size())}, cw, true);
        y -= 8;
        String[][] totals = {{"Grant received", money(f.grant())}, {"Utilised from the grant", money(f.utilised())},
                {"Unspent balance at the end of the period", money(f.unspent())}};
        for (String[] t : totals) {
            text(regular, 10, INK, M, y, t[0]);
            textRight(bold, 10, INK, W - M, y, t[1]);
            y -= 15;
        }
        if (f.ownFunds().signum() > 0) {
            text(regular, 10, INK, M, y, "Met from the organisation's own funds");
            textRight(bold, 10, INK, W - M, y, money(f.ownFunds()));
            y -= 15;
        }
        if (f.withoutBill() > 0) {
            text(regular, 8.5f, RED, M, y, f.withoutBill() + " expense(s) without a bill attached in the books (see annexure).");
            y -= 13;
        }
        y -= 8;

        // Certificate wording
        heading("Certificate");
        String statement = UcRules.statement(ngoName, uc.funderName(), uc.funderReference(), project, uc.periodFrom(), uc.periodTo(),
                uc.grantReceivedOn(), f);
        for (String l : wrap(regular, 9.5f, statement, W - 2 * M)) { text(regular, 9.5f, INK, M, y, l); y -= 13; }
        y -= 14;

        // Sign-off
        float colW = (W - 2 * M - 20) / 2, top = y;
        float left = signBlock(M, top, colW, "For " + ngoName,
                uc.signatoryName() == null ? List.of("(awaiting the organisation's signature)")
                        : List.of(uc.signatoryName(), uc.signatoryRole(), "Approved in Yogdaan on " + STAMP.format(uc.ngoSignedAt())));
        List<String> ca = new ArrayList<>();
        if (uc.caName() == null) ca.add("(awaiting certification by the chartered accountant)");
        else {
            ca.add("CA " + uc.caName() + ", M. No. " + uc.caMembershipNo());
            if (uc.caFirm() != null) ca.add(uc.caFirm() + (uc.caFirmRegNo() == null ? "" : ", FRN " + uc.caFirmRegNo()));
            ca.add("UDIN " + uc.caUdin());
            ca.add((uc.caPlace() == null ? "" : uc.caPlace() + ", ") + STAMP.format(uc.caCertifiedAt()));
        }
        float right = signBlock(M + colW + 20, top, colW, "Chartered accountant", ca);
        y = Math.min(left, right) - 8;
        if (uc.caName() != null) {
            for (String l : wrap(regular, 8, UcRules.CA_DECLARATION, W - 2 * M)) { text(regular, 8, MUTED, M, y, l); y -= 10; }
            text(regular, 8, MUTED, M, y, "UDIN can be verified at udin.icai.org.");
        }

        // Annexure: every expense
        newPage();
        text(bold, 13, INK, M, y, "Annexure: expenses in the period");
        y -= 18;
        float[] aw = {72, 62, 96, W - 2 * M - 72 - 62 - 96 - 80 - 34, 80, 34};
        tableHeader(new String[]{"Voucher", "Date", "Head", "Description / vendor", "Amount", "Bill"}, aw);
        for (UcRules.Line l : f.lines()) {
            if (y < 70) { newPage(); tableHeader(new String[]{"Voucher", "Date", "Head", "Description / vendor", "Amount", "Bill"}, aw); }
            String desc = l.description() + (l.vendor() == null || l.vendor().isBlank() ? "" : " · " + l.vendor());
            row(new String[]{str(l.voucherNo(), "-"), DATE.format(l.date()), l.head(), desc, money(l.amount()), l.bills() > 0 ? "Yes" : "No"}, aw, false);
        }
        row(new String[]{"", "", "", "Total", money(f.spent()), ""}, aw, true);
    }

    /** Draws a signature block and returns the baseline below its last line. */
    private float signBlock(float x, float top, float w, String title, List<String> lines) throws IOException {
        line(x, top - 46, x + w, top - 46);
        text(bold, 9.5f, INK, x, top - 60, title);
        float yy = top - 73;
        for (String l : lines) { for (String p : wrap(regular, 8.5f, l, w)) { text(regular, 8.5f, MUTED, x, yy, p); yy -= 11; } }
        return yy;
    }

    // ------------------------------------------------------------------ drawing helpers

    private void heading(String s) throws IOException {
        text(bold, 11, GREEN, M, y, s);
        y -= 6;
        line(M, y, W - M, y);
        y -= 14;
    }

    private void tableHeader(String[] cols, float[] w) throws IOException {
        fill(SHADE, M, y - 4, W - 2 * M, 16);
        float x = M;
        for (int i = 0; i < cols.length; i++) {
            if (numeric(cols, i)) textRight(bold, 8.5f, MUTED, x + w[i] - 4, y, cols[i]);
            else text(bold, 8.5f, MUTED, x + 4, y, cols[i]);
            x += w[i];
        }
        y -= 17;
    }

    private static boolean numeric(String[] cols, int i) { return "Amount".equals(cols[i]) || "Budget".equals(cols[i]) || "Utilised".equals(cols[i]) || "Entries".equals(cols[i]); }

    private void row(String[] cells, float[] w, boolean strong) throws IOException {
        PDFont f = strong ? bold : regular;
        List<List<String>> wrapped = new ArrayList<>();
        int lines = 1;
        for (int i = 0; i < cells.length; i++) {
            List<String> l = wrap(f, 8.5f, cells[i] == null ? "" : cells[i], w[i] - 8);
            wrapped.add(l);
            lines = Math.max(lines, l.size());
        }
        float x = M;
        for (int i = 0; i < cells.length; i++) {
            float yy = y;
            boolean right = i > 0 && (cells.length == 4 ? i >= 1 : i == 4);
            for (String l : wrapped.get(i)) {
                if (right) textRight(f, 8.5f, INK, x + w[i] - 4, yy, l); else text(f, 8.5f, INK, x + 4, yy, l);
                yy -= 11;
            }
            x += w[i];
        }
        y -= 11 * lines + 3;
        line(M, y + 8, W - M, y + 8);
    }

    private void newPage() throws IOException {
        if (cs != null) cs.close();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        pages.add(page);
        cs = new PDPageContentStream(doc, page);
        y = H - 50;
    }

    private byte[] finish() throws IOException {
        cs.close();
        int n = pages.size();
        for (int i = 0; i < n; i++) {
            try (PDPageContentStream c = new PDPageContentStream(doc, pages.get(i), PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs = c;
                text(regular, 7.5f, MUTED, M, 24, footer + "   ·   page " + (i + 1) + " of " + n);
                textRight(regular, 7.5f, MUTED, W - M, 24, "Generated with Yogdaan (yogdaan.co.in)");
                if (draft) watermark(c);
            }
        }
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            doc.save(out);
            doc.close();
            return out.toByteArray();
        }
    }

    private void watermark(PDPageContentStream c) throws IOException {
        PDExtendedGraphicsState gs = new PDExtendedGraphicsState();
        gs.setNonStrokingAlphaConstant(0.12f);
        c.setGraphicsStateParameters(gs);
        c.beginText();
        c.setFont(bold, 54);
        c.setNonStrokingColor(RED[0], RED[1], RED[2]);
        c.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(35), 110, 260));
        c.showText(safe(bold, "DRAFT - NOT CERTIFIED"));
        c.endText();
    }

    private void text(PDFont f, float size, float[] c, float x, float yy, String s) throws IOException {
        if (s == null || s.isEmpty()) return;
        cs.beginText();
        cs.setFont(f, size);
        cs.setNonStrokingColor(c[0], c[1], c[2]);
        cs.newLineAtOffset(x, yy);
        cs.showText(safe(f, s));
        cs.endText();
    }

    private void textRight(PDFont f, float size, float[] c, float right, float yy, String s) throws IOException {
        text(f, size, c, right - width(f, size, s), yy, s);
    }

    private float width(PDFont f, float size, String s) throws IOException { return f.getStringWidth(safe(f, s)) / 1000 * size; }

    private void fill(float[] c, float x, float yy, float w, float h) throws IOException {
        cs.setNonStrokingColor(c[0], c[1], c[2]);
        cs.addRect(x, yy, w, h);
        cs.fill();
    }

    private void line(float x1, float y1, float x2, float y2) throws IOException {
        cs.setStrokingColor(LINE[0], LINE[1], LINE[2]);
        cs.setLineWidth(0.6f);
        cs.moveTo(x1, y1);
        cs.lineTo(x2, y2);
        cs.stroke();
    }

    private List<String> wrap(PDFont f, float size, String s, float max) throws IOException {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : s.split(" ")) {
            String next = cur.isEmpty() ? word : cur + " " + word;
            if (width(f, size, next) > max && !cur.isEmpty()) { out.add(cur.toString()); cur = new StringBuilder(word); }
            else cur = new StringBuilder(next);
        }
        if (!cur.isEmpty() || out.isEmpty()) out.add(cur.toString());
        return out;
    }

    /** Characters the font cannot draw become '?', so one odd character never breaks a certificate. */
    private static String safe(PDFont f, String s) {
        StringBuilder b = new StringBuilder();
        s.codePoints().forEach(cp -> {
            String ch = new String(Character.toChars(cp));
            try { f.encode(ch); b.append(ch); } catch (Exception e) { b.append('?'); }
        });
        return b.toString();
    }

    private String money(BigDecimal v) {
        NumberFormat nf = NumberFormat.getNumberInstance(Locale.forLanguageTag("en-IN"));
        nf.setMinimumFractionDigits(2);
        nf.setMaximumFractionDigits(2);
        return (rupee ? "₹ " : "Rs ") + nf.format(v);
    }

    private static String str(Object o, String fallback) { return o == null || o.toString().isBlank() ? fallback : o.toString(); }

    static String stamp(Instant i) { return i == null ? "" : STAMP.format(i); }

    static String day(LocalDate d) { return d == null ? "" : DATE.format(d); }
}
