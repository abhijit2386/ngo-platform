package org.ngoplatform.campaign.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import org.ngoplatform.campaign.app.AccountRules;

/** One bill paid for a campaign. Workflow and invariants: {@link AccountRules}; DB trigger keeps approved rows fixed. */
@Entity
@Table(name = "campaign_expenses")
public class Expense {
    @Id private UUID id;
    @Column(name = "ngo_id", nullable = false) private UUID ngoId;
    @Column(name = "campaign_id", nullable = false) private UUID campaignId;
    @Column(name = "budget_line_id") private UUID budgetLineId;
    @Column(nullable = false) private String category;
    @Column(nullable = false) private String description;
    @Column(nullable = false) private BigDecimal amount;
    @Column(name = "expense_date", nullable = false) private LocalDate expenseDate;
    @Column(name = "vendor_id") private UUID vendorId;
    @Column(name = "vendor_name") private String vendorName;
    @Column(name = "bill_number") private String billNumber;
    @Column(name = "no_bill_reason") private String noBillReason;
    @Column(name = "payment_mode", nullable = false) private String paymentMode;
    @Column(name = "paid_by", nullable = false) private String paidBy;
    @Column(name = "paid_by_user") private UUID paidByUser;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private AccountRules.Status status = AccountRules.Status.SUBMITTED;
    @Column(name = "voucher_no") private String voucherNo;
    @Column(name = "submitted_by", nullable = false) private UUID submittedBy;
    @Column(name = "submitted_at", nullable = false) private Instant submittedAt = Instant.now();
    @Column(name = "decided_by") private UUID decidedBy;
    @Column(name = "decided_at") private Instant decidedAt;
    @Column(name = "rejection_reason") private String rejectionReason;
    @Column(name = "self_approved", nullable = false) private boolean selfApproved;
    @Column(name = "voided_by") private UUID voidedBy;
    @Column(name = "voided_at") private Instant voidedAt;
    @Column(name = "void_reason") private String voidReason;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version private long version;

    protected Expense() {}

    public Expense(UUID ngoId, UUID campaignId, UUID submittedBy) {
        this.id = UUID.randomUUID(); this.ngoId = ngoId; this.campaignId = campaignId; this.submittedBy = submittedBy;
    }

    public void edit(UUID budgetLineId, String category, String description, BigDecimal amount, LocalDate date, UUID vendorId,
                     String vendorName, String billNumber, String noBillReason, String mode, String paidBy, UUID paidByUser) {
        this.budgetLineId = budgetLineId; this.category = category; this.description = description; this.amount = amount;
        this.expenseDate = date; this.vendorId = vendorId; this.vendorName = vendorName; this.billNumber = billNumber;
        this.noBillReason = noBillReason; this.paymentMode = mode; this.paidBy = paidBy; this.paidByUser = paidByUser;
        touch();
    }

    /** Editing a pending or rejected expense (re)submits it. */
    public void resubmit() {
        status = AccountRules.Status.SUBMITTED; submittedAt = Instant.now(); rejectionReason = null; decidedBy = null; decidedAt = null;
        touch();
    }

    public void approve(UUID admin, String voucherNo, boolean self) {
        status = AccountRules.Status.APPROVED; decidedBy = admin; decidedAt = Instant.now(); this.voucherNo = voucherNo;
        selfApproved = self; touch();
    }

    public void reject(UUID admin, String reason) {
        status = AccountRules.Status.REJECTED; decidedBy = admin; decidedAt = Instant.now(); rejectionReason = reason; touch();
    }

    public void voidIt(UUID admin, String reason) {
        status = AccountRules.Status.VOID; voidedBy = admin; voidedAt = Instant.now(); voidReason = reason; touch();
    }

    private void touch() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getNgoId() { return ngoId; }
    public UUID getCampaignId() { return campaignId; }
    public UUID getBudgetLineId() { return budgetLineId; }
    public String getCategory() { return category; }
    public String getDescription() { return description; }
    public BigDecimal getAmount() { return amount; }
    public LocalDate getExpenseDate() { return expenseDate; }
    public UUID getVendorId() { return vendorId; }
    public String getVendorName() { return vendorName; }
    public String getBillNumber() { return billNumber; }
    public String getNoBillReason() { return noBillReason; }
    public String getPaymentMode() { return paymentMode; }
    public String getPaidBy() { return paidBy; }
    public UUID getPaidByUser() { return paidByUser; }
    public AccountRules.Status getStatus() { return status; }
    public String getVoucherNo() { return voucherNo; }
    public UUID getSubmittedBy() { return submittedBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public UUID getDecidedBy() { return decidedBy; }
    public Instant getDecidedAt() { return decidedAt; }
    public String getRejectionReason() { return rejectionReason; }
    public boolean isSelfApproved() { return selfApproved; }
    public UUID getVoidedBy() { return voidedBy; }
    public Instant getVoidedAt() { return voidedAt; }
    public String getVoidReason() { return voidReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public long getVersion() { return version; }
}
