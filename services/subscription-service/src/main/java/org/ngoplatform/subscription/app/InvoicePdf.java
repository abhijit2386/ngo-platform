package org.ngoplatform.subscription.app;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.ngoplatform.subscription.domain.Records;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** SUB-030: subscription tax invoice PDF (seller GSTIN comes from configuration). */
@Component
public class InvoicePdf {
    private static final DateTimeFormatter D = DateTimeFormatter.ofPattern("dd MMM yyyy").withZone(SubscriptionService.IST);
    private final ObjectMapper mapper;
    private final String seller;
    private final String sellerGstin;

    public InvoicePdf(ObjectMapper mapper, @Value("${ngo.billing.seller-name}") String seller,
                      @Value("${ngo.billing.seller-gstin}") String sellerGstin) {
        this.mapper = mapper; this.seller = seller; this.sellerGstin = sellerGstin;
    }

    public byte[] render(Records.Invoice inv, String ngoName) {
        try (PDDocument doc = new PDDocument(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);
            var bold = new PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD);
            var regular = new PDType1Font(Standard14Fonts.FontName.HELVETICA);
            List<Map<String, Object>> lines = mapper.readValue(inv.getLines(), new TypeReference<>() {});
            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = 790;
                y = text(cs, bold, 18, 50, y, "TAX INVOICE");
                y = text(cs, regular, 10, 50, y - 6, seller + "   GSTIN: " + sellerGstin);
                y = text(cs, regular, 10, 50, y - 14, "Invoice no: " + inv.getNumber());
                y = text(cs, regular, 10, 50, y, "Date: " + D.format(inv.getIssuedAt()));
                y = text(cs, bold, 11, 50, y - 14, "Billed to");
                y = text(cs, regular, 10, 50, y, safe(inv.getBillToName() != null ? inv.getBillToName() : ngoName));
                if (inv.getBillToGstin() != null) y = text(cs, regular, 10, 50, y, "GSTIN: " + inv.getBillToGstin());
                if (inv.getPeriodStart() != null) {
                    y = text(cs, regular, 10, 50, y, "Service period: " + D.format(inv.getPeriodStart()) + " - " + D.format(inv.getPeriodEnd()));
                }
                y -= 20;
                text(cs, bold, 10, 50, y, "Description");
                y = text(cs, bold, 10, 450, y, "Amount (INR)");
                for (Map<String, Object> l : lines) {
                    text(cs, regular, 10, 50, y, safe(String.valueOf(l.get("description"))));
                    y = text(cs, regular, 10, 450, y, String.valueOf(l.get("amount")));
                }
                y -= 10;
                text(cs, regular, 10, 300, y, "Taxable value");
                y = text(cs, regular, 10, 450, y, inv.getTaxableAmount().toPlainString());
                text(cs, regular, 10, 300, y, "GST (included)");
                y = text(cs, regular, 10, 450, y, inv.getGstAmount().toPlainString());
                text(cs, bold, 11, 300, y, "Total");
                y = text(cs, bold, 11, 450, y, inv.getTotal().toPlainString());
                text(cs, regular, 8, 50, 60, "Prices include GST. Platform charges no commission on donations. "
                        + "This invoice covers SaaS subscription fees only.");
            }
            doc.save(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new IllegalStateException("Invoice rendering failed", e);
        }
    }

    /** Standard-14 fonts are WinAnsi only; replace characters they cannot encode. */
    static String safe(String s) {
        if (s == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : s.toCharArray()) b.append(c < 0x20 || c > 0xFF ? '?' : c);
        return b.toString();
    }

    private static float text(PDPageContentStream cs, PDType1Font font, float size, float x, float y, String s) throws IOException {
        cs.beginText();
        cs.setFont(font, size);
        cs.newLineAtOffset(x, y);
        cs.showText(s);
        cs.endText();
        return y - size - 4;
    }
}
