package org.ngoplatform.receipt.app;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import org.ngoplatform.commons.error.ApiException;

/**
 * Receipt images (owner decision 2026-11-01): PNG or JPEG only - never PDF - checked by content, size-limited, and
 * re-encoded from pixels so no metadata (EXIF/GPS) of the original is kept.
 */
public final class Images {
    private Images() {}

    public static final long SIGNATURE_MAX_BYTES = 1024 * 1024;
    public static final int SIGNATURE_MAX_W = 1200, SIGNATURE_MAX_H = 600;
    public static final long BACKGROUND_MAX_BYTES = 5L * 1024 * 1024;
    public static final int BACKGROUND_MIN_SHORT_SIDE = 1240, BACKGROUND_MAX_LONG_SIDE = 4000;
    static final int DECODE_MAX_SIDE = 10_000;

    public enum Kind { PNG, JPEG }

    /** Type by magic bytes; anything else (PDF, GIF, WebP, programs) is refused. */
    public static Kind kind(byte[] b) {
        if (b != null && b.length > 8 && (b[0] & 0xFF) == 0x89 && b[1] == 'P' && b[2] == 'N' && b[3] == 'G') return Kind.PNG;
        if (b != null && b.length > 3 && (b[0] & 0xFF) == 0xFF && (b[1] & 0xFF) == 0xD8 && (b[2] & 0xFF) == 0xFF) return Kind.JPEG;
        throw ApiException.badRequest("IMAGE_TYPE_NOT_ALLOWED", "Upload a PNG or JPEG picture");
    }

    static BufferedImage decode(byte[] input, long maxBytes, String tooBigMessage) {
        if (input == null || input.length == 0) throw ApiException.badRequest("FILE_EMPTY", "The file is empty");
        if (input.length > maxBytes) throw ApiException.badRequest("FILE_TOO_LARGE", tooBigMessage);
        kind(input);
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) throw unreadable();
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true);
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w > DECODE_MAX_SIDE || h > DECODE_MAX_SIDE) {
                    throw ApiException.badRequest("IMAGE_TOO_LARGE", "The picture has too many pixels");
                }
                BufferedImage img = reader.read(0);
                if (img == null) throw unreadable();
                return img;
            } finally {
                reader.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException a) throw a;
            throw unreadable();
        }
    }

    /**
     * Signature: scaled to fit 1200 x 600, near-white paper made transparent (photos of a signature on white paper
     * print cleanly over a background), PNG.
     */
    public static byte[] signature(byte[] input) {
        synchronized (PdfRenderer.NATIVE_LOCK) { return signature0(input); }
    }

    private static byte[] signature0(byte[] input) {
        BufferedImage src = decode(input, SIGNATURE_MAX_BYTES, "The signature picture must be at most 1 MB");
        if (src.getWidth() < 100 || src.getHeight() < 40) {
            throw ApiException.badRequest("IMAGE_TOO_SMALL", "The signature picture is too small (at least 100 x 40 pixels)");
        }
        double f = Math.min(1.0, Math.min((double) SIGNATURE_MAX_W / src.getWidth(), (double) SIGNATURE_MAX_H / src.getHeight()));
        BufferedImage img = scale(src, (int) Math.round(src.getWidth() * f), (int) Math.round(src.getHeight() * f));
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int a = argb >>> 24, r = (argb >> 16) & 0xFF, g = (argb >> 8) & 0xFF, b = argb & 0xFF;
                int lum = (r * 299 + g * 587 + b * 114) / 1000;
                if (lum > 215) a = 0;                       // paper
                else if (lum > 160) a = a * (215 - lum) / 55; // soft edge of the ink
                img.setRGB(x, y, (a << 24) | (argb & 0xFFFFFF));
            }
        }
        return png(img);
    }

    /** Receipt background (R2): sharp enough to print, never larger than 4000 px on the long side. */
    public static Background background(byte[] input) {
        synchronized (PdfRenderer.NATIVE_LOCK) { return background0(input); }
    }

    private static Background background0(byte[] input) {
        BufferedImage src = decode(input, BACKGROUND_MAX_BYTES, "The background picture must be at most 5 MB");
        int w = src.getWidth(), h = src.getHeight();
        if (Math.min(w, h) < BACKGROUND_MIN_SHORT_SIDE) {
            throw ApiException.badRequest("IMAGE_TOO_SMALL", "The background must be at least " + BACKGROUND_MIN_SHORT_SIDE
                    + " pixels on its short side to print sharply (this one is " + w + " x " + h + ")");
        }
        double f = Math.min(1.0, (double) BACKGROUND_MAX_LONG_SIDE / Math.max(w, h));
        BufferedImage img = f < 1.0 ? scale(src, (int) Math.round(w * f), (int) Math.round(h * f)) : src;
        BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
        Graphics2D g = rgb.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, rgb.getWidth(), rgb.getHeight());
        g.drawImage(img, 0, 0, null);
        g.dispose();
        return new Background(jpeg(rgb), rgb.getWidth(), rgb.getHeight());
    }

    public record Background(byte[] jpeg, int width, int height) {}

    static BufferedImage scale(BufferedImage src, int w, int h) {
        BufferedImage out = new BufferedImage(Math.max(1, w), Math.max(1, h), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = out.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(src, 0, 0, out.getWidth(), out.getHeight(), null);
        g.dispose();
        return out;
    }

    static byte[] png(BufferedImage img) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            ImageIO.write(img, "png", out);
            return out.toByteArray();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    static byte[] jpeg(BufferedImage img) {
        try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var writer = ImageIO.getImageWritersByFormatName("jpeg").next();
            var param = writer.getDefaultWriteParam();
            param.setCompressionMode(javax.imageio.ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.88f);
            try (var ios = ImageIO.createImageOutputStream(out)) {
                writer.setOutput(ios);
                writer.write(null, new javax.imageio.IIOImage(img, null, null), param);
            } finally {
                writer.dispose();
            }
            return out.toByteArray();
        } catch (IOException e) { throw new IllegalStateException(e); }
    }

    private static ApiException unreadable() {
        return ApiException.badRequest("IMAGE_UNREADABLE", "This picture could not be read. Use a PNG or JPEG file.");
    }
}
