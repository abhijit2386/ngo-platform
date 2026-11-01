package org.ngoplatform.receipt.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.ngoplatform.commons.error.ApiException;

class ImagesTest {
    static byte[] image(int w, int h, String format) throws Exception {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(10, 20, 120));
        g.fillRect(w / 4, h / 3, w / 2, Math.max(2, h / 10)); // the "ink"
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    static String code(Runnable r) {
        try { r.run(); return "none"; } catch (ApiException e) { return e.code(); }
    }

    @Test
    void onlyPngAndJpegAreAccepted() throws Exception {
        assertThat(code(() -> Images.signature("%PDF-1.7 fake".getBytes()))).isEqualTo("IMAGE_TYPE_NOT_ALLOWED");
        assertThat(code(() -> {
            try { Images.signature(image(300, 120, "gif")); } catch (ApiException e) { throw e; } catch (Exception e) { throw new RuntimeException(e); }
        })).isEqualTo("IMAGE_TYPE_NOT_ALLOWED");
        assertThat(Images.kind(image(300, 120, "png"))).isEqualTo(Images.Kind.PNG);
        assertThat(Images.kind(image(300, 120, "jpg"))).isEqualTo(Images.Kind.JPEG);
        assertThat(code(() -> Images.signature(new byte[2_000_000]))).isEqualTo("FILE_TOO_LARGE");
    }

    @Test
    void signaturePaperBecomesTransparentAndSizeIsCapped() throws Exception {
        byte[] png = Images.signature(image(2400, 900, "jpg"));
        BufferedImage out = ImageIO.read(new ByteArrayInputStream(png));
        assertThat(out.getWidth()).isLessThanOrEqualTo(Images.SIGNATURE_MAX_W);
        assertThat(out.getHeight()).isLessThanOrEqualTo(Images.SIGNATURE_MAX_H);
        assertThat(out.getColorModel().hasAlpha()).isTrue();
        assertThat(out.getRGB(2, 2) >>> 24).isZero();                                         // paper
        assertThat(out.getRGB(out.getWidth() / 2, out.getHeight() / 3 + 3) >>> 24).isEqualTo(255); // ink
        assertThat(code(() -> {
            try { Images.signature(image(60, 20, "png")); } catch (ApiException e) { throw e; } catch (Exception e) { throw new RuntimeException(e); }
        })).isEqualTo("IMAGE_TOO_SMALL");
    }

    @Test
    void backgroundMustBeSharpEnoughToPrint() throws Exception {
        assertThat(code(() -> {
            try { Images.background(image(800, 600, "jpg")); } catch (ApiException e) { throw e; } catch (Exception e) { throw new RuntimeException(e); }
        })).isEqualTo("IMAGE_TOO_SMALL");
        Images.Background bg = Images.background(image(1754, 1240, "png"));
        assertThat(bg.width()).isEqualTo(1754);
        assertThat(Images.kind(bg.jpeg())).isEqualTo(Images.Kind.JPEG);
        TemplateDefinition.Page page = TemplateDefinition.pageFor(bg.width(), bg.height());
        assertThat(page.width()).isEqualTo(595.28f);
        assertThat(page.height()).isBetween(420f, 421f); // A5 landscape
    }
}
