package org.ngoplatform.identity.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ngoplatform.identity.domain.UserAccount;

class ProfileRulesTest {
    private static final Instant NOW = Instant.parse("2026-10-29T03:00:00Z");

    private static UserAccount user() { return new UserAccount(UUID.randomUUID(), "a@ngo.test", true, "A"); }

    @Test
    void presetCodesA01ToA16Only() {
        assertThat(ProfileService.isPreset("a01")).isTrue();
        assertThat(ProfileService.isPreset("a16")).isTrue();
        assertThat(ProfileService.isPreset("a00")).isFalse();
        assertThat(ProfileService.isPreset("a17")).isFalse();
        assertThat(ProfileService.isPreset("b01")).isFalse();
        assertThat(ProfileService.isPreset("a1")).isFalse();
        assertThat(ProfileService.isPreset(null)).isFalse();
    }

    @Test
    void photoKeysAreVersionedPerUser() {
        UUID id = UUID.fromString("00000000-0000-0000-0000-000000000001");
        assertThat(ProfileService.key(id, 3, 128)).isEqualTo("avatars/00000000-0000-0000-0000-000000000001/3-128.jpg");
    }

    @Test
    void newUserIsIncomplete() {
        assertThat(user().isProfileComplete()).isFalse();
    }

    @Test
    void completingSetsAllFields() {
        UserAccount u = user();
        u.completeProfile("Asha Patil", "mr", LocalDate.of(1985, 4, 12), "FEMALE", NOW);
        assertThat(u.isProfileComplete()).isTrue();
        assertThat(u.getGender()).isEqualTo("FEMALE");
        assertThat(u.getPreferredLanguage()).isEqualTo("mr");
        assertThat(u.getBirthDate()).isEqualTo(LocalDate.of(1985, 4, 12));
    }

    @Test
    void birthDateIsLockedOnceSaved() {
        UserAccount u = user();
        u.completeProfile("Asha", "en", LocalDate.of(1985, 4, 12), "UNDISCLOSED", NOW);
        assertThatThrownBy(() -> u.completeProfile("Asha", "en", LocalDate.of(1990, 1, 1), "UNDISCLOSED", NOW))
                .hasMessage("BIRTH_DATE_LOCKED");
    }

    @Test
    void sameBirthDateMayBeResent() {
        UserAccount u = user();
        u.completeProfile("Asha", "en", LocalDate.of(1985, 4, 12), "UNDISCLOSED", NOW);
        u.completeProfile("Asha Patil", "hi", LocalDate.of(1985, 4, 12), "FEMALE", NOW);
        assertThat(u.getDisplayName()).isEqualTo("Asha Patil");
        assertThat(u.getPreferredLanguage()).isEqualTo("hi");
    }

    @Test
    void disabledAccountIsNotComplete() {
        UserAccount u = user();
        u.completeProfile("Asha", "en", LocalDate.of(1985, 4, 12), "UNDISCLOSED", NOW);
        u.disable();
        assertThat(u.isProfileComplete()).isFalse();
    }

    @Test
    void deletionRequestBlocksTheGate() {
        UserAccount u = user();
        u.completeProfile("Asha", "en", LocalDate.of(1985, 4, 12), "UNDISCLOSED", NOW);
        u.requestDeletion();
        assertThat(u.isProfileComplete()).isFalse();
        u.cancelDeletion();
        assertThat(u.isProfileComplete()).isTrue();
    }

    @Test
    void profileCompletionNeverSetsThePhone() {
        UserAccount u = user();
        u.completeProfile("Asha", "en", LocalDate.of(1985, 4, 12), "UNDISCLOSED", NOW);
        assertThat(u.getPhone()).isNull();
        assertThat(u.hasVerifiedPhone()).isFalse();
    }

    @Test
    void verifiedPhoneCanBeSetAndCleared() {
        UserAccount u = user();
        u.setVerifiedPhone("9876543210", NOW);
        assertThat(u.hasVerifiedPhone()).isTrue();
        assertThat(u.getPhoneVerifiedAt()).isEqualTo(NOW);
        u.clearPhone();
        assertThat(u.hasVerifiedPhone()).isFalse();
        assertThat(u.getPhone()).isNull();
    }

    @Test
    void updatingNameKeepsThePhone() {
        UserAccount u = user();
        u.setVerifiedPhone("9876543210", NOW);
        u.updateProfile("New Name", "hi");
        assertThat(u.getPhone()).isEqualTo("9876543210");
    }

    @Test
    void pictureKindsSwitchCleanly() {
        UserAccount u = user();
        assertThat(u.getAvatarKind()).isEqualTo("NONE");
        assertThat(u.newPhoto()).isEqualTo(1);
        assertThat(u.getAvatarKind()).isEqualTo("PHOTO");
        u.usePresetAvatar("a05");
        assertThat(u.getAvatarKind()).isEqualTo("PRESET");
        assertThat(u.getAvatarPreset()).isEqualTo("a05");
        assertThat(u.newPhoto()).isEqualTo(2);
        assertThat(u.getAvatarPreset()).isNull();
        u.clearAvatar();
        assertThat(u.getAvatarKind()).isEqualTo("NONE");
    }
}
