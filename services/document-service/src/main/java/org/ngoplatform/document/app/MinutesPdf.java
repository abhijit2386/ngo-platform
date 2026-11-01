package org.ngoplatform.document.app;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDFont;
import org.apache.pdfbox.pdmodel.font.PDType0Font;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;

/**
 * Approved minutes as an A4 PDF for the NGO's minute book: meeting details, attendance and quorum, agenda, minutes,
 * numbered resolutions, action items, who recorded / approved, and signature lines for the chairperson and secretary.
 * Lines with Devanagari text use a Devanagari font when the server has one (Mangal / Nirmala / Noto).
 */
public final class MinutesPdf {
    static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy, h:mm a", Locale.ENGLISH).withZone(INDIA);
    static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(INDIA);
    private static final float W = PDRectangle.A4.getWidth(), H = PDRectangle.A4.getHeight(), M = 50;
    private static final float[] INK = {0.08f, 0.13f, 0.24f}, MUTED = {0.36f, 0.39f, 0.47f}, GREEN = {0.03f, 0.48f, 0.29f};

    private final PDDocument doc = new PDDocument();
    private final List<PDPage> pages = new ArrayList<>();
    private PDPageContentStream cs;
    private float y;
    private PDFont regular, bold, indic;
    private final Map<PDFont, Map<Character, Boolean>> glyphs = new HashMap<>();

    public static byte[] render(MeetingService.Detail d, String ngoName, String recordedBy, String approvedBy, Instant approvedAt) {
        MinutesPdf p = new MinutesPdf();
        try {
            p.fonts();
            p.write(d, ngoName, recordedBy, approvedBy, approvedAt);
            return p.finish(ngoName + " · " + d.meeting().title());
        } catch (IOException e) {
            throw new IllegalStateException("Minutes PDF failed", e);
        }
    }

    static String typeLabel(String t) {
        return switch (t) {
            case "AGM" -> "Annual General Meeting";
            case "EGM" -> "Extraordinary General Meeting";
            case "BOARD" -> "Board / Governing Body Meeting";
            case "COMMITTEE" -> "Committee Meeting";
            case "STAFF" -> "Staff Meeting";
            default -> "Meeting";
        };
    }

    private void fonts() throws IOException {
        String[][] latin = {{"C:/Windows/Fonts/arial.ttf", "C:/Windows/Fonts/arialbd.ttf"},
                {"/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf", "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf"}};
        for (String[] f : latin) {
            if (new File(f[0]).isFile()) {
                regular = PDType0Font.load(doc, new File(f[0]));
                bold = new File(f[1]).isFile() ? PDType0Font.load(doc, new File(f[1])) : regular;
                break;
            }
        }
        if (regular == null) {
            regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
        }
        for (String f : new String[]{"C:/Windows/Fonts/mangal.ttf", "/usr/share/fonts/truetype/noto/NotoSansDevanagari-Regular.ttf"}) {
            if (new File(f).isFile()) { indic = PDType0Font.load(doc, new File(f)); break; }
        }
    }

    private void write(MeetingService.Detail d, String ngoName, String recordedBy, String approvedBy, Instant approvedAt) throws IOException {
        MeetingService.Meeting m = d.meeting();
        newPage();
        text(bold, 11, GREEN, M, y, ngoName == null ? "" : ngoName);
        y -= 22;
        text(bold, 16, INK, M, y, "Minutes of the " + typeLabel(m.type()));
        y -= 18;
        para(regular, 11, INK, m.title());
        y -= 4;
        kv("Date and time", WHEN.format(m.startsAt()));
        String where = switch (m.mode()) {
            case "ONLINE" -> "Online" + (m.onlineLink() == null ? "" : " (" + m.onlineLink() + ")");
            case "HYBRID" -> (m.venue() == null ? "" : m.venue() + " and ") + "online";
            default -> m.venue() == null ? "" : m.venue();
        };
        if (!where.isBlank()) kv("Place", where);
        if (m.chairperson() != null) kv("Chairperson", m.chairperson());
        String present = d.attendees().stream().filter(a -> Boolean.TRUE.equals(a.present())).map(MinutesPdf::person).collect(Collectors.joining(", "));
        String absent = d.attendees().stream().filter(a -> !Boolean.TRUE.equals(a.present())).map(MinutesPdf::person).collect(Collectors.joining(", "));
        heading("Attendance");
        para(regular, 10, INK, "Present (" + d.presentCount() + "): " + (present.isEmpty() ? "-" : present));
        if (!absent.isEmpty()) para(regular, 10, MUTED, "Absent / leave of absence: " + absent);
        if (m.quorumRequired() != null) {
            para(bold, 10, d.quorumMet() ? GREEN : new float[]{0.75f, 0.15f, 0.15f}, d.quorumMet()
                    ? "Quorum present (" + d.presentCount() + " of " + m.quorumRequired() + " required)."
                    : "Quorum NOT present (" + d.presentCount() + " of " + m.quorumRequired() + " required).");
        }
        if (m.agenda() != null && !m.agenda().isBlank()) {
            heading("Agenda");
            for (String line : m.agenda().split("\\R")) if (!line.isBlank()) para(regular, 10, INK, line.strip());
        }
        heading("Proceedings");
        for (String p : m.minutes() == null ? new String[0] : m.minutes().split("\\R\\s*\\R")) {
            para(regular, 10, INK, p.replaceAll("\\R", " ").strip());
            y -= 4;
        }
        if (!d.resolutions().isEmpty()) {
            heading("Resolutions");
            for (MeetingService.Resolution r : d.resolutions()) {
                need(50);
                para(bold, 10, INK, (r.number() == null ? "" : r.number() + "  ") + "(" + r.result().charAt(0) + r.result().substring(1).toLowerCase() + ")");
                para(regular, 10, INK, "RESOLVED THAT " + r.text());
                List<String> by = new ArrayList<>();
                if (r.proposedBy() != null) by.add("Proposed by " + r.proposedBy());
                if (r.secondedBy() != null) by.add("seconded by " + r.secondedBy());
                if (r.votesFor() != null || r.votesAgainst() != null) {
                    by.add("votes for " + nz(r.votesFor()) + ", against " + nz(r.votesAgainst()) + (r.abstained() == null ? "" : ", abstained " + r.abstained()));
                }
                if (!by.isEmpty()) para(regular, 9, MUTED, String.join("; ", by) + ".");
                y -= 6;
            }
        }
        if (!d.actions().isEmpty()) {
            heading("Action items");
            for (MeetingService.Action a : d.actions()) {
                para(regular, 10, INK, "- " + a.text() + (a.ownerName() == null ? "" : "  (" + a.ownerName() + ")")
                        + (a.dueDate() == null ? "" : "  due " + DAY.format(a.dueDate().atStartOfDay(INDIA))));
            }
        }
        y -= 10;
        para(regular, 9, MUTED, "Minutes recorded by " + (recordedBy == null ? "-" : recordedBy) + "; approved by "
                + (approvedBy == null ? "-" : approvedBy) + " on " + DAY.format(approvedAt) + " in Yogdaan.");
        need(90);
        y -= 60;
        float sw = (W - 2 * M - 60) / 2;
        String[] sig = {"Chairperson", "Secretary"};
        for (int i = 0; i < 2; i++) {
            float x = M + i * (sw + 60);
            cs.setStrokingColor(0.6f, 0.6f, 0.6f);
            cs.moveTo(x, y);
            cs.lineTo(x + sw, y);
            cs.stroke();
            text(regular, 9, MUTED, x, y - 12, sig[i]);
        }
    }

    private static String person(MeetingService.Attendee a) { return a.designation() == null ? a.name() : a.name() + " (" + a.designation() + ")"; }

    private static String nz(Integer i) { return i == null ? "0" : i.toString(); }

    // ---------------- layout

    private void heading(String t) throws IOException {
        need(40);
        y -= 8;
        text(bold, 12, GREEN, M, y, t);
        y -= 16;
    }

    private void kv(String k, String v) throws IOException {
        need(16);
        text(bold, 10, MUTED, M, y, k);
        List<String> lines = wrap(fontFor(v, false), 10, v, W - 2 * M - 110);
        for (String l : lines) {
            text(fontFor(l, false), 10, INK, M + 110, y, l);
            y -= 14;
        }
    }

    private void para(PDFont font, float size, float[] color, String s) throws IOException {
        for (String l : wrap(fontFor(s, font == bold), size, s, W - 2 * M)) {
            need(size + 6);
            text(fontFor(l, font == bold), size, color, M, y, l);
            y -= size + 4;
        }
    }

    private PDFont fontFor(String s, boolean wantBold) {
        if (indic != null && s.chars().anyMatch(c -> c >= 0x0900 && c <= 0x097F)) return indic;
        return wantBold ? bold : regular;
    }

    private List<String> wrap(PDFont font, float size, String s, float width) throws IOException {
        List<String> out = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        for (String word : s.split("\\s+")) {
            String candidate = line.isEmpty() ? word : line + " " + word;
            if (width(font, size, candidate) <= width || line.isEmpty()) {
                line.setLength(0);
                line.append(candidate);
            } else {
                out.add(line.toString());
                line.setLength(0);
                line.append(word);
            }
        }
        if (!line.isEmpty()) out.add(line.toString());
        return out;
    }

    private void need(float h) throws IOException { if (y - h < M + 20) newPage(); }

    private void newPage() throws IOException {
        if (cs != null) cs.close();
        PDPage p = new PDPage(PDRectangle.A4);
        doc.addPage(p);
        pages.add(p);
        cs = new PDPageContentStream(doc, p);
        y = H - M;
    }

    private byte[] finish(String footer) throws IOException {
        if (cs != null) cs.close();
        for (int i = 0; i < pages.size(); i++) {
            try (PDPageContentStream f = new PDPageContentStream(doc, pages.get(i), PDPageContentStream.AppendMode.APPEND, true)) {
                cs = f;
                text(regular, 8, MUTED, M, 25, footer);
                String n = "Page " + (i + 1) + " of " + pages.size();
                text(regular, 8, MUTED, W - M - width(regular, 8, n), 25, n);
            }
        }
        PDDocument d = doc;
        try (d; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            d.save(out);
            return out.toByteArray();
        }
    }

    private void text(PDFont font, float size, float[] c, float x, float yy, String s) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.setNonStrokingColor(c[0], c[1], c[2]);
        cs.newLineAtOffset(x, yy);
        cs.showText(safe(font, s));
        cs.endText();
    }

    private float width(PDFont f, float size, String s) throws IOException { return f.getStringWidth(safe(f, s)) / 1000f * size; }

    private String safe(PDFont font, String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char ch : s.toCharArray()) {
            if (Character.isISOControl(ch)) { b.append(' '); continue; }
            b.append(supports(font, ch) ? ch : '?');
        }
        return b.toString();
    }

    private boolean supports(PDFont font, char ch) {
        return glyphs.computeIfAbsent(font, f -> new HashMap<>()).computeIfAbsent(ch, c -> {
            try {
                return font.encode(String.valueOf(c)).length > 0;
            } catch (IllegalArgumentException | IOException e) {
                return false;
            }
        });
    }
}
