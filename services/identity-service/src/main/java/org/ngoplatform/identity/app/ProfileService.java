package org.ngoplatform.identity.app;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.CurrentUser;
import org.ngoplatform.commons.storage.FileInspector;
import org.ngoplatform.commons.storage.ObjectStorage;
import org.ngoplatform.identity.domain.NgoMembership;
import org.ngoplatform.identity.domain.Repositories;
import org.ngoplatform.identity.domain.UserAccount;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Profile completion, the adults-only rule, and profile pictures (own photo or built-in avatar). */
@Service
public class ProfileService {
    private static final Logger log = LoggerFactory.getLogger(ProfileService.class);
    public static final Set<String> GENDERS = Set.of("FEMALE", "MALE", "TRANSGENDER", "UNDISCLOSED");
    /** Built-in avatars shipped with the app: a01..a16. */
    public static final int PRESET_COUNT = 16;

    public record ProfileInput(String displayName, String phone, String language, LocalDate birthDate, String gender,
                               boolean confirmAdult) {}

    public enum Refusal { DELETED, DISABLED }

    private final MeService me;
    private final Repositories.Users users;
    private final Repositories.Memberships memberships;
    private final KeycloakAdmin keycloak;
    private final ObjectStorage storage;
    private final AuditLog audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final long maxUpload;
    private final org.springframework.transaction.support.TransactionTemplate tx;

    public ProfileService(MeService me, Repositories.Users users, Repositories.Memberships memberships,
                          KeycloakAdmin keycloak, ObjectStorage storage, AuditLog audit, JdbcTemplate jdbc, Clock clock,
                          org.springframework.transaction.support.TransactionTemplate tx,
                          @Value("${ngo.photos.max-upload-bytes:5242880}") long maxUpload) {
        this.me = me; this.users = users; this.memberships = memberships; this.keycloak = keycloak;
        this.storage = storage; this.audit = audit; this.jdbc = jdbc; this.clock = clock; this.tx = tx;
        this.maxUpload = maxUpload;
    }

    /** Validates everything except the age; throws 400/409 for bad input. Returns whether the person is an adult. */
    public boolean validate(CurrentUser cu, ProfileInput in) {
        LocalDate today = Ages.today(clock);
        if (!Ages.isPlausible(in.birthDate(), today)) {
            throw ApiException.badRequest("INVALID_BIRTH_DATE", "Enter a valid date of birth");
        }
        if (!GENDERS.contains(in.gender())) throw ApiException.badRequest("INVALID_GENDER", "Choose one of the options");
        if (!in.confirmAdult()) {
            throw ApiException.badRequest("ADULT_CONFIRMATION_REQUIRED", "Please confirm that you are 18 or older");
        }
        users.findById(cu.userId()).map(UserAccount::getBirthDate).ifPresent(existing -> {
            if (!existing.equals(in.birthDate())) {
                throw ApiException.conflict("BIRTH_DATE_LOCKED",
                        "Your date of birth cannot be changed in the app. Please contact support.");
            }
        });
        return Ages.isAdult(in.birthDate(), today);
    }

    @Transactional
    public UserAccount complete(CurrentUser cu, ProfileInput in) {
        UserAccount u = me.sync(cu);
        boolean first = u.getBirthDate() == null;
        u.completeProfile(in.displayName().trim(), in.language(), in.birthDate(), in.gender(),
                clock.instant());
        audit.record(null, cu.userId(), first ? "PROFILE_COMPLETED" : "PROFILE_UPDATED", "USER", cu.userId(), Map.of());
        return u;
    }

    /**
     * Under 18: a brand-new account is deleted completely (Keycloak user incl. social links, our record, picture);
     * nothing personal is kept. An account that already belongs to an NGO cannot simply vanish, so it is disabled
     * and flagged for platform support instead. Keycloak goes first: if it is unreachable nothing changes.
     */
    public Refusal refuseUnderage(CurrentUser cu) {
        List<NgoMembership> ms = memberships.findByUserIdAndStatusNot(cu.userId(), NgoMembership.Status.REMOVED);
        if (!ms.isEmpty()) {
            keycloak.disableUser(cu.userId());
            tx.executeWithoutResult(t -> disableLocally(cu.userId()));
            return Refusal.DISABLED;
        }
        keycloak.deleteUser(cu.userId());
        tx.executeWithoutResult(t -> deleteLocally(cu.userId()));
        return Refusal.DELETED;
    }

    void disableLocally(UUID userId) {
        users.findById(userId).ifPresent(u -> { u.disable(); users.save(u); });
        audit.record(null, null, "UNDERAGE_ACCOUNT_DISABLED", "USER", userId, Map.of());
    }

    void deleteLocally(UUID userId) {
        users.findById(userId).ifPresent(u -> {
            deletePhotoFiles(userId, u.getPhotoVersion());
            users.delete(u);
        });
        jdbc.update("insert into refused_subjects(subject, expires_at) values (?, now() + interval '1 day') "
                + "on conflict (subject) do update set expires_at = excluded.expires_at", userId);
        // No personal data in the audit trail: not even the subject id.
        audit.record(null, null, "UNDERAGE_SIGNUP_REFUSED", "USER", null, Map.of());
    }

    @Scheduled(fixedDelay = 3_600_000, initialDelay = 60_000)
    public void purgeRefusals() {
        int n = jdbc.update("delete from refused_subjects where expires_at <= now()");
        if (n > 0) log.info("purged {} expired refusal markers", n);
    }

    // ------------------------------------------------------------------ pictures

    @Transactional
    public UserAccount usePreset(CurrentUser cu, String preset) {
        if (!isPreset(preset)) throw ApiException.badRequest("UNKNOWN_AVATAR", "Choose one of the avatars");
        UserAccount u = me.sync(cu);
        int old = "PHOTO".equals(u.getAvatarKind()) ? u.getPhotoVersion() : 0;
        u.usePresetAvatar(preset);
        if (old > 0) deletePhotoFiles(u.getId(), old);
        return u;
    }

    @Transactional
    public UserAccount clearPicture(CurrentUser cu) {
        UserAccount u = me.sync(cu);
        if ("PHOTO".equals(u.getAvatarKind())) deletePhotoFiles(u.getId(), u.getPhotoVersion());
        u.clearAvatar();
        audit.record(null, cu.userId(), "PROFILE_PICTURE_REMOVED", "USER", cu.userId(), Map.of());
        return u;
    }

    @Transactional
    public UserAccount uploadPhoto(CurrentUser cu, byte[] data) {
        FileInspector.inspect(data, maxUpload, Set.of("image/jpeg", "image/png"));
        PhotoProcessing.Result r = PhotoProcessing.process(data);
        UserAccount u = me.sync(cu);
        int old = "PHOTO".equals(u.getAvatarKind()) ? u.getPhotoVersion() : 0;
        int v = u.newPhoto();
        storage.put(key(u.getId(), v, PhotoProcessing.LARGE), r.large(), "image/jpeg");
        storage.put(key(u.getId(), v, PhotoProcessing.SMALL), r.small(), "image/jpeg");
        if (old > 0) deletePhotoFiles(u.getId(), old);
        audit.record(null, cu.userId(), "PROFILE_PICTURE_UPDATED", "USER", cu.userId(), Map.of());
        return u;
    }

    /**
     * Photo bytes for a viewer: the person themself, anyone who shares an active NGO membership with them, or
     * platform support. Everyone else gets 404 (no hint that the photo exists).
     */
    @Transactional(readOnly = true)
    public byte[] photo(CurrentUser viewer, UUID userId, int size) {
        UserAccount u = users.findById(userId).filter(x -> "PHOTO".equals(x.getAvatarKind()))
                .orElseThrow(() -> ApiException.notFound("Photo"));
        if (!viewer.userId().equals(userId) && !viewer.isPlatformSupport() && !sharesNgo(viewer.userId(), userId)) {
            throw ApiException.notFound("Photo");
        }
        int px = size <= PhotoProcessing.SMALL ? PhotoProcessing.SMALL : PhotoProcessing.LARGE;
        try (InputStream in = storage.get(key(userId, u.getPhotoVersion(), px))) {
            return in.readAllBytes();
        } catch (IOException | UncheckedIOException e) {
            throw ApiException.notFound("Photo");
        }
    }

    boolean sharesNgo(UUID a, UUID b) {
        Set<UUID> mine = memberships.findByUserIdAndStatusNot(a, NgoMembership.Status.REMOVED).stream()
                .filter(NgoMembership::isActive).map(NgoMembership::getNgoId).collect(java.util.stream.Collectors.toSet());
        return memberships.findByUserIdAndStatusNot(b, NgoMembership.Status.REMOVED).stream()
                .filter(NgoMembership::isActive).anyMatch(m -> mine.contains(m.getNgoId()));
    }

    static boolean isPreset(String code) {
        if (code == null || !code.matches("a\\d{2}")) return false;
        int n = Integer.parseInt(code.substring(1));
        return n >= 1 && n <= PRESET_COUNT;
    }

    static String key(UUID userId, int version, int px) { return "avatars/" + userId + "/" + version + "-" + px + ".jpg"; }

    private void deletePhotoFiles(UUID userId, int version) {
        if (version <= 0) return;
        try {
            storage.delete(key(userId, version, PhotoProcessing.LARGE));
            storage.delete(key(userId, version, PhotoProcessing.SMALL));
        } catch (RuntimeException e) {
            log.warn("could not delete old photo files: {}", e.toString());
        }
    }

    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    public Instant now() { return clock.instant(); }
}
