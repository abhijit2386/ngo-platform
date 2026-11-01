package org.ngoplatform.document.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.ngoplatform.commons.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Short-lived download links: created only after the member's access was checked, valid for 5 minutes, bound to
 * one document and the member who asked (recorded in the activity log when used). HMAC-SHA256, no state.
 */
@Component
public class LinkSigner {
    public static final Duration TTL = Duration.ofMinutes(5);
    public record Grant(UUID documentId, UUID userId, Instant expiresAt) {}

    private final byte[] secret;
    private final Clock clock;

    public LinkSigner(@Value("${ngo.documents.link-secret}") String secret, Clock clock) {
        if (secret == null || secret.length() < 24) throw new IllegalStateException("ngo.documents.link-secret must be at least 24 characters");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
        this.clock = clock;
    }

    public String sign(UUID documentId, UUID userId) {
        long exp = clock.instant().plus(TTL).getEpochSecond();
        String body = documentId + ":" + userId + ":" + exp;
        return b64(body.getBytes(StandardCharsets.UTF_8)) + "." + b64(mac(body));
    }

    public Instant expiry(String token) { return verify(token).expiresAt(); }

    public Grant verify(String token) {
        try {
            int dot = token.indexOf('.');
            if (dot < 1 || token.length() > 400) throw invalid();
            String body = new String(Base64.getUrlDecoder().decode(token.substring(0, dot)), StandardCharsets.UTF_8);
            byte[] sig = Base64.getUrlDecoder().decode(token.substring(dot + 1));
            if (!MessageDigest.isEqual(sig, mac(body))) throw invalid();
            String[] p = body.split(":");
            if (p.length != 3) throw invalid();
            Instant exp = Instant.ofEpochSecond(Long.parseLong(p[2]));
            if (clock.instant().isAfter(exp)) throw ApiException.forbidden("LINK_EXPIRED", "This download link has expired. Open the document again in the app.");
            return new Grant(UUID.fromString(p[0]), UUID.fromString(p[1]), exp);
        } catch (IllegalArgumentException e) {
            throw invalid();
        }
    }

    private byte[] mac(String body) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(secret, "HmacSHA256"));
            return m.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String b64(byte[] b) { return Base64.getUrlEncoder().withoutPadding().encodeToString(b); }

    private static ApiException invalid() { return ApiException.forbidden("LINK_INVALID", "This download link is not valid"); }
}
