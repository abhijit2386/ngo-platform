package org.ngoplatform.campaign.app;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Iterator;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.ngoplatform.commons.error.ApiException;

/**
 * Campaign cover pipeline: decode (dimensions checked before decoding, against decompression bombs), centre-crop to
 * 16:9, scale to 1200x675 and 480x270, flatten transparency onto white and re-encode as JPEG. Re-encoding from
 * pixels drops every metadata block of the original (EXIF, GPS location of beneficiaries' photos, camera serials).
 */
public final class CoverImage {
    public static final int LARGE_W = 1200, LARGE_H = 675;
    public static final int SMALL_W = 480, SMALL_H = 270;
    static final int MAX_SIDE = 10_000;
    static final int MIN_W = 400, MIN_H = 225;

    private CoverImage() {}

    public record Result(byte[] large, byte[] small) {}

    public static Result process(byte[] input) {
        BufferedImage wide = crop169(decode(input));
        return new Result(jpeg(scale(wide, LARGE_W, LARGE_H)), jpeg(scale(wide, SMALL_W, SMALL_H)));
    }

    static BufferedImage decode(byte[] input) {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(input))) {
            if (in == null) throw unreadable();
            Iterator<ImageReader> readers = ImageIO.getImageReaders(in);
            if (!readers.hasNext()) throw unreadable();
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, true); // ignore metadata while reading
                int w = reader.getWidth(0), h = reader.getHeight(0);
                if (w > MAX_SIDE || h > MAX_SIDE) {
                    throw ApiException.badRequest("IMAGE_TOO_LARGE", "The picture is too large. Please use a smaller one.");
                }
                if (w < MIN_W || h < MIN_H) {
                    throw ApiException.badRequest("IMAGE_TOO_SMALL", "The picture is too small. Use one at least 400 x 225 pixels.");
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

    static BufferedImage crop169(BufferedImage src) {
        int w = src.getWidth(), h = src.getHeight();
        int cw = w, ch = w * 9 / 16;
        if (ch > h) { ch = h; cw = h * 16 / 9; }
        return src.getSubimage((w - cw) / 2, (h - ch) / 2, cw, ch);
    }

    static BufferedImage scale(BufferedImage img, int width, int height) {
        BufferedImage out = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(img, 0, 0, width, height, null);
        } finally {
            g.dispose();
        }
        return out;
    }

    static byte[] jpeg(BufferedImage img) {
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ImageOutputStream out = ImageIO.createImageOutputStream(bytes)) {
            writer.setOutput(out);
            ImageWriteParam p = writer.getDefaultWriteParam();
            p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            p.setCompressionQuality(0.85f);
            writer.write(null, new IIOImage(img, null, null), p); // no metadata written
        } catch (IOException e) {
            throw new IllegalStateException("JPEG encoding failed", e);
        } finally {
            writer.dispose();
        }
        return bytes.toByteArray();
    }

    private static ApiException unreadable() {
        return ApiException.badRequest("IMAGE_UNREADABLE", "We could not read this picture. Please use a JPEG or PNG photo.");
    }
}
