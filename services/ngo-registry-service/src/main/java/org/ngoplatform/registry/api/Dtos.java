package org.ngoplatform.registry.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.ngoplatform.registry.domain.Ngo;
import org.ngoplatform.registry.domain.Parts;

public final class Dtos {
    private Dtos() {}
    static final String NAME = "^[\\p{L}\\p{M}\\p{N} .,'&()\\-/]+$";
    static final String PHONE = "^[6-9]\\d{9}$";

    public record OrganizationDetails(
            @NotBlank @Size(min = 3, max = 200) @Pattern(regexp = NAME) String legalName,
            @NotBlank @Size(min = 2, max = 120) @Pattern(regexp = NAME) String displayName,
            @NotNull Ngo.RegistrationType registrationType,
            @Size(max = 2000) String description,
            @NotNull @Size(max = 8) List<@NotBlank String> areasOfActivity,
            @Email @Size(max = 254) String officialEmail,
            @Pattern(regexp = PHONE, message = "Enter a 10-digit Indian mobile number") String officialPhone,
            @Size(max = 200) @Pattern(regexp = "^$|^https?://[\\w.-]+(\\.[\\w.-]+)+[\\w\\-._~:/?#\\[\\]@!$&'()*+,;=%]*$") String website) {}

    public record CreateNgo(@NotNull @Valid OrganizationDetails organization, @Valid Person secondaryAdmin) {}

    public record RegistrationDetails(
            @NotBlank @Size(min = 3, max = 60) @Pattern(regexp = "^[A-Za-z0-9/\\-. ]+$") String registrationNumber,
            @NotNull @PastOrPresent LocalDate registrationDate,
            @NotBlank String registrationState,
            @Pattern(regexp = "^$|^[A-Za-z]{2}/\\d{4}/\\d{7}$", message = "Darpan ID looks like MH/2017/0123456") String darpanId,
            @Pattern(regexp = "^$|^[A-Za-z]{5}\\d{4}[A-Za-z]$", message = "PAN looks like ABCDE1234F") String pan,
            @Size(max = 60) String tax80gNumber,
            @Future LocalDate tax80gValidUntil) {}

    public record AddressDto(@NotBlank @Size(max = 200) String line1, @Size(max = 200) String line2,
                             @NotBlank @Size(max = 80) @Pattern(regexp = NAME) String city,
                             @NotBlank @Size(max = 80) @Pattern(regexp = NAME) String district,
                             @NotBlank String state,
                             @NotBlank @Pattern(regexp = "^[1-9]\\d{5}$", message = "PIN code must be 6 digits") String pinCode) {
        public static AddressDto of(Parts.Address a) {
            return a == null ? null : new AddressDto(a.getLine1(), a.getLine2(), a.getCity(), a.getDistrict(), a.getState(), a.getPinCode());
        }
    }

    public record Person(@NotBlank @Size(min = 2, max = 120) @Pattern(regexp = NAME) String name,
                         @Email @Size(max = 254) String email,
                         @Pattern(regexp = "^$|" + PHONE) String phone,
                         @Size(max = 80) String designation) {}

    public record PublicProfileUpdate(@NotBlank @Size(min = 2, max = 120) @Pattern(regexp = NAME) String displayName,
                                      @Size(max = 2000) String description,
                                      @NotNull @Size(max = 8) List<String> areasOfActivity,
                                      @Size(max = 200) String website,
                                      @Pattern(regexp = "^$|^#[0-9A-Fa-f]{6}$") String brandColor) {}

    public record DocumentView(UUID id, String type, String fileName, String contentType, long sizeBytes,
                               String status, LocalDate expiresOn, String reviewNote, Instant uploadedAt) {
        public static DocumentView of(Parts.Document d) {
            return new DocumentView(d.getId(), d.getType(), d.getFileName(), d.getContentType(), d.getSizeBytes(),
                    d.getStatus(), d.getExpiresOn(), d.getReviewNote(), d.getCreatedAt());
        }
    }

    public record ContactView(String kind, String name, String email, String phone, String designation) {
        public static ContactView of(Parts.Contact c) {
            return new ContactView(c.getKind(), c.getName(), c.getEmail(), c.getPhone(), c.getDesignation());
        }
    }

    /** Private view for NGO members. */
    public record NgoView(UUID id, String legalName, String displayName, Ngo.RegistrationType registrationType,
                          String registrationNumber, LocalDate registrationDate, String registrationState,
                          String darpanId, String pan, String description, List<String> areasOfActivity,
                          String officialEmail, String officialPhone, String website, boolean hasLogo,
                          String brandColor, String tax80gNumber, LocalDate tax80gValidUntil, boolean tax80gVerified,
                          String accountStatus, String registrationStatus, String verificationStatus,
                          String publicProfileStatus, String donationEligibility, String paymentOnboardingStatus,
                          Instant verifiedAt, Instant submittedAt, AddressDto address, List<ContactView> contacts,
                          List<String> missingForSubmission, long version) {}

    /** Public donor-facing view: no private verification records. */
    public record PublicNgo(UUID id, String displayName, String registrationType, String description,
                            List<String> areasOfActivity, String city, String district, String state, String website,
                            boolean hasLogo, String brandColor, boolean verified, Instant verifiedSince,
                            boolean tax80gEligible, boolean acceptingDonations) {
        public static PublicNgo of(Ngo n, Parts.Address a) {
            return new PublicNgo(n.getId(), n.getDisplayName(), n.getRegistrationType().name(), n.getDescription(),
                    n.getAreasOfActivity(), a == null ? null : a.getCity(), a == null ? null : a.getDistrict(),
                    a == null ? null : a.getState(), n.getWebsite(), n.getLogoKey() != null, n.getBrandColor(),
                    n.getVerificationStatus() == Ngo.VerificationStatus.APPROVED, n.getVerifiedAt(),
                    n.isTax80gVerified() && n.getTax80gValidUntil() != null && n.getTax80gValidUntil().isAfter(LocalDate.now()),
                    n.donationEligible());
        }
    }

    public enum Decision { APPROVE, REJECT, REQUEST_INFO }

    public record DocumentReview(@NotNull UUID documentId, boolean accepted, @Size(max = 500) String note) {}

    public record VerificationDecision(@NotNull Decision decision, @Size(max = 2000) String messageToNgo,
                                       @Size(max = 2000) String internalNote, Boolean confirm80g,
                                       @Valid List<DocumentReview> documentReviews) {}

    public record Suspension(boolean suspend, @Size(max = 1000) String reason) {}

    public record ChangeRequestDto(@NotBlank @Pattern(regexp = "LEGAL_NAME|REGISTRATION_NUMBER|PAN|REGISTRATION_TYPE") String field,
                                   @NotBlank @Size(max = 200) String newValue, @Size(max = 500) String reason) {}

    public record ChangeView(UUID id, UUID ngoId, String field, String oldValue, String newValue, String reason,
                             String status, Instant createdAt) {
        public static ChangeView of(Parts.ChangeRequest c) {
            return new ChangeView(c.getId(), c.getNgoId(), c.getField(), c.getOldValue(), c.getNewValue(), c.getReason(),
                    c.getStatus(), c.getCreatedAt());
        }
    }

    public record VerificationHistory(String action, String messageToNgo, Instant at) {}

    public record AdminVerificationHistory(String action, UUID actorId, String internalNote, String messageToNgo, Instant at) {}

    public record QueueItem(UUID id, String displayName, String legalName, String registrationState,
                            String verificationStatus, List<String> riskFlags, Instant submittedAt) {}
}
