package org.ngoplatform.receipt.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

class TemplateDefinitionTest {
    static final TemplateDefinition.Page A5L = new TemplateDefinition.Page(595.28f, 419.53f);

    @Test
    void defaultLayoutIsValidOnBasic() {
        TemplateDefinition d = TemplateDefinition.defaults(A5L);
        TemplateDefinition.Result r = d.validate(false);
        assertThat(r.errors()).isEmpty();
        assertThat(r.warnings()).doesNotContain("QR_MISSING", "SIGNATORY_MISSING", "TAX80G_MISSING");
    }

    @Test
    void requiredFieldsQrSizeAndBounds() {
        List<TemplateDefinition.Field> f = new ArrayList<>(TemplateDefinition.defaults(A5L).fields());
        f.removeIf(x -> x.key().equals("amount"));
        f.add(TemplateDefinition.image("logo", 580, 10, 60, 60));                // runs off the right edge
        f.replaceAll(x -> x.key().equals("qrCode") ? TemplateDefinition.image("qrCode", 28, 300, 30, 30) : x);
        f.add(TemplateDefinition.image("signatory1", 10, 10, 100, 50));          // duplicate
        TemplateDefinition.Result r = new TemplateDefinition(A5L, f, false).validate(false);
        assertThat(r.errors()).anyMatch(e -> e.startsWith("REQUIRED_FIELD_MISSING: amount"))
                .anyMatch(e -> e.startsWith("OUT_OF_BOUNDS") && e.contains("logo"))
                .anyMatch(e -> e.startsWith("QR_TOO_SMALL"))
                .anyMatch(e -> e.startsWith("DUPLICATE_FIELD"));
    }

    @Test
    void conditionsOtherThanThe80gBlockNeedEnterprise() {
        List<TemplateDefinition.Field> f = new ArrayList<>(TemplateDefinition.defaults(A5L).fields());
        f.add(new TemplateDefinition.Field("staticText", 28, 60, 200, 9, "LEFT", false, null, null, "Cheque donors only", null,
                new TemplateDefinition.Condition("channel", "CHEQUE"), null, 1));
        assertThat(new TemplateDefinition(A5L, f, false).validate(false).errors()).anyMatch(e -> e.startsWith("CONDITIONS_REQUIRE_ENTERPRISE"));
        assertThat(new TemplateDefinition(A5L, f, false).validate(true).errors()).isEmpty();
    }

    @Test
    void newBackgroundKeepsFieldsOnThePage() {
        TemplateDefinition portrait = TemplateDefinition.defaults(A5L).withPage(TemplateDefinition.pageFor(1240, 1754));
        assertThat(portrait.page().height()).isBetween(841f, 843f);
        assertThat(portrait.validate(false).errors()).isEmpty();
        TemplateDefinition back = portrait.withPage(A5L);
        assertThat(back.validate(false).errors()).isEmpty();
    }

    @Test
    void customReceiptPrintsOnTheBackground() throws Exception {
        BufferedImage bg = new BufferedImage(1754, 1240, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = bg.createGraphics();
        g.setColor(new Color(255, 248, 230)); g.fillRect(0, 0, 1754, 1240);
        g.setColor(new Color(120, 60, 20)); g.fillRect(0, 0, 1754, 160);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(bg, "jpg", out);
        Images.Background b = Images.background(out.toByteArray());
        TemplateDefinition def = TemplateDefinition.defaults(TemplateDefinition.pageFor(b.width(), b.height()));
        Map<String, String> v = PdfRendererTest.values("मीरा शाह");
        byte[] pdf = new PdfRenderer().custom(def, b.jpeg(), new PdfRenderer.Data(v, null, true,
                List.of(new PdfRenderer.Signatory("Asha Patil", "Chairman", null), new PdfRenderer.Signatory("Ravi Kulkarni", "Secretary", null)),
                "http://localhost:8080/api/v1/public/receipts/verify/ABCD234567"), Map.of("tax80g", "true"));
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            String text = new PDFTextStripper().getText(doc);
            assertThat(text).contains("Receipt No. ASF/2026-27/000124", "₹ 12,500.00", "Rupees Twelve Thousand", "Chairman", "Secretary", "80G");
        }
        Files.write(Path.of("target", "sample-custom-receipt.pdf"), pdf);
        byte[] no80g = new PdfRenderer().custom(def, b.jpeg(), new PdfRenderer.Data(Map.of("receiptNumber", "ASF/2026-27/000125",
                "date", "01 Nov 2026", "donorName", "Kiran Rao", "amount", "750.00", "amountInWords", "Rupees Seven Hundred Fifty Only"),
                null, false, List.of(), null), Map.of("tax80g", "false"));
        try (PDDocument doc = Loader.loadPDF(no80g)) {
            assertThat(new PDFTextStripper().getText(doc)).contains("Kiran Rao").doesNotContain("80G");
        }
    }
}
