package org.ngoplatform.donation.api;

import jakarta.validation.constraints.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.ngoplatform.donation.domain.Donation;
import org.ngoplatform.donation.domain.Records;

public final class Dtos {
    private Dtos() {}
    static final String NAME = "^[\\p{L}\\p{M}\\p{N} .,'&()\\-/]+$";

    public record CheckoutRequest(
            @NotNull UUID ngoId, UUID campaignId, UUID projectId,
            @NotNull @DecimalMin(value = "1.00") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @Size(max = 200) String purpose,
            @NotBlank @Size(min = 2, max = 160) @Pattern(regexp = NAME) String donorName,
            @Email @Size(max = 254) String donorEmail,
            @Pattern(regexp = "^$|^[6-9]\\d{9}$", message = "Enter a 10-digit Indian mobile number") String donorPhone,
            @Pattern(regexp = "^$|^[A-Za-z]{5}\\d{4}[A-Za-z]$", message = "PAN looks like ABCDE1234F") String donorPan,
            @Size(max = 300) String donorAddress,
            boolean wants80g,
            @Pattern(regexp = "^$|^[A-Z_]{4,30}$") String scenario) {}

    public record OfflineRequest(
            UUID donorId,
            @NotBlank @Size(min = 2, max = 160) @Pattern(regexp = NAME) String donorName,
            @Email @Size(max = 254) String donorEmail,
            @Pattern(regexp = "^$|^[6-9]\\d{9}$") String donorPhone,
            @Pattern(regexp = "^$|^[A-Za-z]{5}\\d{4}[A-Za-z]$") String donorPan,
            @NotNull @DecimalMin("1.00") @Digits(integer = 10, fraction = 2) BigDecimal amount,
            @NotNull Donation.Channel channel,
            @Size(max = 100) String reference,
            @NotNull @PastOrPresent LocalDate receivedOn,
            UUID campaignId, UUID projectId,
            @Size(max = 200) String purpose,
            boolean wants80g) {}

    public record Correction(@NotNull @jakarta.validation.Valid OfflineRequest corrected, @NotBlank @Size(max = 300) String reason) {}

    public record RefundRequest(@NotNull @DecimalMin("1.00") @Digits(integer = 10, fraction = 2) BigDecimal amount,
                                @NotBlank @Size(min = 5, max = 500) String reason) {}

    public record RefundDecision(boolean approve, @Pattern(regexp = "^$|REFUND_SUCCESS|REFUND_FAILURE|PARTIAL_REFUND") String scenario) {}

    public record SettingsRequest(@NotBlank @Pattern(regexp = "MOCK|RAZORPAY|CASHFREE|PAYU|OTHER") String provider,
                                  @NotBlank @Size(max = 100) String merchantReference,
                                  boolean guestCheckoutAllowed,
                                  @NotNull @DecimalMin("1.00") BigDecimal minAmount,
                                  @NotNull @DecimalMin("1.00") BigDecimal maxAmount,
                                  @NotBlank @Pattern(regexp = "^[A-Z0-9]{2,10}$") String receiptPrefix) {}

    public record MockComplete(@NotBlank String scenario) {}

    public record ExceptionResolution(boolean ignore, @NotBlank @Size(max = 500) String note) {}

    public record DonationView(UUID id, UUID ngoId, String ngoName, UUID donorId, String donorName, String donorEmail,
                               UUID campaignId, String campaignTitle, UUID projectId, BigDecimal amount, BigDecimal refundedAmount,
                               String purpose, String channel, String status, boolean wants80g, String offlineReference,
                               boolean hasEvidence, Instant paidAt, String failureReason, boolean voided, UUID correctionOf,
                               Instant createdAt) {
        public static DonationView of(Donation d) {
            return new DonationView(d.getId(), d.getNgoId(), d.getNgoName(), d.getDonorId(), d.getDonorName(), d.getDonorEmail(),
                    d.getCampaignId(), d.getCampaignTitle(), d.getProjectId(), d.getAmount(), d.getRefundedAmount(), d.getPurpose(),
                    d.getChannel().name(), d.getStatus().name(), d.isWants80g(), d.getOfflineReference(),
                    d.getEvidenceKey() != null, d.getPaidAt(), d.getFailureReason(), d.isVoided(), d.getCorrectionOf(),
                    d.getCreatedAt());
        }
    }

    public record RefundView(UUID id, UUID donationId, BigDecimal amount, String reason, String status, UUID requestedBy,
                             UUID decidedBy, String failureReason, Instant createdAt) {
        public static RefundView of(Records.Refund r) {
            return new RefundView(r.getId(), r.getDonationId(), r.getAmount(), r.getReason(), r.getStatus().name(),
                    r.getRequestedBy(), r.getDecidedBy(), r.getFailureReason(), r.getCreatedAt());
        }
    }

    public record ExceptionView(UUID id, String kind, UUID donationId, String reference, BigDecimal expected,
                                BigDecimal actual, String status, String note, Instant createdAt) {
        public static ExceptionView of(Records.ReconException e) {
            return new ExceptionView(e.getId(), e.getKind(), e.getDonationId(), e.getReference(), e.getExpected(),
                    e.getActual(), e.getStatus(), e.getNote(), e.getCreatedAt());
        }
    }
}
