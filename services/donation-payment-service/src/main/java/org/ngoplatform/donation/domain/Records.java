package org.ngoplatform.donation.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

public final class Records {
    private Records() {}

    @Entity
    @Table(name = "payment_settings")
    public static class PaymentSettings {
        @Id @Column(name = "ngo_id") private UUID ngoId;
        @Column(nullable = false) private String provider;
        @Column(name = "merchant_reference", nullable = false) private String merchantReference;
        @Column(nullable = false) private String status;
        @Column(name = "guest_checkout_allowed", nullable = false) private boolean guestCheckoutAllowed = true;
        @Column(name = "min_amount", nullable = false) private BigDecimal minAmount;
        @Column(name = "max_amount", nullable = false) private BigDecimal maxAmount;
        @Column(name = "receipt_prefix", nullable = false) private String receiptPrefix = "RCPT";
        @Column(name = "updated_by") private UUID updatedBy;
        @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
        @Version private long version;
        protected PaymentSettings() {}
        public PaymentSettings(UUID ngoId) { this.ngoId = ngoId; }
        public void update(String provider, String merchant, String status, boolean guest, BigDecimal min, BigDecimal max,
                           String prefix, UUID by) {
            this.provider = provider; this.merchantReference = merchant; this.status = status; this.guestCheckoutAllowed = guest;
            this.minAmount = min; this.maxAmount = max; this.receiptPrefix = prefix; this.updatedBy = by; this.updatedAt = Instant.now();
        }
        public UUID getNgoId() { return ngoId; }
        public String getProvider() { return provider; }
        public String getMerchantReference() { return merchantReference; }
        public String getStatus() { return status; }
        public boolean isGuestCheckoutAllowed() { return guestCheckoutAllowed; }
        public BigDecimal getMinAmount() { return minAmount; }
        public BigDecimal getMaxAmount() { return maxAmount; }
        public String getReceiptPrefix() { return receiptPrefix; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "donation_payments")
    public static class Payment {
        @Id private UUID id;
        @Column(name = "donation_id", nullable = false) private UUID donationId;
        @Column(name = "ngo_id", nullable = false) private UUID ngoId;
        @Column(nullable = false) private String provider;
        @Column(name = "provider_order_id", nullable = false) private String providerOrderId;
        @Column(name = "provider_payment_id") private String providerPaymentId;
        @Column(nullable = false) private BigDecimal amount;
        private String scenario;
        @Column(nullable = false) private String status;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
        @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
        @Version private long version;
        protected Payment() {}
        public Payment(UUID donationId, UUID ngoId, String provider, String orderId, BigDecimal amount) {
            this.id = UUID.randomUUID(); this.donationId = donationId; this.ngoId = ngoId; this.provider = provider;
            this.providerOrderId = orderId; this.amount = amount; this.status = "CREATED";
        }
        public void update(String status, String paymentId) {
            this.status = status; if (paymentId != null) this.providerPaymentId = paymentId; this.updatedAt = Instant.now();
        }
        public void setScenario(String s) { this.scenario = s; }
        public UUID getId() { return id; }
        public UUID getDonationId() { return donationId; }
        public UUID getNgoId() { return ngoId; }
        public String getProvider() { return provider; }
        public String getProviderOrderId() { return providerOrderId; }
        public String getProviderPaymentId() { return providerPaymentId; }
        public BigDecimal getAmount() { return amount; }
        public String getScenario() { return scenario; }
        public String getStatus() { return status; }
        public Instant getCreatedAt() { return createdAt; }
    }

    @Entity
    @Immutable
    @Table(name = "payment_events")
    public static class PaymentEvent {
        @Id private UUID id;
        @Column(nullable = false) private String provider;
        @Column(name = "provider_event_id", nullable = false) private String providerEventId;
        @Column(name = "donation_id") private UUID donationId;
        @Column(name = "event_type", nullable = false) private String eventType;
        @Column(name = "signature_valid", nullable = false) private boolean signatureValid;
        @Column(nullable = false) private String outcome;
        @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private String payload;
        @Column(name = "received_at", nullable = false) private Instant receivedAt = Instant.now();
        protected PaymentEvent() {}
        public PaymentEvent(String provider, String eventId, UUID donationId, String type, boolean valid, String outcome, String payload) {
            this.id = UUID.randomUUID(); this.provider = provider; this.providerEventId = eventId; this.donationId = donationId;
            this.eventType = type; this.signatureValid = valid; this.outcome = outcome; this.payload = payload;
        }
        public String getEventType() { return eventType; }
        public String getOutcome() { return outcome; }
        public boolean isSignatureValid() { return signatureValid; }
        public String getProviderEventId() { return providerEventId; }
        public Instant getReceivedAt() { return receivedAt; }
    }

    @Entity
    @Table(name = "refunds")
    public static class Refund {
        public enum Status { REQUESTED, APPROVED, REJECTED, PROCESSING, SUCCEEDED, FAILED }
        @Id private UUID id;
        @Column(name = "donation_id", nullable = false) private UUID donationId;
        @Column(name = "ngo_id", nullable = false) private UUID ngoId;
        @Column(nullable = false) private BigDecimal amount;
        @Column(nullable = false) private String reason;
        @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.REQUESTED;
        @Column(name = "requested_by", nullable = false) private UUID requestedBy;
        @Column(name = "decided_by") private UUID decidedBy;
        @Column(name = "provider_refund_id") private String providerRefundId;
        @Column(name = "failure_reason") private String failureReason;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
        @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
        @Version private long version;
        protected Refund() {}
        public Refund(UUID donationId, UUID ngoId, BigDecimal amount, String reason, UUID by) {
            this.id = UUID.randomUUID(); this.donationId = donationId; this.ngoId = ngoId; this.amount = amount;
            this.reason = reason; this.requestedBy = by;
        }
        public void decide(boolean approve, UUID by) { this.status = approve ? Status.APPROVED : Status.REJECTED; this.decidedBy = by; touch(); }
        public void processing(String providerRefundId) { this.status = Status.PROCESSING; this.providerRefundId = providerRefundId; touch(); }
        public void succeeded() { this.status = Status.SUCCEEDED; touch(); }
        public void failed(String reason) { this.status = Status.FAILED; this.failureReason = reason; touch(); }
        private void touch() { this.updatedAt = Instant.now(); }
        public UUID getId() { return id; }
        public UUID getDonationId() { return donationId; }
        public UUID getNgoId() { return ngoId; }
        public BigDecimal getAmount() { return amount; }
        public String getReason() { return reason; }
        public Status getStatus() { return status; }
        public UUID getRequestedBy() { return requestedBy; }
        public UUID getDecidedBy() { return decidedBy; }
        public String getProviderRefundId() { return providerRefundId; }
        public String getFailureReason() { return failureReason; }
        public Instant getCreatedAt() { return createdAt; }
    }

    @Entity
    @Table(name = "settlement_batches")
    public static class SettlementBatch {
        @Id private UUID id;
        @Column(name = "ngo_id", nullable = false) private UUID ngoId;
        @Column(nullable = false) private String provider;
        @Column(name = "provider_settlement_id", nullable = false) private String providerSettlementId;
        @Column(name = "settled_on", nullable = false) private LocalDate settledOn;
        @Column(name = "gross_amount", nullable = false) private BigDecimal grossAmount;
        @Column(name = "fee_amount", nullable = false) private BigDecimal feeAmount;
        @Column(name = "tax_amount", nullable = false) private BigDecimal taxAmount;
        @Column(name = "net_amount", nullable = false) private BigDecimal netAmount;
        @Column(name = "bank_reference") private String bankReference;
        @Column(nullable = false) private String status = "RECEIVED";
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
        protected SettlementBatch() {}
        public SettlementBatch(UUID ngoId, String provider, String settlementId, LocalDate on, BigDecimal gross, BigDecimal fee,
                               BigDecimal tax, BigDecimal net, String bankRef) {
            this.id = UUID.randomUUID(); this.ngoId = ngoId; this.provider = provider; this.providerSettlementId = settlementId;
            this.settledOn = on; this.grossAmount = gross; this.feeAmount = fee; this.taxAmount = tax; this.netAmount = net;
            this.bankReference = bankRef;
        }
        public void setStatus(String s) { this.status = s; }
        public UUID getId() { return id; }
        public UUID getNgoId() { return ngoId; }
        public String getProvider() { return provider; }
        public String getProviderSettlementId() { return providerSettlementId; }
        public LocalDate getSettledOn() { return settledOn; }
        public BigDecimal getGrossAmount() { return grossAmount; }
        public BigDecimal getFeeAmount() { return feeAmount; }
        public BigDecimal getTaxAmount() { return taxAmount; }
        public BigDecimal getNetAmount() { return netAmount; }
        public String getBankReference() { return bankReference; }
        public String getStatus() { return status; }
    }

    @Entity
    @Table(name = "settlement_lines")
    public static class SettlementLine {
        @Id private UUID id;
        @Column(name = "batch_id", nullable = false) private UUID batchId;
        @Column(name = "provider_payment_id", nullable = false) private String providerPaymentId;
        @Column(name = "donation_id") private UUID donationId;
        @Column(name = "gross_amount", nullable = false) private BigDecimal grossAmount;
        @Column(name = "fee_amount", nullable = false) private BigDecimal feeAmount;
        @Column(name = "tax_amount", nullable = false) private BigDecimal taxAmount;
        @Column(name = "net_amount", nullable = false) private BigDecimal netAmount;
        @Column(nullable = false) private boolean matched;
        protected SettlementLine() {}
        public SettlementLine(UUID batchId, String paymentId, BigDecimal gross, BigDecimal fee, BigDecimal tax, BigDecimal net) {
            this.id = UUID.randomUUID(); this.batchId = batchId; this.providerPaymentId = paymentId; this.grossAmount = gross;
            this.feeAmount = fee; this.taxAmount = tax; this.netAmount = net;
        }
        public void match(UUID donationId) { this.donationId = donationId; this.matched = true; }
        public String getProviderPaymentId() { return providerPaymentId; }
        public UUID getDonationId() { return donationId; }
        public BigDecimal getGrossAmount() { return grossAmount; }
        public BigDecimal getFeeAmount() { return feeAmount; }
        public BigDecimal getTaxAmount() { return taxAmount; }
        public BigDecimal getNetAmount() { return netAmount; }
        public boolean isMatched() { return matched; }
    }

    @Entity
    @Table(name = "reconciliation_runs")
    public static class ReconRun {
        @Id private UUID id;
        @Column(name = "ngo_id", nullable = false) private UUID ngoId;
        @Column(name = "started_by") private UUID startedBy;
        @Column(name = "started_at", nullable = false) private Instant startedAt = Instant.now();
        @Column(name = "finished_at") private Instant finishedAt;
        @Column(nullable = false) private int batches;
        @Column(nullable = false) private int matched;
        @Column(nullable = false) private int exceptions;
        protected ReconRun() {}
        public ReconRun(UUID ngoId, UUID by) { this.id = UUID.randomUUID(); this.ngoId = ngoId; this.startedBy = by; }
        public void finish(int batches, int matched, int exceptions) {
            this.batches = batches; this.matched = matched; this.exceptions = exceptions; this.finishedAt = Instant.now();
        }
        public UUID getId() { return id; }
        public Instant getStartedAt() { return startedAt; }
        public Instant getFinishedAt() { return finishedAt; }
        public int getBatches() { return batches; }
        public int getMatched() { return matched; }
        public int getExceptions() { return exceptions; }
    }

    @Entity
    @Table(name = "reconciliation_exceptions")
    public static class ReconException {
        @Id private UUID id;
        @Column(name = "ngo_id", nullable = false) private UUID ngoId;
        @Column(name = "run_id") private UUID runId;
        @Column(nullable = false) private String kind;
        @Column(name = "donation_id") private UUID donationId;
        private String reference;
        private BigDecimal expected;
        private BigDecimal actual;
        @Column(nullable = false) private String status = "OPEN";
        private String note;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
        @Column(name = "resolved_by") private UUID resolvedBy;
        @Column(name = "resolved_at") private Instant resolvedAt;
        protected ReconException() {}
        public ReconException(UUID ngoId, UUID runId, String kind, UUID donationId, String reference, BigDecimal expected, BigDecimal actual) {
            this.id = UUID.randomUUID(); this.ngoId = ngoId; this.runId = runId; this.kind = kind; this.donationId = donationId;
            this.reference = reference; this.expected = expected; this.actual = actual;
        }
        public void resolve(String status, String note, UUID by) { this.status = status; this.note = note; this.resolvedBy = by; this.resolvedAt = Instant.now(); }
        public UUID getId() { return id; }
        public UUID getNgoId() { return ngoId; }
        public String getKind() { return kind; }
        public UUID getDonationId() { return donationId; }
        public String getReference() { return reference; }
        public BigDecimal getExpected() { return expected; }
        public BigDecimal getActual() { return actual; }
        public String getStatus() { return status; }
        public String getNote() { return note; }
        public Instant getCreatedAt() { return createdAt; }
    }
}
