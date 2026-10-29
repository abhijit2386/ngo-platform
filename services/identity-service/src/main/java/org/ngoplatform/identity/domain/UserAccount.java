package org.ngoplatform.identity.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserAccount {
    @Id private UUID id;
    @Column(nullable = false) private String email;
    @Column(name = "email_verified", nullable = false) private boolean emailVerified;
    @Column(name = "display_name") private String displayName;
    private String phone;
    @Column(name = "preferred_language", nullable = false) private String preferredLanguage = "en";
    @Column(nullable = false) private String status = "ACTIVE";
    @Column(name = "deletion_requested_at") private Instant deletionRequestedAt;
    @Column(name = "birth_date") private LocalDate birthDate;
    @Column(nullable = false) private String gender = "UNDISCLOSED";
    @Column(name = "avatar_kind", nullable = false) private String avatarKind = "NONE";
    @Column(name = "avatar_preset") private String avatarPreset;
    @Column(name = "photo_version", nullable = false) private int photoVersion;
    @Column(name = "adult_confirmed_at") private Instant adultConfirmedAt;
    @Column(name = "profile_completed_at") private Instant profileCompletedAt;
    @Column(name = "phone_verified_at") private Instant phoneVerifiedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version private long version;

    protected UserAccount() {}

    public UserAccount(UUID id, String email, boolean emailVerified, String displayName) {
        this.id = id; this.email = email; this.emailVerified = emailVerified; this.displayName = displayName;
    }

    public void syncFromToken(String email, boolean verified, String name) {
        if (email != null) this.email = email;
        this.emailVerified = verified;
        if (this.displayName == null && name != null) this.displayName = name;
        touch();
    }

    /** Name and language. The phone number changes only through OTP verification ({@link #setVerifiedPhone}). */
    public void updateProfile(String displayName, String language) {
        this.displayName = displayName; this.preferredLanguage = language; touch();
    }

    public void setVerifiedPhone(String phone, Instant at) { this.phone = phone; this.phoneVerifiedAt = at; touch(); }
    public void clearPhone() { this.phone = null; this.phoneVerifiedAt = null; touch(); }
    public boolean hasVerifiedPhone() { return phone != null && phoneVerifiedAt != null; }

    /**
     * Completes or updates the profile. The date of birth is fixed once saved (corrections go through support);
     * the caller has already checked the 18+ rule.
     */
    public void completeProfile(String displayName, String language, LocalDate birthDate, String gender, Instant now) {
        if (this.birthDate != null && !this.birthDate.equals(birthDate)) {
            throw new IllegalStateException("BIRTH_DATE_LOCKED");
        }
        this.displayName = displayName; this.preferredLanguage = language;
        this.birthDate = birthDate; this.gender = gender;
        if (adultConfirmedAt == null) adultConfirmedAt = now;
        if (profileCompletedAt == null) profileCompletedAt = now;
        touch();
    }

    /** Profile complete and account usable: the condition of the adults-only gate. */
    public boolean isProfileComplete() {
        return birthDate != null && profileCompletedAt != null && "ACTIVE".equals(status);
    }

    public void usePresetAvatar(String preset) { this.avatarKind = "PRESET"; this.avatarPreset = preset; touch(); }
    public void clearAvatar() { this.avatarKind = "NONE"; this.avatarPreset = null; touch(); }
    /** Starts a new photo version (old versions are deleted from storage by the caller). */
    public int newPhoto() { this.avatarKind = "PHOTO"; this.avatarPreset = null; this.photoVersion++; touch(); return photoVersion; }
    public void disable() { this.status = "DISABLED"; touch(); }

    public void requestDeletion() { this.status = "DELETION_REQUESTED"; this.deletionRequestedAt = Instant.now(); touch(); }
    public void cancelDeletion() { this.status = "ACTIVE"; this.deletionRequestedAt = null; touch(); }

    private void touch() { this.updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public String getEmail() { return email; }
    public boolean isEmailVerified() { return emailVerified; }
    public String getDisplayName() { return displayName; }
    public String getPhone() { return phone; }
    public String getPreferredLanguage() { return preferredLanguage; }
    public String getStatus() { return status; }
    public long getVersion() { return version; }
    public LocalDate getBirthDate() { return birthDate; }
    public String getGender() { return gender; }
    public String getAvatarKind() { return avatarKind; }
    public String getAvatarPreset() { return avatarPreset; }
    public int getPhotoVersion() { return photoVersion; }
    public Instant getPhoneVerifiedAt() { return phoneVerifiedAt; }
}
