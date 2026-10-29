package org.ngoplatform.subscription.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.ngoplatform.commons.error.ApiException;

@Entity
@Table(name = "ngo_subscriptions")
public class Subscription {
    public enum Status { TRIAL, ACTIVE, GRACE, SUSPENDED, CANCELLED, EXPIRED, ARCHIVED }
    public enum Cycle { TRIAL, QUARTERLY, ANNUAL }

    /** Explicit state machine (spec §7.1). */
    static final Map<Status, Set<Status>> TRANSITIONS = Map.of(
            Status.TRIAL, EnumSet.of(Status.ACTIVE, Status.EXPIRED, Status.CANCELLED),
            Status.ACTIVE, EnumSet.of(Status.ACTIVE, Status.GRACE, Status.CANCELLED),
            Status.GRACE, EnumSet.of(Status.ACTIVE, Status.SUSPENDED),
            Status.SUSPENDED, EnumSet.of(Status.ACTIVE, Status.ARCHIVED),
            Status.CANCELLED, EnumSet.of(Status.ACTIVE, Status.ARCHIVED),
            Status.EXPIRED, EnumSet.of(Status.ACTIVE, Status.ARCHIVED),
            Status.ARCHIVED, EnumSet.of(Status.ACTIVE));

    @Id private UUID id;
    @Column(name = "ngo_id", nullable = false, unique = true) private UUID ngoId;
    @Column(name = "plan_code", nullable = false) private String planCode;
    @Enumerated(EnumType.STRING) @Column(name = "billing_cycle", nullable = false) private Cycle cycle;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status;
    @Column(name = "trial_used", nullable = false) private boolean trialUsed;
    @Column(name = "current_period_start", nullable = false) private Instant periodStart;
    @Column(name = "current_period_end", nullable = false) private Instant periodEnd;
    @Column(name = "grace_ends_at") private Instant graceEndsAt;
    @Column(name = "contracted_period_price", nullable = false) private BigDecimal contractedPeriodPrice = BigDecimal.ZERO;
    @Column(name = "contracted_block_price", nullable = false) private BigDecimal contractedBlockPrice = BigDecimal.ZERO;
    @Column(name = "staff_blocks", nullable = false) private int staffBlocks;
    @Column(name = "scheduled_staff_blocks") private Integer scheduledStaffBlocks;
    @Column(name = "scheduled_plan_code") private String scheduledPlanCode;
    @Enumerated(EnumType.STRING) @Column(name = "scheduled_cycle") private Cycle scheduledCycle;
    @Column(name = "auto_renew", nullable = false) private boolean autoRenew;
    @Column(name = "cancel_at_period_end", nullable = false) private boolean cancelAtPeriodEnd;
    @Column(name = "billing_contact_name") private String billingContactName;
    @Column(name = "billing_contact_email") private String billingContactEmail;
    private String gstin;
    @Column(name = "suspended_at") private Instant suspendedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version private long version;

    protected Subscription() {}

    public static Subscription startTrial(UUID ngoId, String plan, Instant now, Instant end) {
        Subscription s = new Subscription();
        s.id = UUID.randomUUID(); s.ngoId = ngoId; s.planCode = plan; s.cycle = Cycle.TRIAL;
        s.status = Status.TRIAL; s.trialUsed = true; s.periodStart = now; s.periodEnd = end;
        return s;
    }

    public void transition(Status to) {
        if (!TRANSITIONS.getOrDefault(status, Set.of()).contains(to)) {
            throw ApiException.rule("INVALID_SUBSCRIPTION_TRANSITION", "Cannot move subscription from " + status + " to " + to);
        }
        this.status = to;
        if (to == Status.SUSPENDED) suspendedAt = Instant.now();
        if (to == Status.ACTIVE) { suspendedAt = null; graceEndsAt = null; }
        touch();
    }

    /** Starts a new paid term (trial conversion, renewal, reactivation). */
    public void startTerm(String plan, Cycle cycle, Instant start, Instant end, BigDecimal planPrice, int blocks,
                          BigDecimal blockPrice) {
        if (status != Status.ACTIVE) transition(Status.ACTIVE);
        this.planCode = plan; this.cycle = cycle; this.periodStart = start; this.periodEnd = end;
        this.contractedPeriodPrice = planPrice; this.staffBlocks = blocks; this.contractedBlockPrice = blockPrice;
        this.scheduledPlanCode = null; this.scheduledCycle = null; this.scheduledStaffBlocks = null;
        this.cancelAtPeriodEnd = false; this.graceEndsAt = null;
        touch();
    }

    public void upgrade(String plan, BigDecimal newPeriodPrice) {
        this.planCode = plan; this.contractedPeriodPrice = newPeriodPrice;
        if (scheduledPlanCode != null) scheduledPlanCode = null; // an upgrade supersedes a pending downgrade
        touch();
    }

    public void addBlocks(int blocks, BigDecimal blockPrice) {
        this.staffBlocks += blocks; this.contractedBlockPrice = blockPrice; touch();
    }

    public void changeTrialPlan(String plan) { this.planCode = plan; touch(); }
    public void scheduleDowngrade(String plan, Cycle cycle) { this.scheduledPlanCode = plan; this.scheduledCycle = cycle; touch(); }
    public void cancelScheduledDowngrade() { this.scheduledPlanCode = null; this.scheduledCycle = null; touch(); }
    public void scheduleBlocks(Integer blocks) { this.scheduledStaffBlocks = blocks; touch(); }
    public void setAutoRenew(boolean v) { this.autoRenew = v; touch(); }
    public void setCancelAtPeriodEnd(boolean v) { this.cancelAtPeriodEnd = v; touch(); }
    public void enterGrace(Instant until) { transition(Status.GRACE); this.graceEndsAt = until; }
    public void billingContact(String name, String email, String gstin) {
        this.billingContactName = name; this.billingContactEmail = email; this.gstin = gstin; touch();
    }
    private void touch() { this.updatedAt = Instant.now(); }

    public boolean isPaidTerm() { return cycle == Cycle.QUARTERLY || cycle == Cycle.ANNUAL; }

    public UUID getId() { return id; }
    public UUID getNgoId() { return ngoId; }
    public String getPlanCode() { return planCode; }
    public Cycle getCycle() { return cycle; }
    public Status getStatus() { return status; }
    public boolean isTrialUsed() { return trialUsed; }
    public Instant getPeriodStart() { return periodStart; }
    public Instant getPeriodEnd() { return periodEnd; }
    public Instant getGraceEndsAt() { return graceEndsAt; }
    public BigDecimal getContractedPeriodPrice() { return contractedPeriodPrice; }
    public BigDecimal getContractedBlockPrice() { return contractedBlockPrice; }
    public int getStaffBlocks() { return staffBlocks; }
    public Integer getScheduledStaffBlocks() { return scheduledStaffBlocks; }
    public String getScheduledPlanCode() { return scheduledPlanCode; }
    public Cycle getScheduledCycle() { return scheduledCycle; }
    public boolean isAutoRenew() { return autoRenew; }
    public boolean isCancelAtPeriodEnd() { return cancelAtPeriodEnd; }
    public String getBillingContactName() { return billingContactName; }
    public String getBillingContactEmail() { return billingContactEmail; }
    public String getGstin() { return gstin; }
    public Instant getSuspendedAt() { return suspendedAt; }
    public long getVersion() { return version; }
}
