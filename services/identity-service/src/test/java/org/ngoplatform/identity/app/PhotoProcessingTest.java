package org.ngoplatform.identity.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.ngoplatform.commons.error.ApiException;

class PhotoProcessingTest {

    static byte[] image(int w, int h, String format, int type) throws Exception {
        BufferedImage img = new BufferedImage(w, h, type);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED); g.fillRect(0, 0, w / 2, h);
        g.setColor(Color.BLUE); g.fillRect(w / 2, 0, w - w / 2, h);
        g.dispose();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, format, out);
        return out.toByteArray();
    }

    /** Inserts an APP1 "Exif" segment with a GPS-looking payload after the JFIF APP0 segment, as cameras do. */
    static byte[] withExif(byte[] jpeg) {
        byte[] payload = "Exif\0\0GPSLatitude=18.5204;GPSLongitude=73.8567".getBytes(StandardCharsets.ISO_8859_1);
        int len = payload.length + 2;
        int app0End = 4 + (((jpeg[4] & 0xFF) << 8) | (jpeg[5] & 0xFF)); // SOI(2) + marker(2) + segment length
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(jpeg, 0, app0End);
        out.write(0xFF); out.write(0xE1); out.write((len >> 8) & 0xFF); out.write(len & 0xFF);
        out.write(payload, 0, payload.length);
        out.write(jpeg, app0End, jpeg.length - app0End);
        return out.toByteArray();
    }

    static BufferedImage read(byte[] b) throws Exception { return ImageIO.read(new ByteArrayInputStream(b)); }

    @Test
    void producesSquare512And128Jpegs() throws Exception {
        var r = PhotoProcessing.process(image(1200, 800, "jpg", BufferedImage.TYPE_INT_RGB));
        BufferedImage large = read(r.large()), small = read(r.small());
        assertThat(large.getWidth()).isEqualTo(512);
        assertThat(large.getHeight()).isEqualTo(512);
        assertThat(small.getWidth()).isEqualTo(128);
        assertThat(small.getHeight()).isEqualTo(128);
        assertThat(r.large()[0] & 0xFF).isEqualTo(0xFF);
        assertThat(r.large()[1] & 0xFF).isEqualTo(0xD8);
    }

    @Test
    void stripsExifAndGpsMetadata() throws Exception {
        byte[] original = withExif(image(800, 800, "jpg", BufferedImage.TYPE_INT_RGB));
        assertThat(new String(original, StandardCharsets.ISO_8859_1)).contains("GPSLatitude");
        var r = PhotoProcessing.process(original);
        String out = new String(r.large(), StandardCharsets.ISO_8859_1);
        assertThat(out).doesNotContain("Exif").doesNotContain("GPS");
    }

    @Test
    void cropsTheCentreOfWideImages() throws Exception {
        // Left half red, right half blue: the centre crop of a 3:1 image keeps both colours around the middle.
        var r = PhotoProcessing.process(image(1500, 500, "png", BufferedImage.TYPE_INT_RGB));
        BufferedImage large = read(r.large());
        Color left = new Color(large.getRGB(20, 256)), right = new Color(large.getRGB(490, 256));
        assertThat(left.getRed()).isGreaterThan(200);
        assertThat(right.getBlue()).isGreaterThan(200);
    }

    @Test
    void transparentPngIsFlattenedOnWhite() throws Exception {
        BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB); // fully transparent
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(img, "png", out);
        BufferedImage large = read(PhotoProcessing.process(out.toByteArray()).large());
        Color c = new Color(large.getRGB(256, 256));
        assertThat(c.getRed()).isGreaterThan(240);
        assertThat(c.getGreen()).isGreaterThan(240);
        assertThat(c.getBlue()).isGreaterThan(240);
    }

    @Test
    void rejectsTinyImages() throws Exception {
        byte[] tiny = image(32, 32, "png", BufferedImage.TYPE_INT_RGB);
        assertThatThrownBy(() -> PhotoProcessing.process(tiny)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("PHOTO_TOO_SMALL");
    }

    @Test
    void rejectsGarbage() {
        byte[] junk = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 1, 2, 3, 4, 5};
        assertThatThrownBy(() -> PhotoProcessing.process(junk)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("PHOTO_UNREADABLE");
    }

    @Test
    void rejectsDecompressionBombByHeaderBeforeDecoding() throws Exception {
        // A PNG header that claims 20000 x 20000 pixels: rejected from the header, never decoded.
        byte[] png = image(100, 100, "png", BufferedImage.TYPE_INT_RGB);
        png[16] = 0; png[17] = 0; png[18] = 0x4E; png[19] = 0x20; // width = 20000
        png[20] = 0; png[21] = 0; png[22] = 0x4E; png[23] = 0x20; // height = 20000
        assertThatThrownBy(() -> PhotoProcessing.process(png)).isInstanceOf(ApiException.class)
                .extracting(e -> ((ApiException) e).code()).isEqualTo("PHOTO_TOO_LARGE");
    }

    @Test
    void smallOutputIsMuchSmallerThanLarge() throws Exception {
        var r = PhotoProcessing.process(image(1000, 1000, "jpg", BufferedImage.TYPE_INT_RGB));
        assertThat(r.small().length).isLessThan(r.large().length);
    }
}
