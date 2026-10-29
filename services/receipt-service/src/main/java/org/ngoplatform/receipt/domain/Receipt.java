package org.ngoplatform.receipt.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "donation_receipts")
public class Receipt {
    public enum Status { PENDING, GENERATING, GENERATED, DELIVERY_PENDING, DELIVERED, GENERATION_FAILED, VOIDED, REISSUED }

    @Id private UUID id;
    @Column(name = "ngo_id", nullable = false) private UUID ngoId;
    @Column(name = "donation_id", nullable = false) private UUID donationId;
    @Column(name = "donor_id", nullable = false) private UUID donorId;
    @Column(name = "donor_user_id") private UUID donorUserId;
    @Column(name = "receipt_number") private String receiptNumber;
    @Column(nullable = false) private BigDecimal amount;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.PENDING;
    @Column(name = "template_id") private UUID templateId;
    @Column(name = "template_version") private Integer templateVersion;
    @Column(name = "tax_80g", nullable = false) private boolean tax80g;
    @Column(name = "pdf_key") private String pdfKey;
    @Column(name = "pdf_sha256", length = 64) @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR) private String pdfSha256;
    @Column(nullable = false) private int attempts;
    @Column(name = "last_error") private String lastError;
    @Column(name = "next_attempt_at") private Instant nextAttemptAt;
    @Column(name = "reissue_of") private UUID reissueOf;
    @Column(name = "void_reason") private String voidReason;
    @Column(name = "issued_at") private Instant issuedAt;
    @Column(name = "delivered_at") private Instant deliveredAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version private long version;

    protected Receipt() {}

    public Receipt(UUID ngoId, UUID donationId, UUID donorId, UUID donorUserId, BigDecimal amount) {
        this.id = UUID.randomUUID(); this.ngoId = ngoId; this.donationId = donationId; this.donorId = donorId;
        this.donorUserId = donorUserId; this.amount = amount;
    }

    public void generating() { status = Status.GENERATING; attempts++; touch(); }

    public void generated(String number, String key, String sha, UUID templateId, Integer templateVersion, boolean tax80g, Instant at) {
        this.receiptNumber = number; this.pdfKey = key; this.pdfSha256 = sha; this.templateId = templateId;
        this.templateVersion = templateVersion; this.tax80g = tax80g; this.issuedAt = at; this.status = Status.DELIVERY_PENDING;
        this.lastError = null; this.nextAttemptAt = null; touch();
    }

    /** Exponential backoff: 1, 4, 16, 64 minutes ... capped at 6h. */
    public void failed(String error, Instant now) {
        this.status = Status.GENERATION_FAILED;
        this.lastError = error == null ? "unknown" : error.substring(0, Math.min(300, error.length()));
        long minutes = Math.min(360, (long) Math.pow(4, Math.max(0, attempts - 1)));
        this.nextAttemptAt = now.plusSeconds(minutes * 60);
        touch();
    }

    public void delivered(Instant at) { if (status == Status.DELIVERY_PENDING || status == Status.GENERATED) { status = Status.DELIVERED; deliveredAt = at; touch(); } }
    public void voidIt(String reason, boolean reissued) { status = reissued ? Status.REISSUED : Status.VOIDED; voidReason = reason; touch(); }
    public void setReissueOf(UUID id) { this.reissueOf = id; }
    public void retryNow() { this.nextAttemptAt = Instant.now(); touch(); }
    private void touch() { updatedAt = Instant.now(); }

    public boolean isLive() { return status != Status.VOIDED && status != Status.REISSUED; }

    public UUID getId() { return id; }
    public UUID getNgoId() { return ngoId; }
    public UUID getDonationId() { return donationId; }
    public UUID getDonorId() { return donorId; }
    public UUID getDonorUserId() { return donorUserId; }
    public String getReceiptNumber() { return receiptNumber; }
    public BigDecimal getAmount() { return amount; }
    public Status getStatus() { return status; }
    public UUID getTemplateId() { return templateId; }
    public Integer getTemplateVersion() { return templateVersion; }
    public boolean isTax80g() { return tax80g; }
    public String getPdfKey() { return pdfKey; }
    public String getPdfSha256() { return pdfSha256; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public UUID getReissueOf() { return reissueOf; }
    public String getVoidReason() { return voidReason; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public Instant getCreatedAt() { return createdAt; }
}
