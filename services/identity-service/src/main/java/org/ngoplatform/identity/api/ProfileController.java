package org.ngoplatform.identity.api;

import jakarta.validation.Valid;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.security.CurrentUser;
import org.ngoplatform.identity.app.MeService;
import org.ngoplatform.identity.app.ProfileService;
import org.ngoplatform.identity.domain.UserAccount;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Profile completion (adults only), profile pictures and built-in avatars. */
@RestController
public class ProfileController {
    private final AccessGuard guard;
    private final MeService me;
    private final ProfileService profiles;

    private final org.ngoplatform.identity.app.PhoneVerificationService phones;

    public ProfileController(AccessGuard guard, MeService me, ProfileService profiles,
                             org.ngoplatform.identity.app.PhoneVerificationService phones) {
        this.guard = guard; this.me = me; this.profiles = profiles; this.phones = phones;
    }

    /** Sends a 6-digit code by SMS to the number (not saved until verified). */
    @PostMapping("/api/v1/me/phone/otp")
    public org.ngoplatform.identity.app.PhoneVerificationService.Sent sendOtp(@Valid @RequestBody Dtos.PhoneOtpRequest req) {
        return phones.send(guard.user(), req.phone());
    }

    /** Verifies the code; on success the number becomes the account's verified mobile. */
    @PostMapping("/api/v1/me/phone/verify")
    public Dtos.MeView verifyOtp(@Valid @RequestBody Dtos.PhoneVerify req) {
        CurrentUser cu = guard.user();
        return Dtos.MeView.of(phones.verify(cu, req.phone(), req.code()), cu.platformRoles(), me.memberships(cu));
    }

    @DeleteMapping("/api/v1/me/phone")
    public Dtos.MeView removePhone() {
        CurrentUser cu = guard.user();
        return Dtos.MeView.of(phones.remove(cu), cu.platformRoles(), me.memberships(cu));
    }

    /**
     * Completes (first time) or updates the profile. Under-18: 403 UNDERAGE and the new account is removed
     * (or disabled when it already belongs to an NGO); the app then signs out.
     */
    @PutMapping("/api/v1/me/profile")
    public Dtos.MeView complete(@Valid @RequestBody Dtos.CompleteProfile req) {
        CurrentUser cu = guard.user();
        var in = new ProfileService.ProfileInput(req.displayName(), req.phone(), req.preferredLanguage(),
                req.birthDate(), req.gender(), req.confirmAdult());
        if (!profiles.validate(cu, in)) {
            ProfileService.Refusal r = profiles.refuseUnderage(cu);
            throw ApiException.forbidden("UNDERAGE", r == ProfileService.Refusal.DELETED
                    ? "Seva Setu is for people aged 18 and over. Your sign-up has been cancelled and your details removed."
                    : "Seva Setu is for people aged 18 and over. Your account has been paused; our support team will contact your organisation.");
        }
        UserAccount u = profiles.complete(cu, in);
        return Dtos.MeView.of(u, cu.platformRoles(), me.memberships(cu));
    }

    @PutMapping("/api/v1/me/avatar")
    public Dtos.MeView preset(@Valid @RequestBody Dtos.PresetAvatar req) {
        CurrentUser cu = guard.user();
        return Dtos.MeView.of(profiles.usePreset(cu, req.preset()), cu.platformRoles(), me.memberships(cu));
    }

    @PostMapping(path = "/api/v1/me/photo", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Dtos.MeView upload(@RequestParam("file") MultipartFile file) throws java.io.IOException {
        CurrentUser cu = guard.user();
        return Dtos.MeView.of(profiles.uploadPhoto(cu, file.getBytes()), cu.platformRoles(), me.memberships(cu));
    }

    /** Removes the picture (own photo or avatar): initials are shown instead. */
    @DeleteMapping("/api/v1/me/photo")
    public Dtos.MeView remove() {
        CurrentUser cu = guard.user();
        return Dtos.MeView.of(profiles.clearPicture(cu), cu.platformRoles(), me.memberships(cu));
    }

    @GetMapping("/api/v1/me/photo")
    public ResponseEntity<byte[]> ownPhoto(@RequestParam(defaultValue = "512") int size) {
        CurrentUser cu = guard.user();
        return image(profiles.photo(cu, cu.userId(), size));
    }

    /** Team members' photos (same NGO only). The version in the app's URL makes long caching safe. */
    @GetMapping("/api/v1/users/{userId}/photo")
    public ResponseEntity<byte[]> photo(@PathVariable UUID userId, @RequestParam(defaultValue = "128") int size) {
        return image(profiles.photo(guard.user(), userId, size));
    }

    private static ResponseEntity<byte[]> image(byte[] bytes) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.maxAge(Duration.ofDays(1)).cachePrivate())
                .header("X-Content-Type-Options", "nosniff").body(bytes);
    }
}
