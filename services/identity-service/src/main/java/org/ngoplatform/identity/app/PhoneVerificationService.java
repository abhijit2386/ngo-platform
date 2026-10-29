package org.ngoplatform.identity.app;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.CurrentUser;
import org.ngoplatform.identity.domain.Repositories;
import org.ngoplatform.identity.domain.UserAccount;
import org.ngoplatform.identity.sms.SmsSender;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Mobile verification by SMS one-time code (owner rule 2026-10-29). Codes: 6 digits, valid 5 minutes, 5 tries,
 * new code after 60 s, at most 5 codes per account and per number per day. Only an HMAC of the code is stored.
 */
@Service
public class PhoneVerificationService {
    public static final Duration TTL = Duration.ofMinutes(5);
    public static final Duration RESEND_AFTER = Duration.ofSeconds(60);
    public static final int MAX_TRIES = 5;
    public static final int DAILY_LIMIT = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    public record Sent(String phoneMasked, long expiresInSeconds, long resendAfterSeconds) {}

    private final JdbcTemplate jdbc;
    private final MeService me;
    private final Repositories.Users users;
    private final SmsSender sms;
    private final AuditLog audit;
    private final Clock clock;
    private final byte[] secret;

    public PhoneVerificationService(JdbcTemplate jdbc, MeService me, Repositories.Users users, SmsSender sms,
                                    AuditLog audit, Clock clock, @Value("${ngo.otp.secret}") String secret) {
        this.jdbc = jdbc; this.me = me; this.users = users; this.sms = sms; this.audit = audit; this.clock = clock;
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public static boolean isIndianMobile(String p) { return p != null && p.matches("[6-9]\\d{9}"); }

    public static String mask(String p) { return p.substring(0, 2) + "XXXXXX" + p.substring(8); }

    String hash(UUID userId, String phone, String code) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((userId + "|" + phone + "|" + code).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String newCode() { return String.format("%06d", RANDOM.nextInt(1_000_000)); }

    @Transactional
    public Sent send(CurrentUser cu, String phone) {
        if (!isIndianMobile(phone)) throw ApiException.badRequest("INVALID_PHONE", "Enter a 10-digit Indian mobile number");
        UserAccount u = me.sync(cu);
        if (phone.equals(u.getPhone()) && u.hasVerifiedPhone()) {
            throw ApiException.conflict("PHONE_ALREADY_VERIFIED", "This number is already verified on your account");
        }
        Integer taken = jdbc.queryForObject("select count(*) from users where phone = ? and id <> ?", Integer.class, phone, u.getId());
        if (taken != null && taken > 0) {
            throw ApiException.conflict("PHONE_IN_USE", "This number is linked to another account. Use a different number or contact support.");
        }
        Instant now = clock.instant();
        Instant last = jdbc.query("select max(created_at) from phone_verifications where user_id = ?",
                rs -> rs.next() && rs.getTimestamp(1) != null ? rs.getTimestamp(1).toInstant() : null, u.getId());
        if (last != null && last.plus(RESEND_AFTER).isAfter(now)) {
            long wait = Duration.between(now, last.plus(RESEND_AFTER)).toSeconds() + 1;
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_RESEND_WAIT", "Please wait " + wait + " seconds before asking for a new code");
        }
        Integer byUser = jdbc.queryForObject("select count(*) from phone_verifications where user_id = ? and created_at > ?",
                Integer.class, u.getId(), java.sql.Timestamp.from(now.minus(Duration.ofDays(1))));
        Integer byPhone = jdbc.queryForObject("select count(*) from phone_verifications where phone = ? and created_at > ?",
                Integer.class, phone, java.sql.Timestamp.from(now.minus(Duration.ofDays(1))));
        if ((byUser != null && byUser >= DAILY_LIMIT) || (byPhone != null && byPhone >= DAILY_LIMIT)) {
            throw new ApiException(HttpStatus.TOO_MANY_REQUESTS, "OTP_DAILY_LIMIT", "Too many codes today. Please try again tomorrow.");
        }
        String code = newCode();
        jdbc.update("insert into phone_verifications(id, user_id, phone, code_hash, expires_at, created_at) values (?,?,?,?,?,?)",
                UUID.randomUUID(), u.getId(), phone, hash(u.getId(), phone, code), java.sql.Timestamp.from(now.plus(TTL)),
                java.sql.Timestamp.from(now));
        sms.sendOtp(phone, code); // a failure rolls the row back, so the user may retry at once
        audit.record(null, u.getId(), "PHONE_OTP_SENT", "USER", u.getId(), Map.of("phone", mask(phone)));
        return new Sent(mask(phone), TTL.toSeconds(), RESEND_AFTER.toSeconds());
    }

    @Transactional(noRollbackFor = ApiException.class)
    public UserAccount verify(CurrentUser cu, String phone, String code) {
        if (!isIndianMobile(phone) || code == null || !code.matches("\\d{6}")) {
            throw ApiException.badRequest("INVALID_CODE", "Enter the 6-digit code");
        }
        UserAccount u = me.sync(cu);
        Instant now = clock.instant();
        var rows = jdbc.queryForList("select id, code_hash, attempts, expires_at from phone_verifications "
                + "where user_id = ? and phone = ? and consumed_at is null order by created_at desc limit 1 for update",
                u.getId(), phone);
        if (rows.isEmpty()) throw ApiException.rule("OTP_NOT_REQUESTED", "Ask for a code first");
        var r = rows.get(0);
        UUID id = (UUID) r.get("id");
        int attempts = ((Number) r.get("attempts")).intValue();
        Instant expires = ((java.sql.Timestamp) r.get("expires_at")).toInstant();
        if (!expires.isAfter(now)) throw ApiException.rule("OTP_EXPIRED", "This code has expired. Ask for a new one.");
        if (attempts >= MAX_TRIES) throw ApiException.rule("OTP_LOCKED", "Too many wrong tries. Ask for a new code.");
        jdbc.update("update phone_verifications set attempts = attempts + 1 where id = ?", id);
        boolean ok = MessageDigest.isEqual(hash(u.getId(), phone, code).getBytes(StandardCharsets.UTF_8),
                r.get("code_hash").toString().getBytes(StandardCharsets.UTF_8));
        if (!ok) {
            int left = MAX_TRIES - attempts - 1;
            throw ApiException.rule("OTP_WRONG", left > 0 ? "Wrong code. " + left + " tries left." : "Wrong code. Ask for a new code.");
        }
        jdbc.update("update phone_verifications set consumed_at = ? where id = ?", java.sql.Timestamp.from(now), id);
        Integer taken = jdbc.queryForObject("select count(*) from users where phone = ? and id <> ?", Integer.class, phone, u.getId());
        if (taken != null && taken > 0) throw ApiException.conflict("PHONE_IN_USE", "This number is linked to another account.");
        u.setVerifiedPhone(phone, now);
        users.save(u);
        audit.record(null, u.getId(), "PHONE_VERIFIED", "USER", u.getId(), Map.of("phone", mask(phone)));
        return u;
    }

    /** Team members keep a verified number (owner rule); others may remove theirs. */
    @Transactional
    public UserAccount remove(CurrentUser cu) {
        UserAccount u = me.sync(cu);
        if (me.needsPhone(u.getId())) {
            throw ApiException.rule("PHONE_REQUIRED_FOR_TEAM", "NGO team members need a verified mobile number. You can change it, not remove it.");
        }
        u.clearPhone();
        audit.record(null, u.getId(), "PHONE_REMOVED", "USER", u.getId(), Map.of());
        return u;
    }
}
