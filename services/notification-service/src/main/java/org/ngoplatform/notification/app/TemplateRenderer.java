package org.ngoplatform.notification.app;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Minimal, logic-free renderer: replaces {{name}} with the variable value. Unknown placeholders render as empty
 * text (never as raw "{{...}}"), values are inserted literally (templates are plain text, so no markup injection),
 * and blank lines left by empty values collapse.
 */
public final class TemplateRenderer {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\{\\{\\s*([A-Za-z][A-Za-z0-9_]*)\\s*}}");
    private static final int MAX_VALUE = 1000;

    private TemplateRenderer() {}

    public static String render(String template, Map<String, ?> vars) {
        if (template == null) return null;
        Matcher m = PLACEHOLDER.matcher(template);
        StringBuilder out = new StringBuilder();
        while (m.find()) {
            Object v = vars.get(m.group(1));
            String s = v == null ? "" : String.valueOf(v).strip();
            if (s.length() > MAX_VALUE) s = s.substring(0, MAX_VALUE) + "…";
            m.appendReplacement(out, Matcher.quoteReplacement(s));
        }
        m.appendTail(out);
        return out.toString().replaceAll("\\n{3,}", "\n\n").strip();
    }

    /** Placeholder names used by a template (for admin validation and preview forms). */
    public static Set<String> placeholders(String template) {
        Set<String> names = new LinkedHashSet<>();
        if (template == null) return names;
        Matcher m = PLACEHOLDER.matcher(template);
        while (m.find()) names.add(m.group(1));
        return names;
    }
}
