package org.ngoplatform.receipt.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One receipt for one donation (spec §15.6). Once numbered, the number, PDF, amount and printed donor name are
 * immutable (DB trigger); a correction cancels the receipt and issues a new one (REISSUED -> new PENDING).
 */
@Entity
@Table(name = "donation_receipts")
public class Receipt {
    public enum Status { PENDING, GENERATING, GENERATED, DELIVERY_PENDING, DELIVERED, GENERATION_FAILED, VOIDED, REISSUED }

    @Id private UUID id;
    @Column(name = "ngo_id", nullable = false) private UUID ngoId;
    @Column(name = "donation_id", nullable = false) private UUID donationId;
    @Column(name = "donor_id") private UUID donorId;
    @Column(name = "donor_user_id") private UUID donorUserId;
    @Column(name = "donor_name") private String donorName;
    @Column(name = "ngo_name") private String ngoName;
    @Column(name = "receipt_number") private String receiptNumber;
    @Column(nullable = false) private BigDecimal amount;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.PENDING;
    @Column(name = "template_id") private UUID templateId;
    @Column(name = "template_version") private Integer templateVersion;
    @Column(name = "tax_80g", nullable = false) private boolean tax80g;
    @Column(name = "pdf_key") private String pdfKey;
    @Column(name = "pdf_sha256", length = 64) @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR) private String pdfSha256;
    @Column(name = "pdf_size") private Long pdfSize;
    @Column(name = "donation_date") private LocalDate donationDate;
    @Column(name = "fiscal_year") private String fiscalYear;
    private String channel;
    @Column(name = "campaign_title") private String campaignTitle;
    @Column(name = "verify_code") private String verifyCode;
    @Column(nullable = false) private int attempts;
    @Column(name = "last_error") private String lastError;
    @Column(name = "next_attempt_at") private Instant nextAttemptAt;
    @Column(name = "notified_at") private Instant notifiedAt;
    @Column(name = "reissue_of") private UUID reissueOf;
    @Column(name = "void_reason") private String voidReason;
    @Column(name = "void_by") private UUID voidBy;
    @Column(name = "voided_at") private Instant voidedAt;
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

    /** Facts copied from the donation before numbering (shown in lists without cross-service calls). */
    /**
     * Number + printed facts, committed before the PDF is rendered: the number is never lost (gap-free series) and a
     * retry prints exactly the same receipt.
     */
    public void numbered(String number, String verifyCode, String donorName, String ngoName, LocalDate donationDate, String fiscalYear,
                         String channel, String campaignTitle, boolean tax80g) {
        if (receiptNumber != null) return;
        this.receiptNumber = number; this.verifyCode = verifyCode; this.donorName = donorName; this.ngoName = ngoName;
        this.tax80g = tax80g;
        this.donationDate = donationDate; this.fiscalYear = fiscalYear; this.channel = channel; this.campaignTitle = campaignTitle;
        touch();
    }

    public void generated(String key, String sha, long size, UUID templateId, Integer templateVersion,
                          boolean tax80g, boolean hasRecipient, Instant at) {
        this.pdfKey = key; this.pdfSha256 = sha; this.pdfSize = size;
        this.templateId = templateId; this.templateVersion = templateVersion; this.tax80g = tax80g; this.issuedAt = at;
        this.status = hasRecipient ? Status.DELIVERY_PENDING : Status.GENERATED;
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

    public void notified(Instant at) { this.notifiedAt = at; touch(); }

    public void delivered(Instant at) {
        if (status == Status.DELIVERY_PENDING || status == Status.GENERATED) { status = Status.DELIVERED; deliveredAt = at; touch(); }
    }

    public void voidIt(String reason, UUID by, boolean reissued, Instant at) {
        status = reissued ? Status.REISSUED : Status.VOIDED; voidReason = reason; voidBy = by; voidedAt = at; touch();
    }

    public void setReissueOf(UUID id) { this.reissueOf = id; }
    public void retryNow(Instant now) { this.nextAttemptAt = now; touch(); }
    private void touch() { updatedAt = Instant.now(); }

    public boolean isLive() { return status != Status.VOIDED && status != Status.REISSUED; }
    public boolean isIssued() { return pdfKey != null; }

    public UUID getId() { return id; }
    public UUID getNgoId() { return ngoId; }
    public UUID getDonationId() { return donationId; }
    public UUID getDonorId() { return donorId; }
    public UUID getDonorUserId() { return donorUserId; }
    public String getDonorName() { return donorName; }
    public String getNgoName() { return ngoName; }
    public String getReceiptNumber() { return receiptNumber; }
    public BigDecimal getAmount() { return amount; }
    public Status getStatus() { return status; }
    public UUID getTemplateId() { return templateId; }
    public Integer getTemplateVersion() { return templateVersion; }
    public boolean isTax80g() { return tax80g; }
    public String getPdfKey() { return pdfKey; }
    public String getPdfSha256() { return pdfSha256; }
    public Long getPdfSize() { return pdfSize; }
    public LocalDate getDonationDate() { return donationDate; }
    public String getFiscalYear() { return fiscalYear; }
    public String getChannel() { return channel; }
    public String getCampaignTitle() { return campaignTitle; }
    public String getVerifyCode() { return verifyCode; }
    public int getAttempts() { return attempts; }
    public String getLastError() { return lastError; }
    public Instant getNextAttemptAt() { return nextAttemptAt; }
    public Instant getNotifiedAt() { return notifiedAt; }
    public UUID getReissueOf() { return reissueOf; }
    public String getVoidReason() { return voidReason; }
    public UUID getVoidBy() { return voidBy; }
    public Instant getVoidedAt() { return voidedAt; }
    public Instant getIssuedAt() { return issuedAt; }
    public Instant getDeliveredAt() { return deliveredAt; }
    public Instant getCreatedAt() { return createdAt; }
}
