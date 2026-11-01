package org.ngoplatform.campaign.app;

import java.util.ArrayList;
import java.util.List;
import org.ngoplatform.commons.error.ApiException;

/** Minimal RFC 4180 reader/writer (quoted fields, doubled quotes, CRLF or LF). Also accepts ';' files from Excel locales. */
public final class Csv {
    private Csv() {}

    public static List<List<String>> parse(String text) {
        char sep = detect(text);
        List<List<String>> rows = new ArrayList<>();
        List<String> row = new ArrayList<>();
        StringBuilder f = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length() && text.charAt(i + 1) == '"') { f.append('"'); i++; }
                    else quoted = false;
                } else f.append(c);
            } else if (c == '"') {
                quoted = true;
            } else if (c == sep) {
                row.add(f.toString()); f.setLength(0);
            } else if (c == '\n' || c == '\r') {
                if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') i++;
                row.add(f.toString()); f.setLength(0);
                rows.add(row); row = new ArrayList<>();
            } else {
                f.append(c);
            }
            if (f.length() > 2000) throw ApiException.badRequest("CSV_FIELD_TOO_LONG", "A cell in the file is too long");
        }
        if (quoted) throw ApiException.badRequest("CSV_INVALID", "The file has an unclosed quote");
        if (f.length() > 0 || !row.isEmpty()) { row.add(f.toString()); rows.add(row); }
        return rows;
    }

    private static char detect(String text) {
        int nl = text.indexOf('\n');
        String first = nl < 0 ? text : text.substring(0, nl);
        return first.chars().filter(ch -> ch == ';').count() > first.chars().filter(ch -> ch == ',').count() ? ';' : ',';
    }

    /** One CSV field; leading = + - @ are prefixed with ' so spreadsheets do not run them as formulas. */
    public static String field(String v) {
        if (v == null) return "";
        String s = v;
        if (!s.isEmpty() && "=+-@".indexOf(s.charAt(0)) >= 0) s = "'" + s;
        return s.matches(".*[\",\\r\\n;].*") ? "\"" + s.replace("\"", "\"\"") + "\"" : s;
    }
}
