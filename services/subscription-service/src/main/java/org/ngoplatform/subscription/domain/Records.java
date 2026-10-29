package org.ngoplatform.subscription.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

public final class Records {
    private Records() {}

    @Entity
    @Table(name = "subscription_quotes")
    public static class Quote {
        public enum Kind { NEW_TERM, UPGRADE, ADD_BLOCKS, RENEWAL, REACTIVATION }
        public enum Status { OPEN, PAID, EXPIRED, CANCELLED, NEEDS_REVIEW }

        @Id private UUID id;
        @Column(name = "ngo_id", nullable = false) private UUID ngoId;
        @Enumerated(EnumType.STRING) @Column(nullable = false) private Kind kind;
        @Column(name = "target_plan", nullable = false) private String targetPlan;
        @Column(name = "target_cycle", nullable = false) private String targetCycle;
        @Column(name = "target_blocks", nullable = false) private int targetBlocks;
        @Column(nullable = false) private BigDecimal amount;
        @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private String breakdown;
        @Column(name = "subscription_version", nullable = false) private long subscriptionVersion;
        @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.OPEN;
        @Column(name = "expires_at", nullable = false) private Instant expiresAt;
        @Column(name = "created_by", nullable = false) private UUID createdBy;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
        @Version private long version;

        protected Quote() {}
        public Quote(UUID ngoId, Kind kind, String plan, String cycle, int blocks, BigDecimal amount, String breakdown,
                     long subVersion, Instant expiresAt, UUID createdBy) {
            this.id = UUID.randomUUID(); this.ngoId = ngoId; this.kind = kind; this.targetPlan = plan;
            this.targetCycle = cycle; this.targetBlocks = blocks; this.amount = amount; this.breakdown = breakdown;
            this.subscriptionVersion = subVersion; this.expiresAt = expiresAt; this.createdBy = createdBy;
        }
        public void setStatus(Status s) { this.status = s; }
        public UUID getId() { return id; }
        public UUID getNgoId() { return ngoId; }
        public Kind getKind() { return kind; }
        public String getTargetPlan() { return targetPlan; }
        public String getTargetCycle() { return targetCycle; }
        public int getTargetBlocks() { return targetBlocks; }
        public BigDecimal getAmount() { return amount; }
        public String getBreakdown() { return breakdown; }
        public long getSubscriptionVersion() { return subscriptionVersion; }
        public Status getStatus() { return status; }
        public Instant getExpiresAt() { return expiresAt; }
        public Instant getCreatedAt() { return createdAt; }
        public UUID getCreatedBy() { return createdBy; }
    }

    @Entity
    @Table(name = "subscription_payments")
    public static class Payment {
        public enum Status { CREATED, PENDING, PAID, FAILED, CANCELLED, NEEDS_REVIEW, REFUNDED }
        @Id private UUID id;
        @Column(name = "ngo_id", nullable = false) private UUID ngoId;
        @Column(name = "quote_id", nullable = false) private UUID quoteId;
        @Column(nullable = false) private String provider;
        @Column(name = "provider_order_id", nullable = false) private String providerOrderId;
        @Column(name = "provider_payment_id") private String providerPaymentId;
        @Column(nullable = false) private BigDecimal amount;
        @Column(name = "paid_amount") private BigDecimal paidAmount;
        @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.CREATED;
        @Column(name = "failure_reason") private String failureReason;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
        @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
        @Version private long version;

        protected Payment() {}
        public Payment(UUID ngoId, UUID quoteId, String provider, String orderId, BigDecimal amount) {
            this.id = UUID.randomUUID(); this.ngoId = ngoId; this.quoteId = quoteId; this.provider = provider;
            this.providerOrderId = orderId; this.amount = amount;
        }
        public void markPaid(String paymentId, BigDecimal paid) {
            this.status = Status.PAID; this.providerPaymentId = paymentId; this.paidAmount = paid; this.updatedAt = Instant.now();
        }
        public void markFailed(String reason) { this.status = Status.FAILED; this.failureReason = reason; this.updatedAt = Instant.now(); }
        public void markPending() { this.status = Status.PENDING; this.updatedAt = Instant.now(); }
        public void markReview(String paymentId, BigDecimal paid, String reason) {
            this.status = Status.NEEDS_REVIEW; this.providerPaymentId = paymentId; this.paidAmount = paid;
            this.failureReason = reason; this.updatedAt = Instant.now();
        }
        public void markCancelled() { this.status = Status.CANCELLED; this.updatedAt = Instant.now(); }
        public UUID getId() { return id; }
        public UUID getNgoId() { return ngoId; }
        public UUID getQuoteId() { return quoteId; }
        public String getProvider() { return provider; }
        public String getProviderOrderId() { return providerOrderId; }
        public String getProviderPaymentId() { return providerPaymentId; }
        public BigDecimal getAmount() { return amount; }
        public BigDecimal getPaidAmount() { return paidAmount; }
        public Status getStatus() { return status; }
        public String getFailureReason() { return failureReason; }
        public Instant getCreatedAt() { return createdAt; }
    }

    @Entity
    @Table(name = "subscription_invoices")
    public static class Invoice {
        @Id private UUID id;
        @Column(nullable = false) private String number;
        @Column(name = "ngo_id", nullable = false) private UUID ngoId;
        @Column(name = "payment_id", nullable = false) private UUID paymentId;
        @Column(nullable = false) private BigDecimal total;
        @Column(name = "taxable_amount", nullable = false) private BigDecimal taxableAmount;
        @Column(name = "gst_amount", nullable = false) private BigDecimal gstAmount;
        @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private String lines;
        @Column(name = "bill_to_name") private String billToName;
        @Column(name = "bill_to_gstin") private String billToGstin;
        @Column(name = "period_start") private Instant periodStart;
        @Column(name = "period_end") private Instant periodEnd;
        @Column(nullable = false) private String status = "ISSUED";
        @Column(name = "issued_at", nullable = false) private Instant issuedAt = Instant.now();

        protected Invoice() {}
        public Invoice(String number, UUID ngoId, UUID paymentId, BigDecimal total, BigDecimal taxable, BigDecimal gst,
                       String lines, String billToName, String gstin, Instant start, Instant end) {
            this.id = UUID.randomUUID(); this.number = number; this.ngoId = ngoId; this.paymentId = paymentId;
            this.total = total; this.taxableAmount = taxable; this.gstAmount = gst; this.lines = lines;
            this.billToName = billToName; this.billToGstin = gstin; this.periodStart = start; this.periodEnd = end;
        }
        public UUID getId() { return id; }
        public String getNumber() { return number; }
        public UUID getNgoId() { return ngoId; }
        public UUID getPaymentId() { return paymentId; }
        public BigDecimal getTotal() { return total; }
        public BigDecimal getTaxableAmount() { return taxableAmount; }
        public BigDecimal getGstAmount() { return gstAmount; }
        public String getLines() { return lines; }
        public String getBillToName() { return billToName; }
        public String getBillToGstin() { return billToGstin; }
        public Instant getPeriodStart() { return periodStart; }
        public Instant getPeriodEnd() { return periodEnd; }
        public String getStatus() { return status; }
        public Instant getIssuedAt() { return issuedAt; }
    }

    @Entity
    @Table(name = "subscription_changes")
    public static class Change {
        @Id private UUID id;
        @Column(name = "ngo_id", nullable = false) private UUID ngoId;
        @Column(nullable = false) private String kind;
        @Column(name = "from_plan") private String fromPlan;
        @Column(name = "to_plan") private String toPlan;
        @Column(name = "from_status") private String fromStatus;
        @Column(name = "to_status") private String toStatus;
        private Integer blocks;
        private BigDecimal amount;
        @Column(name = "actor_id") private UUID actorId;
        private String note;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

        protected Change() {}
        public Change(UUID ngoId, String kind, String fromPlan, String toPlan, String fromStatus, String toStatus,
                      Integer blocks, BigDecimal amount, UUID actor, String note) {
            this.id = UUID.randomUUID(); this.ngoId = ngoId; this.kind = kind; this.fromPlan = fromPlan;
            this.toPlan = toPlan; this.fromStatus = fromStatus; this.toStatus = toStatus; this.blocks = blocks;
            this.amount = amount; this.actorId = actor; this.note = note;
        }
        public String getKind() { return kind; }
        public String getFromPlan() { return fromPlan; }
        public String getToPlan() { return toPlan; }
        public String getFromStatus() { return fromStatus; }
        public String getToStatus() { return toStatus; }
        public Integer getBlocks() { return blocks; }
        public BigDecimal getAmount() { return amount; }
        public String getNote() { return note; }
        public Instant getCreatedAt() { return createdAt; }
    }

    @Entity
    @Table(name = "subscription_credits")
    public static class Credit {
        @Id private UUID id;
        @Column(name = "ngo_id", nullable = false) private UUID ngoId;
        @Column(nullable = false) private BigDecimal amount;
        @Column(nullable = false) private String reason;
        @Column(name = "payment_id") private UUID paymentId;
        @Column(nullable = false) private String status;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
        @Column(name = "resolved_at") private Instant resolvedAt;

        protected Credit() {}
        public Credit(UUID ngoId, BigDecimal amount, String reason, UUID paymentId, String status) {
            this.id = UUID.randomUUID(); this.ngoId = ngoId; this.amount = amount; this.reason = reason;
            this.paymentId = paymentId; this.status = status;
        }
        public void resolve(String status) { this.status = status; this.resolvedAt = Instant.now(); }
        public UUID getId() { return id; }
        public UUID getNgoId() { return ngoId; }
        public BigDecimal getAmount() { return amount; }
        public String getReason() { return reason; }
        public UUID getPaymentId() { return paymentId; }
        public String getStatus() { return status; }
        public Instant getCreatedAt() { return createdAt; }
    }

    @Entity
    @Table(name = "subscription_disputes")
    public static class Dispute {
        @Id private UUID id;
        @Column(name = "ngo_id", nullable = false) private UUID ngoId;
        @Column(name = "payment_id") private UUID paymentId;
        @Column(nullable = false) private String kind;
        @Column(nullable = false) private String reason;
        @Column(nullable = false) private String status = "OPEN";
        private String resolution;
        @Column(name = "created_by", nullable = false) private UUID createdBy;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
        @Column(name = "resolved_at") private Instant resolvedAt;

        protected Dispute() {}
        public Dispute(UUID ngoId, UUID paymentId, String kind, String reason, UUID by) {
            this.id = UUID.randomUUID(); this.ngoId = ngoId; this.paymentId = paymentId; this.kind = kind;
            this.reason = reason; this.createdBy = by;
        }
        public void resolve(boolean approved, String resolution) {
            this.status = approved ? "APPROVED" : "REJECTED"; this.resolution = resolution; this.resolvedAt = Instant.now();
        }
        public UUID getId() { return id; }
        public UUID getNgoId() { return ngoId; }
        public UUID getPaymentId() { return paymentId; }
        public String getKind() { return kind; }
        public String getReason() { return reason; }
        public String getStatus() { return status; }
        public String getResolution() { return resolution; }
        public Instant getCreatedAt() { return createdAt; }
    }
}
