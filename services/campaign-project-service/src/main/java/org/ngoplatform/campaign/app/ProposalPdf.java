package org.ngoplatform.campaign.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * CSR project proposal PDF (A4 portrait): organisation, key facts, the need / objectives / activities / outcomes /
 * monitoring, budget with spending so far, the campaign's progress, organisation details, the compliance-pack link
 * and the contact. Drafts carry a "DRAFT" mark.
 */
public final class ProposalPdf {
    static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneId.of("Asia/Kolkata"));
    private static final float W = PDRectangle.A4.getWidth(), H = PDRectangle.A4.getHeight(), M = 46, BOTTOM = 60;
    private static final float[] GREEN = {8 / 255f, 122 / 255f, 75 / 255f}, INK = {20 / 255f, 33 / 255f, 61 / 255f},
            MUTED = {91 / 255f, 100 / 255f, 120 / 255f}, LINE = {0.82f, 0.85f, 0.82f}, SHADE = {0.95f, 0.97f, 0.95f},
            SOFT = {0.91f, 0.96f, 0.93f}, RED = {0.75f, 0.15f, 0.15f}, WHITE = {1, 1, 1};
    static final String DEFAULT_MONITORING = "Progress reports to the company every quarter with photographs and beneficiary numbers, "
            + "and a utilisation certificate certified by a chartered accountant at the end of the grant period, prepared from the "
            + "project's own accounts (every expense approved and kept with its bill).";

    private final PDDocument doc = new PDDocument();
    private PDFont regular, bold;
    private boolean rupee;
    private PDPageContentStream cs;
    private float y;
    private final List<PDPage> pages = new ArrayList<>();
    private final boolean draft;
    private final String footer;

    private ProposalPdf(boolean draft, String footer) { this.draft = draft; this.footer = footer; }

    public static byte[] render(ProposalService.Proposal p, String campaign, Map<String, Object> ngo, ProposalService.Progress progress, boolean draft) {
        String ngoName = str(ngo.get("legalName"), str(ngo.get("displayName"), "Organisation"));
        ProposalPdf pdf = new ProposalPdf(draft, (draft ? "DRAFT   ·   " : "") + p.number() + " · " + ngoName);
        try {
            pdf.fonts();
            pdf.write(p, campaign, ngo, ngoName, progress);
            return pdf.finish();
        } catch (IOException e) {
            throw new IllegalStateException("Proposal PDF failed", e);
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

    private void write(ProposalService.Proposal p, String campaign, Map<String, Object> ngo, String ngoName, ProposalService.Progress g)
            throws IOException {
        newPage();
        // Letterhead
        fill(GREEN, 0, H - 86, W, 86);
        text(bold, 17, WHITE, M, H - 38, ngoName);
        List<String> ids = new ArrayList<>();
        if (ngo.get("registrationNumber") != null) ids.add("Reg. No. " + ngo.get("registrationNumber"));
        if (ngo.get("pan") != null) ids.add("PAN " + ngo.get("pan"));
        if (ngo.get("tax80gNumber") != null) ids.add("80G " + ngo.get("tax80gNumber"));
        text(regular, 9, WHITE, M, H - 56, str(ngo.get("address"), ""));
        text(regular, 9, WHITE, M, H - 70, String.join("   ·   ", ids));
        y = H - 118;
        text(bold, 15, INK, M, y, "CSR PROJECT PROPOSAL");
        textRight(bold, 10, INK, W - M, y + 2, p.number());
        y -= 16;
        textRight(regular, 9, MUTED, W - M, y, p.sentOn() == null ? "Draft, " + DATE.format(LocalDate.now(ZoneId.of("Asia/Kolkata")))
                : "Submitted on " + DATE.format(p.sentOn()));
        y -= 8;
        for (String l : wrap(bold, 13, p.title(), W - 2 * M)) { y -= 16; text(bold, 13, GREEN, M, y, l); }
        y -= 18;
        text(regular, 9.5f, MUTED, M, y, "To");
        String to = p.funderName() + (p.funderContact() == null ? "" : "  ·  Attn. " + p.funderContact());
        for (String l : wrap(bold, 10, to, W - 2 * M - 120)) { text(bold, 10, INK, M + 120, y, l); y -= 13; }
        y -= 6;

        // Key facts box
        // The share of the budget only makes sense when the ask fits inside it.
        BigDecimal share = g.budgetTotal().signum() > 0 && p.askAmount().compareTo(g.budgetTotal()) <= 0
                ? p.askAmount().multiply(BigDecimal.valueOf(100)).divide(g.budgetTotal(), 0, RoundingMode.HALF_UP) : null;
        String[][] facts = {
                {"Amount requested", money(p.askAmount()) + (share == null ? "" : "  (" + share + "% of the project budget of " + money(g.budgetTotal()) + ")")},
                {"Schedule VII area", ProposalRules.SCHEDULE_VII.getOrDefault(p.scheduleVii(), p.scheduleVii())},
                {"Duration", p.durationMonths() + (p.durationMonths() == 1 ? " month" : " months")},
                {"Location", p.location() == null ? "-" : p.location()},
                {"People reached", p.beneficiaries() == null ? "-" : UcRules.group(BigDecimal.valueOf(p.beneficiaries()), false)},
                {"Campaign", campaign}};
        float boxTop = y + 4;
        float h = 10;
        for (String[] f : facts) h += 14 * wrap(f == facts[0] ? bold : regular, 9.5f, f[1], W - 2 * M - 140).size() + 2;
        fill(SOFT, M - 6, boxTop - h, W - 2 * M + 12, h);
        y -= 8;
        for (String[] f : facts) {
            text(regular, 9, MUTED, M + 4, y, f[0]);
            PDFont font = f == facts[0] ? bold : regular;
            for (String l : wrap(font, 9.5f, f[1], W - 2 * M - 140)) { text(font, 9.5f, INK, M + 136, y, l); y -= 14; }
            y -= 2;
        }
        y -= 14;

        // The proposal itself
        section("1. The need", p.need());
        section("2. Objectives", p.objectives());
        section("3. Activities", p.activities());
        section("4. Expected outcomes", p.outcomes());
        section("5. Monitoring and reporting", p.monitoring() == null || p.monitoring().isBlank() ? DEFAULT_MONITORING : p.monitoring());

        // Budget
        ensure(70);
        heading("6. Budget");
        if (g.budget().isEmpty()) {
            paragraph("A detailed budget will be shared on request.", regular, 9.5f, MUTED);
        } else {
            float[] cw = {W - 2 * M - 2 * 110, 110, 110};
            tableHeader(new String[]{"Budget head", "Budget", "Spent so far"}, cw);
            for (ProposalService.BudgetLine b : g.budget()) {
                ensure(16);
                row(new String[]{b.head(), money(b.planned()), money(b.spent())}, cw, false);
            }
            // Approved expenses not booked to a budget head, so the total matches "Progress so far".
            BigDecimal onHeads = g.budget().stream().map(ProposalService.BudgetLine::spent).reduce(BigDecimal.ZERO, BigDecimal::add);
            if (g.spent().compareTo(onHeads) > 0) row(new String[]{"Other expenses (outside the budget heads)", "-", money(g.spent().subtract(onHeads))}, cw, false);
            row(new String[]{"Total", money(g.budgetTotal()), money(g.spent().max(onHeads))}, cw, true);
        }
        y -= 10;

        // Track record of this campaign
        ensure(70);
        heading("7. Progress so far");
        List<String> done = new ArrayList<>();
        done.add("Running since " + DATE.format(g.startDate()) + (g.endDate() == null ? "" : ", planned until " + DATE.format(g.endDate())) + ".");
        done.add("Raised " + money(g.raised()) + " of the " + money(g.goal()) + " goal from " + g.donations()
                + (g.donations() == 1 ? " donation" : " donations") + ".");
        done.add("Spent " + money(g.spent()) + " in " + g.approvedExpenses() + (g.approvedExpenses() == 1 ? " approved expense" : " approved expenses")
                + ", each recorded with its voucher in the organisation's books.");
        for (String d : done) paragraph("•  " + d, regular, 9.5f, INK);
        y -= 8;

        // About the organisation and the compliance documents
        ensure(90);
        heading("8. About the organisation");
        List<String[]> about = new ArrayList<>();
        about.add(new String[]{"Legal name", ngoName});
        if (ngo.get("registrationNumber") != null) about.add(new String[]{"Registration no.", str(ngo.get("registrationNumber"), "")});
        if (ngo.get("pan") != null) about.add(new String[]{"PAN", str(ngo.get("pan"), "")});
        if (ngo.get("tax80gNumber") != null) {
            about.add(new String[]{"80G", ngo.get("tax80gNumber") + (ngo.get("tax80gValidUntil") == null ? "" : " (valid until " + day(ngo.get("tax80gValidUntil")) + ")")});
        }
        if (ngo.get("address") != null) about.add(new String[]{"Address", str(ngo.get("address"), "")});
        if (ngo.get("officialEmail") != null) about.add(new String[]{"E-mail", str(ngo.get("officialEmail"), "")});
        for (String[] a : about) {
            ensure(14);
            text(regular, 9, MUTED, M, y, a[0]);
            for (String l : wrap(regular, 9.5f, a[1], W - 2 * M - 140)) { text(regular, 9.5f, INK, M + 136, y, l); y -= 13; }
        }
        y -= 6;
        if (p.packUrl() != null) {
            ensure(54);
            text(bold, 9.5f, INK, M, y, p.packTitle() == null ? "Compliance documents" : "Compliance documents (" + p.packTitle() + ")");
            y -= 13;
            paragraph("Registration, PAN, 12A/80G, CSR-1 and audited accounts can be viewed and downloaded at:", regular, 9, MUTED);
            paragraph(p.packUrl(), regular, 9, GREEN);
            if (p.packExpiresAt() != null) paragraph("Link valid until " + STAMP.format(p.packExpiresAt()) + ".", regular, 8.5f, MUTED);
            y -= 6;
        }

        // Contact
        ensure(60);
        heading("Contact");
        paragraph(str(p.contactName(), ngoName), bold, 9.5f, INK);
        if (p.contactDetails() != null) paragraph(p.contactDetails(), regular, 9.5f, INK);
        y -= 4;
        paragraph("We would be glad to present the project, host a site visit and share any further information you need.", regular, 9, MUTED);
    }

    private void section(String title, String body) throws IOException {
        ensure(60);
        heading(title);
        if (body == null || body.isBlank()) { paragraph("-", regular, 9.5f, MUTED); y -= 8; return; }
        for (String para : body.replace("\r\n", "\n").split("\n")) {
            if (para.isBlank()) { y -= 5; continue; }
            paragraph(para.strip(), regular, 9.5f, INK);
        }
        y -= 10;
    }

    private void paragraph(String s, PDFont f, float size, float[] c) throws IOException {
        for (String l : wrap(f, size, s, W - 2 * M)) {
            ensure(size + 4);
            text(f, size, c, M, y, l);
            y -= size + 3.5f;
        }
    }

    private void ensure(float space) throws IOException { if (y - space < BOTTOM) newPage(); }

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
            if (i > 0) textRight(bold, 8.5f, MUTED, x + w[i] - 4, y, cols[i]); else text(bold, 8.5f, MUTED, x + 4, y, cols[i]);
            x += w[i];
        }
        y -= 17;
    }

    private void row(String[] cells, float[] w, boolean strong) throws IOException {
        PDFont f = strong ? bold : regular;
        List<List<String>> wrapped = new ArrayList<>();
        int lines = 1;
        for (int i = 0; i < cells.length; i++) {
            List<String> l = wrap(f, 9, cells[i] == null ? "" : cells[i], w[i] - 8);
            wrapped.add(l);
            lines = Math.max(lines, l.size());
        }
        float x = M;
        for (int i = 0; i < cells.length; i++) {
            float yy = y;
            for (String l : wrapped.get(i)) {
                if (i > 0) textRight(f, 9, INK, x + w[i] - 4, yy, l); else text(f, 9, INK, x + 4, yy, l);
                yy -= 11.5f;
            }
            x += w[i];
        }
        y -= 11.5f * lines + 3;
        line(M, y + 8, W - M, y + 8);
    }

    private void newPage() throws IOException {
        if (cs != null) cs.close();
        PDPage page = new PDPage(PDRectangle.A4);
        doc.addPage(page);
        pages.add(page);
        cs = new PDPageContentStream(doc, page);
        y = H - 54;
    }

    private byte[] finish() throws IOException {
        cs.close();
        int n = pages.size();
        for (int i = 0; i < n; i++) {
            try (PDPageContentStream c = new PDPageContentStream(doc, pages.get(i), PDPageContentStream.AppendMode.APPEND, true, true)) {
                cs = c;
                text(regular, 7.5f, MUTED, M, 26, footer + "   ·   page " + (i + 1) + " of " + n);
                textRight(regular, 7.5f, MUTED, W - M, 26, "Generated with Yogdaan (yogdaan.co.in)");
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
        gs.setNonStrokingAlphaConstant(0.10f);
        c.setGraphicsStateParameters(gs);
        c.beginText();
        c.setFont(bold, 90);
        c.setNonStrokingColor(RED[0], RED[1], RED[2]);
        c.setTextMatrix(Matrix.getRotateInstance(Math.toRadians(35), 170, 300));
        c.showText(safe(bold, "DRAFT"));
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

    /** Word wrap; a single word longer than the line (e.g. a link) is broken by characters. */
    private List<String> wrap(PDFont f, float size, String s, float max) throws IOException {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (String word : s.split(" ")) {
            while (width(f, size, word) > max && word.length() > 1) {
                if (!cur.isEmpty()) { out.add(cur.toString()); cur = new StringBuilder(); }
                int cut = word.length() - 1;
                while (cut > 1 && width(f, size, word.substring(0, cut)) > max) cut--;
                out.add(word.substring(0, cut));
                word = word.substring(cut);
            }
            String next = cur.isEmpty() ? word : cur + " " + word;
            if (width(f, size, next) > max && !cur.isEmpty()) { out.add(cur.toString()); cur = new StringBuilder(word); }
            else cur = new StringBuilder(next);
        }
        if (!cur.isEmpty() || out.isEmpty()) out.add(cur.toString());
        return out;
    }

    private static String safe(PDFont f, String s) {
        StringBuilder b = new StringBuilder();
        s.codePoints().forEach(cp -> {
            String ch = new String(Character.toChars(cp));
            try { f.encode(ch); b.append(ch); } catch (Exception e) { b.append('?'); }
        });
        return b.toString();
    }

    private String money(BigDecimal v) { return (rupee ? "₹ " : "Rs ") + UcRules.group(v, v.stripTrailingZeros().scale() > 0); }

    private static String day(Object iso) {
        try { return DATE.format(LocalDate.parse(iso.toString().substring(0, 10))); } catch (RuntimeException e) { return iso.toString(); }
    }

    private static String str(Object o, String fallback) { return o == null || o.toString().isBlank() ? fallback : o.toString(); }
}
