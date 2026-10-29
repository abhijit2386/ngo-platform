package org.ngoplatform.commons.storage;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Set;
import org.ngoplatform.commons.error.ApiException;

/** Validates uploads by magic number (never trusts the client's content type) and size. */
public final class FileInspector {
    public record Inspected(String contentType, String extension, String sha256, long size) {}

    private FileInspector() {}

    public static Inspected inspect(byte[] data, long maxBytes, Set<String> allowedTypes) {
        if (data == null || data.length == 0) throw ApiException.badRequest("FILE_EMPTY", "The file is empty");
        if (data.length > maxBytes) {
            throw ApiException.badRequest("FILE_TOO_LARGE", "File exceeds " + (maxBytes / (1024 * 1024)) + " MB");
        }
        String type = sniff(data);
        if (type == null || !allowedTypes.contains(type)) {
            throw ApiException.badRequest("FILE_TYPE_NOT_ALLOWED", "Allowed file types: " + String.join(", ", allowedTypes));
        }
        String ext = switch (type) {
            case "application/pdf" -> "pdf";
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "text/csv" -> "csv";
            default -> "bin";
        };
        return new Inspected(type, ext, sha256(data), data.length);
    }

    static String sniff(byte[] d) {
        if (d.length >= 5 && d[0] == '%' && d[1] == 'P' && d[2] == 'D' && d[3] == 'F' && d[4] == '-') return "application/pdf";
        if (d.length >= 8 && (d[0] & 0xFF) == 0x89 && d[1] == 'P' && d[2] == 'N' && d[3] == 'G') return "image/png";
        if (d.length >= 3 && (d[0] & 0xFF) == 0xFF && (d[1] & 0xFF) == 0xD8 && (d[2] & 0xFF) == 0xFF) return "image/jpeg";
        if (looksLikeText(d)) return "text/csv";
        return null;
    }

    private static boolean looksLikeText(byte[] d) {
        int n = Math.min(d.length, 4096);
        String s = new String(d, 0, n, StandardCharsets.UTF_8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == 0 || (c < 0x09) || (c > 0x0D && c < 0x20)) return false;
        }
        return true;
    }

    public static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** Strips path components and unsafe characters from a client-supplied file name. */
    public static String safeName(String original) {
        if (original == null) return "file";
        String base = original.replace('\\', '/');
        base = base.substring(base.lastIndexOf('/') + 1);
        base = base.replaceAll("[^A-Za-z0-9._\\- ]", "_");
        return base.isBlank() ? "file" : base.substring(0, Math.min(base.length(), 120));
    }
}
