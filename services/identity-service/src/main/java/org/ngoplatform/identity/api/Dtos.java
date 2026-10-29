package org.ngoplatform.identity.api;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.ngoplatform.identity.app.RoleCatalog;
import org.ngoplatform.identity.domain.NgoMembership;
import org.ngoplatform.identity.domain.StaffInvitation;
import org.ngoplatform.identity.domain.UserAccount;

public final class Dtos {
    private Dtos() {}

    public record MembershipView(UUID membershipId, UUID ngoId, UUID userId, String role, String status,
                                 Set<String> permissions, Map<String, Boolean> overrides, Instant since) {
        public static MembershipView of(NgoMembership m) {
            return new MembershipView(m.getId(), m.getNgoId(), m.getUserId(), m.getRole(), m.getStatus().name(),
                    RoleCatalog.effective(m), Map.copyOf(m.getOverrides()), m.getCreatedAt());
        }
    }

    public record Avatar(String kind, String preset, int photoVersion) {
        static Avatar of(UserAccount u) { return new Avatar(u.getAvatarKind(), u.getAvatarPreset(), u.getPhotoVersion()); }
    }

    public record MeView(UUID userId, String email, boolean emailVerified, String displayName, String phone,
                         String preferredLanguage, String status, Set<String> platformRoles,
                         List<MembershipView> memberships, java.time.LocalDate birthDate, String gender,
                         Avatar avatar, boolean profileComplete, boolean phoneVerified, boolean phoneRequired) {
        /** profileComplete mirrors the server-side gate (MeService.isUsable): adult profile + phone for team members. */
        public static MeView of(UserAccount u, Set<String> roles, List<NgoMembership> ms) {
            boolean phoneRequired = ms.stream().anyMatch(NgoMembership::isActive);
            boolean complete = u.isProfileComplete() && (u.hasVerifiedPhone() || !phoneRequired);
            return new MeView(u.getId(), u.getEmail(), u.isEmailVerified(), u.getDisplayName(), u.getPhone(),
                    u.getPreferredLanguage(), u.getStatus(), roles, ms.stream().map(MembershipView::of).toList(),
                    u.getBirthDate(), u.getGender(), Avatar.of(u), complete, u.hasVerifiedPhone(), phoneRequired);
        }
    }

    public record MemberView(UUID membershipId, UUID userId, String displayName, String email, String role,
                             String status, Set<String> permissions, Instant since, Avatar avatar, String phone) {}

    public record InvitationView(UUID id, UUID ngoId, String email, String role, String status, Instant expiresAt,
                                 Instant createdAt) {
        public static InvitationView of(StaffInvitation i) {
            return new InvitationView(i.getId(), i.getNgoId(), i.getEmail(), i.getRole(), i.getStatus().name(),
                    i.getExpiresAt(), i.getCreatedAt());
        }
    }

    public record UpdateProfile(
            @NotBlank @Size(min = 2, max = 120) @Pattern(regexp = "^[\\p{L}\\p{M} .'-]+$", message = "Only letters, spaces, . ' -") String displayName,
            @Pattern(regexp = "^$|^[6-9]\\d{9}$", message = "Enter a 10-digit Indian mobile number") String phone,
            @NotNull @Pattern(regexp = "en|hi|mr") String preferredLanguage) {}

    /** Profile completion / edit (NOTE: birthDate is fixed once saved). */
    public record CompleteProfile(
            @NotBlank @Size(min = 2, max = 120) @Pattern(regexp = "^[\\p{L}\\p{M} .'-]+$", message = "Only letters, spaces, . ' -") String displayName,
            @Pattern(regexp = "^$|^[6-9]\\d{9}$", message = "Enter a 10-digit Indian mobile number") String phone,
            @NotNull @Pattern(regexp = "en|hi|mr") String preferredLanguage,
            @NotNull java.time.LocalDate birthDate,
            @NotNull @Pattern(regexp = "FEMALE|MALE|TRANSGENDER|UNDISCLOSED") String gender,
            boolean confirmAdult) {}

    public record PhoneOtpRequest(@NotBlank @Pattern(regexp = "[6-9]\\d{9}", message = "Enter a 10-digit Indian mobile number") String phone) {}

    public record PhoneVerify(@NotBlank @Pattern(regexp = "[6-9]\\d{9}") String phone,
                              @NotBlank @Pattern(regexp = "\\d{6}", message = "Enter the 6-digit code") String code) {}

    public record PresetAvatar(@NotBlank @Pattern(regexp = "a\\d{2}") String preset) {}

    public record InviteRequest(@NotBlank @Email @Size(max = 254) String email,
                                @NotBlank @Pattern(regexp = "SECONDARY_ADMIN|STAFF|FINANCE|CAMPAIGN_MANAGER|VOLUNTEER_COORDINATOR|VOLUNTEER") String role) {}

    public record ChangeRole(@NotBlank @Pattern(regexp = "SECONDARY_ADMIN|STAFF|FINANCE|CAMPAIGN_MANAGER|VOLUNTEER_COORDINATOR|VOLUNTEER") String role) {}

    public record ChangeStatus(@NotNull NgoMembership.Status status) {}

    public record PermissionChange(@NotBlank String permission, Boolean granted) {}

    public record TransferPrimary(@NotNull UUID targetMembershipId) {}
}
