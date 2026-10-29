package org.ngoplatform.donation.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.ngoplatform.commons.error.ApiException;

@Entity
@Table(name = "donations")
public class Donation {
    public enum Status { CREATED, PENDING, AUTHORIZED, PAID, FAILED, CANCELLED, REFUNDED, PARTIALLY_REFUNDED, DISPUTED }
    public enum Channel { ONLINE, CASH, CHEQUE, BANK_TRANSFER, UPI_DIRECT, IN_KIND_VALUE }

    /** Explicit transitions (spec §13.3). Late/out-of-order provider events that would move backwards are ignored. */
    public static final Map<Status, Set<Status>> TRANSITIONS = Map.of(
            Status.CREATED, EnumSet.of(Status.PENDING, Status.AUTHORIZED, Status.PAID, Status.FAILED, Status.CANCELLED),
            Status.PENDING, EnumSet.of(Status.AUTHORIZED, Status.PAID, Status.FAILED, Status.CANCELLED),
            Status.AUTHORIZED, EnumSet.of(Status.PAID, Status.FAILED, Status.CANCELLED),
            Status.PAID, EnumSet.of(Status.PARTIALLY_REFUNDED, Status.REFUNDED, Status.DISPUTED),
            Status.PARTIALLY_REFUNDED, EnumSet.of(Status.PARTIALLY_REFUNDED, Status.REFUNDED, Status.DISPUTED),
            Status.DISPUTED, EnumSet.of(Status.PAID, Status.REFUNDED, Status.PARTIALLY_REFUNDED),
            // A provider may report success after we marked a payment failed/cancelled (delayed success).
            Status.FAILED, EnumSet.of(Status.PAID),
            Status.CANCELLED, EnumSet.of(Status.PAID),
            Status.REFUNDED, EnumSet.noneOf(Status.class));

    @Id private UUID id;
    @Column(name = "ngo_id", nullable = false) private UUID ngoId;
    @Column(name = "donor_id", nullable = false) private UUID donorId;
    @Column(name = "donor_user_id") private UUID donorUserId;
    @Column(name = "donor_name", nullable = false) private String donorName;
    @Column(name = "donor_email") private String donorEmail;
    @Column(name = "donor_pan") private String donorPan;
    @Column(name = "campaign_id") private UUID campaignId;
    @Column(name = "project_id") private UUID projectId;
    @Column(nullable = false) private BigDecimal amount;
    @Column(name = "refunded_amount", nullable = false) private BigDecimal refundedAmount = BigDecimal.ZERO;
    @Column(nullable = false, length = 3) @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR) private String currency = "INR";
    private String purpose;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Channel channel;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.CREATED;
    @Column(name = "wants_80g", nullable = false) private boolean wants80g;
    @Column(name = "offline_reference") private String offlineReference;
    @Column(name = "evidence_key") private String evidenceKey;
    @Column(name = "recorded_by") private UUID recordedBy;
    @Column(name = "paid_at") private Instant paidAt;
    @Column(name = "failure_reason") private String failureReason;
    @Column(nullable = false) private boolean voided;
    @Column(name = "correction_of") private UUID correctionOf;
    @Column(name = "expires_at") private Instant expiresAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version private long version;

    protected Donation() {}

    public Donation(UUID ngoId, UUID donorId, UUID donorUserId, String donorName, String donorEmail, String donorPan,
                    UUID campaignId, UUID projectId, BigDecimal amount, String purpose, Channel channel, boolean wants80g) {
        this.id = UUID.randomUUID(); this.ngoId = ngoId; this.donorId = donorId; this.donorUserId = donorUserId;
        this.donorName = donorName; this.donorEmail = donorEmail; this.donorPan = donorPan; this.campaignId = campaignId;
        this.projectId = projectId; this.amount = amount; this.purpose = purpose; this.channel = channel; this.wants80g = wants80g;
    }

    public boolean canMoveTo(Status to) { return TRANSITIONS.getOrDefault(status, Set.of()).contains(to); }

    public void moveTo(Status to) {
        if (!canMoveTo(to)) throw ApiException.rule("INVALID_DONATION_TRANSITION", "Cannot move donation from " + status + " to " + to);
        this.status = to; touch();
    }

    public void markPaid(Instant at) { moveTo(Status.PAID); this.paidAt = at; this.failureReason = null; }
    public void markFailed(Status to, String reason) { moveTo(to); this.failureReason = reason; }

    public void recordRefund(BigDecimal refund) {
        BigDecimal total = refundedAmount.add(refund);
        if (total.compareTo(amount) > 0) throw ApiException.rule("REFUND_EXCEEDS_AMOUNT", "Refunds cannot exceed the donation amount");
        refundedAmount = total;
        moveTo(total.compareTo(amount) == 0 ? Status.REFUNDED : Status.PARTIALLY_REFUNDED);
    }

    public void recordOffline(String reference, UUID by, Instant receivedAt) {
        this.offlineReference = reference; this.recordedBy = by; markPaid(receivedAt);
    }

    public void setEvidenceKey(String key) { this.evidenceKey = key; touch(); }
    public void setExpiresAt(Instant at) { this.expiresAt = at; }
    public void voidFor(UUID correctionId) { this.voided = true; touch(); }
    public void setCorrectionOf(UUID id) { this.correctionOf = id; }
    private void touch() { this.updatedAt = Instant.now(); }

    public BigDecimal refundable() { return amount.subtract(refundedAmount); }

    public UUID getId() { return id; }
    public UUID getNgoId() { return ngoId; }
    public UUID getDonorId() { return donorId; }
    public UUID getDonorUserId() { return donorUserId; }
    public String getDonorName() { return donorName; }
    public String getDonorEmail() { return donorEmail; }
    public String getDonorPan() { return donorPan; }
    public UUID getCampaignId() { return campaignId; }
    public UUID getProjectId() { return projectId; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getRefundedAmount() { return refundedAmount; }
    public String getPurpose() { return purpose; }
    public Channel getChannel() { return channel; }
    public Status getStatus() { return status; }
    public boolean isWants80g() { return wants80g; }
    public String getOfflineReference() { return offlineReference; }
    public String getEvidenceKey() { return evidenceKey; }
    public UUID getRecordedBy() { return recordedBy; }
    public Instant getPaidAt() { return paidAt; }
    public String getFailureReason() { return failureReason; }
    public boolean isVoided() { return voided; }
    public UUID getCorrectionOf() { return correctionOf; }
    public Instant getExpiresAt() { return expiresAt; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
}
