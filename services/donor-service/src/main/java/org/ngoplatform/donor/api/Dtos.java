package org.ngoplatform.donor.api;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.ngoplatform.donor.app.DonorService;
import org.ngoplatform.donor.domain.Donor;

public final class Dtos {
    private Dtos() {}
    static final String NAME = "^[\\p{L}\\p{M}\\p{N} .,'&()\\-/]+$";

    public record DonorRequest(
            @NotNull Donor.Type type,
            @NotBlank @Size(min = 2, max = 160) @Pattern(regexp = NAME) String name,
            @Email @Size(max = 254) String email,
            @Pattern(regexp = "^$|^[6-9]\\d{9}$", message = "Enter a 10-digit Indian mobile number") String phone,
            @Pattern(regexp = "^$|^[A-Za-z]{5}\\d{4}[A-Za-z]$", message = "PAN looks like ABCDE1234F") String pan,
            @Size(max = 250) String addressLine,
            @Size(max = 80) String city,
            @Size(max = 40) String state,
            @Pattern(regexp = "^$|^[1-9]\\d{5}$", message = "PIN code must be 6 digits") String pinCode,
            @Size(max = 120) String contactPerson,
            @Size(max = 20) List<@Size(max = 30) String> tags,
            @Size(max = 1000) String notes,
            Boolean communicationOptIn,
            Long version) {
        public DonorService.DonorInput input() {
            return new DonorService.DonorInput(type, name, email, phone, pan, addressLine, city, state, pinCode,
                    contactPerson, tags, notes, communicationOptIn);
        }
    }

    public record DonorView(UUID id, String type, String name, String email, String phone, String pan, String addressLine,
                            String city, String state, String pinCode, String contactPerson, List<String> tags,
                            String notes, String source, boolean communicationOptIn, BigDecimal totalDonated,
                            int donationCount, Instant firstDonationAt, Instant lastDonationAt, String status,
                            boolean linkedAccount, Instant createdAt, long version) {
        public static DonorView of(Donor d) {
            return new DonorView(d.getId(), d.getType().name(), d.getName(), d.getEmail(), d.getPhone(), d.getPan(),
                    d.getAddressLine(), d.getCity(), d.getState(), d.getPinCode(), d.getContactPerson(), d.getTags(),
                    d.getNotes(), d.getSource(), d.isCommunicationOptIn(), d.getTotalDonated(), d.getDonationCount(),
                    d.getFirstDonationAt(), d.getLastDonationAt(), d.getStatus().name(), d.getDonorUserId() != null,
                    d.getCreatedAt(), d.getVersion());
        }
    }

    public record Merge(@NotNull UUID sourceDonorId) {}

    public record SegmentRequest(@NotBlank @Size(max = 80) String name, String q, String tag, String city,
                                 @DecimalMin("0") BigDecimal minTotal, @Min(1) @Max(3650) Integer lapsedDays,
                                 @Min(1) @Max(3650) Integer activeWithinDays, Donor.Type type) {}

    public record ResolveRequest(UUID userId, @Size(max = 160) String name, @Email String email, String phone,
                                 String pan, boolean guest) {}

    public record ProfileRequest(@Size(min = 2, max = 120) @Pattern(regexp = NAME) String displayName,
                                 @Pattern(regexp = "^$|^[6-9]\\d{9}$") String phone,
                                 @Pattern(regexp = "^$|^[A-Za-z]{5}\\d{4}[A-Za-z]$") String pan,
                                 @Size(max = 80) String city) {}

    public record PreferencesRequest(boolean emailReceipts, boolean campaignUpdates, boolean marketing, boolean pushEnabled) {}

    public record PrivacyRequestDto(@NotBlank @Pattern(regexp = "ACCESS|CORRECTION|ERASURE|GRIEVANCE|OPT_OUT") String type,
                                    UUID ngoId, @Size(max = 2000) String details) {}

    public record PrivacyResolution(@NotBlank @Pattern(regexp = "IN_PROGRESS|COMPLETED|REJECTED") String status,
                                    @Size(max = 2000) String resolution) {}

    public record ImportView(UUID id, String status, int total, int valid, int duplicates, int invalid, Object rows) {}

    public record PrivacyView(UUID id, String type, UUID ngoId, String details, String status, String resolution, Instant createdAt) {
        public static PrivacyView of(org.ngoplatform.donor.domain.Records.PrivacyRequest p) {
            return new PrivacyView(p.getId(), p.getType(), p.getNgoId(), p.getDetails(), p.getStatus(), p.getResolution(), p.getCreatedAt());
        }
    }
}
