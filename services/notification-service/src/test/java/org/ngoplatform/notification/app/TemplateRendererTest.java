package org.ngoplatform.notification.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TemplateRendererTest {

    @Test
    void replacesPlaceholders() {
        assertThat(TemplateRenderer.render("Hello {{name}}, welcome to {{ngoName}}.", Map.of("name", "Asha", "ngoName", "Asha Seva")))
                .isEqualTo("Hello Asha, welcome to Asha Seva.");
    }

    @Test
    void toleratesWhitespaceInsideBraces() {
        assertThat(TemplateRenderer.render("{{ name }}", Map.of("name", "Ravi"))).isEqualTo("Ravi");
    }

    @Test
    void unknownPlaceholderRendersEmptyNotRaw() {
        assertThat(TemplateRenderer.render("Hi {{missing}}!", Map.of())).isEqualTo("Hi !");
    }

    @Test
    void nullValueRendersEmpty() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("x", null);
        assertThat(TemplateRenderer.render("[{{x}}]", vars)).isEqualTo("[]");
    }

    @Test
    void valuesAreInsertedLiterallyIncludingRegexSpecials() {
        assertThat(TemplateRenderer.render("Amount {{a}}", Map.of("a", "$100 \\ {{b}}"))).isEqualTo("Amount $100 \\ {{b}}");
    }

    @Test
    void valuesAreNotRenderedRecursively() {
        assertThat(TemplateRenderer.render("{{a}}", Map.of("a", "{{b}}", "b", "secret"))).isEqualTo("{{b}}");
    }

    @Test
    void collapsesBlankLinesLeftByEmptyValues() {
        assertThat(TemplateRenderer.render("Line1\n\n{{message}}\n\nLine2", Map.of("message", "")))
                .isEqualTo("Line1\n\nLine2");
    }

    @Test
    void trimsValues() {
        assertThat(TemplateRenderer.render("<{{v}}>", Map.of("v", "  padded  "))).isEqualTo("<padded>");
    }

    @Test
    void clipsVeryLongValues() {
        String rendered = TemplateRenderer.render("{{v}}", Map.of("v", "x".repeat(5000)));
        assertThat(rendered).hasSize(1001).endsWith("…");
    }

    @Test
    void nullTemplateRendersNull() {
        assertThat(TemplateRenderer.render(null, Map.of())).isNull();
    }

    @Test
    void numbersAreRenderedAsText() {
        assertThat(TemplateRenderer.render("{{n}} days", Map.of("n", 7))).isEqualTo("7 days");
    }

    @Test
    void devanagariTextIsPreserved() {
        assertThat(TemplateRenderer.render("नमस्कार {{name}}", Map.of("name", "आशा"))).isEqualTo("नमस्कार आशा");
    }

    @Test
    void listsPlaceholdersInOrderWithoutDuplicates() {
        assertThat(TemplateRenderer.placeholders("{{a}} {{b}} {{a}} {{ c }}")).containsExactly("a", "b", "c");
    }

    @Test
    void placeholdersOfNullIsEmpty() {
        assertThat(TemplateRenderer.placeholders(null)).isEmpty();
    }

    @Test
    void ignoresMalformedPlaceholders() {
        assertThat(TemplateRenderer.placeholders("{{1abc}} {{}} {single}")).isEmpty();
        assertThat(TemplateRenderer.render("{single}", Map.of("single", "x"))).isEqualTo("{single}");
    }
}
