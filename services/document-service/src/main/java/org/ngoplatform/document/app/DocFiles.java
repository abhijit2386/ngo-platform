package org.ngoplatform.document.app;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Iterator;
import java.util.Locale;
import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.ImageInputStream;
import javax.imageio.stream.ImageOutputStream;
import org.ngoplatform.commons.error.ApiException;

/**
 * Upload checks by content (never the client's file name or type): PDF, JPEG/PNG photos, Word / Excel / PowerPoint
 * (current and 97-2003 formats). Photos are re-encoded so EXIF data (GPS location, phone serial) is not stored.
 */
public final class DocFiles {
    private DocFiles() {}

    public record Checked(byte[] bytes, String fileType, String contentType, String extension, String sha256) {}

    static final long MAX_PIXELS = 50_000_000L;

    public static Checked check(byte[] data, String originalName, long maxBytes) {
        if (data == null || data.length == 0) throw ApiException.badRequest("FILE_EMPTY", "The file is empty");
        if (data.length > maxBytes) throw ApiException.badRequest("FILE_TOO_LARGE", "Files can be at most " + (maxBytes / (1024 * 1024)) + " MB");
        String ext = extension(originalName);
        if (starts(data, "%PDF-")) return done(data, "PDF", "application/pdf", "pdf");
        if (data.length > 3 && (data[0] & 0xFF) == 0xFF && (data[1] & 0xFF) == 0xD8 && (data[2] & 0xFF) == 0xFF) {
            return done(cleanImage(data, "jpeg"), "IMAGE", "image/jpeg", "jpg");
        }
        if (data.length > 8 && (data[0] & 0xFF) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return done(cleanImage(data, "png"), "IMAGE", "image/png", "png");
        }
        if (data.length > 4 && data[0] == 'P' && data[1] == 'K' && data[2] == 3 && data[3] == 4) {
            // Office Open XML: the part names are stored uncompressed in the zip headers.
            if (contains(data, "word/document.xml")) return done(data, "WORD", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "docx");
            if (contains(data, "xl/workbook.xml")) return done(data, "EXCEL", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "xlsx");
            if (contains(data, "ppt/presentation.xml")) return done(data, "SLIDES", "application/vnd.openxmlformats-officedocument.presentationml.presentation", "pptx");
            throw notAllowed();
        }
        if (data.length > 8 && (data[0] & 0xFF) == 0xD0 && (data[1] & 0xFF) == 0xCF && (data[2] & 0xFF) == 0x11 && (data[3] & 0xFF) == 0xE0) {
            // Office 97-2003 (OLE2): the container does not say which application, the file name does.
            return switch (ext) {
                case "doc" -> done(data, "WORD", "application/msword", "doc");
                case "xls" -> done(data, "EXCEL", "application/vnd.ms-excel", "xls");
                case "ppt" -> done(data, "SLIDES", "application/vnd.ms-powerpoint", "ppt");
                default -> throw notAllowed();
            };
        }
        throw notAllowed();
    }

    private static Checked done(byte[] b, String type, String ct, String ext) { return new Checked(b, type, ct, ext, sha256(b)); }

    private static ApiException notAllowed() {
        return ApiException.badRequest("FILE_TYPE_NOT_ALLOWED", "Upload a PDF, a photo (JPG / PNG), or a Word, Excel or PowerPoint file");
    }

    static String extension(String name) {
        if (name == null) return "";
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private static boolean starts(byte[] d, String s) {
        byte[] p = s.getBytes(StandardCharsets.US_ASCII);
        if (d.length < p.length) return false;
        for (int i = 0; i < p.length; i++) if (d[i] != p[i]) return false;
        return true;
    }

    static boolean contains(byte[] d, String s) {
        byte[] p = s.getBytes(StandardCharsets.US_ASCII);
        outer:
        for (int i = 0; i <= d.length - p.length; i++) {
            for (int j = 0; j < p.length; j++) if (d[i + j] != p[j]) continue outer;
            return true;
        }
        return false;
    }

    /** Re-encodes a photo from its pixels (drops all metadata). Size is checked before decoding. */
    static byte[] cleanImage(byte[] data, String format) {
        BufferedImage img;
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(data))) {
            Iterator<ImageReader> readers = in == null ? null : ImageIO.getImageReaders(in);
            if (readers == null || !readers.hasNext()) throw unreadable();
            ImageReader r = readers.next();
            try {
                r.setInput(in, true, true);
                if ((long) r.getWidth(0) * r.getHeight(0) > MAX_PIXELS) {
                    throw ApiException.badRequest("IMAGE_TOO_LARGE", "The picture is too large. Please use a smaller one.");
                }
                img = r.read(0);
            } finally {
                r.dispose();
            }
        } catch (IOException | RuntimeException e) {
            if (e instanceof ApiException a) throw a;
            throw unreadable();
        }
        if (img == null) throw unreadable();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            if ("png".equals(format)) {
                ImageIO.write(img, "png", out);
            } else {
                BufferedImage rgb = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
                Graphics2D g = rgb.createGraphics();
                g.setColor(Color.WHITE);
                g.fillRect(0, 0, img.getWidth(), img.getHeight());
                g.drawImage(img, 0, 0, null);
                g.dispose();
                ImageWriter w = ImageIO.getImageWritersByFormatName("jpeg").next();
                try (ImageOutputStream ios = ImageIO.createImageOutputStream(out)) {
                    w.setOutput(ios);
                    ImageWriteParam p = w.getDefaultWriteParam();
                    p.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
                    p.setCompressionQuality(0.9f);
                    w.write(null, new IIOImage(rgb, null, null), p);
                } finally {
                    w.dispose();
                }
            }
        } catch (IOException e) {
            throw new IllegalStateException("Image encoding failed", e);
        }
        return out.toByteArray();
    }

    private static ApiException unreadable() {
        return ApiException.badRequest("IMAGE_UNREADABLE", "We could not read this picture. Please use a JPG or PNG photo.");
    }

    static String sha256(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** File name for downloads: the title plus the real extension, without characters that break headers. */
    public static String downloadName(String title, String storageKey) {
        String ext = extension(storageKey);
        String base = title.replaceAll("[\\\\/:*?\"<>|\\r\\n]+", " ").replaceAll("\\s+", " ").strip();
        if (base.length() > 80) base = base.substring(0, 80).strip();
        return ext.isEmpty() ? base : base + "." + ext;
    }
}
