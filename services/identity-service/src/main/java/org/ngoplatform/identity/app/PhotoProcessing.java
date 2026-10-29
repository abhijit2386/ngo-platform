package org.ngoplatform.identity.app;

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
 * Profile photo pipeline: decode (dimensions checked before decoding, against decompression bombs), centre-crop to
 * a square, scale, flatten transparency onto white and re-encode as JPEG. Re-encoding from pixels drops every
 * metadata block of the original (EXIF, GPS location, camera serials, XMP).
 */
public final class PhotoProcessing {
    public static final int LARGE = 512;
    public static final int SMALL = 128;
    static final int MAX_SIDE = 10_000;
    static final int MIN_SIDE = 64;

    private PhotoProcessing() {}

    public record Result(byte[] large, byte[] small) {}

    public static Result process(byte[] input) {
        BufferedImage src = decode(input);
        BufferedImage square = cropSquare(src);
        return new Result(jpeg(scale(square, LARGE)), jpeg(scale(square, SMALL)));
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
                    throw ApiException.badRequest("PHOTO_TOO_LARGE", "The picture is too large. Please use a smaller one.");
                }
                if (w < MIN_SIDE || h < MIN_SIDE) {
                    throw ApiException.badRequest("PHOTO_TOO_SMALL", "The picture is too small. Please use a bigger one.");
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

    static BufferedImage cropSquare(BufferedImage src) {
        int side = Math.min(src.getWidth(), src.getHeight());
        int x = (src.getWidth() - side) / 2, y = (src.getHeight() - side) / 2;
        return src.getSubimage(x, y, side, side);
    }

    static BufferedImage scale(BufferedImage square, int size) {
        BufferedImage out = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        try {
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, size, size);
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.drawImage(square, 0, 0, size, size, null);
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
        return ApiException.badRequest("PHOTO_UNREADABLE", "We could not read this picture. Please use a JPEG or PNG photo.");
    }
}
