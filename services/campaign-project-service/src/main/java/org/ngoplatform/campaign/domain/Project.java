package org.ngoplatform.campaign.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "projects")
public class Project {
    public enum Status { PLANNED, ACTIVE, ON_HOLD, COMPLETED, CLOSED }

    @Id private UUID id;
    @Column(name = "ngo_id", nullable = false) private UUID ngoId;
    @Column(nullable = false) private String name;
    private String description;
    private String location;
    @Column(name = "start_date", nullable = false) private LocalDate startDate;
    @Column(name = "end_date") private LocalDate endDate;
    @Column(name = "budget_amount", nullable = false) private BigDecimal budgetAmount = BigDecimal.ZERO;
    @Column(name = "spent_amount", nullable = false) private BigDecimal spentAmount = BigDecimal.ZERO;
    @Column(name = "funds_raised", nullable = false) private BigDecimal fundsRaised = BigDecimal.ZERO;
    @Column(nullable = false) private int progress;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.PLANNED;
    @Column(name = "created_by", nullable = false) private UUID createdBy;
    @Column(name = "closed_at") private Instant closedAt;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version private long version;

    protected Project() {}

    public Project(UUID ngoId, UUID by) { this.id = UUID.randomUUID(); this.ngoId = ngoId; this.createdBy = by; }

    public void edit(String name, String description, String location, LocalDate start, LocalDate end) {
        this.name = name; this.description = description; this.location = location; this.startDate = start; this.endDate = end; touch();
    }
    public void setBudget(BigDecimal b) { budgetAmount = b; touch(); }
    public void setStatus(Status s) { status = s; if (s == Status.CLOSED) closedAt = Instant.now(); touch(); }
    public void setProgress(int p) { progress = p; touch(); }
    public void addSpent(BigDecimal a) { spentAmount = spentAmount.add(a); touch(); }
    public void addRaised(BigDecimal a) { fundsRaised = fundsRaised.add(a); touch(); }
    private void touch() { updatedAt = Instant.now(); }

    public boolean countsAsActive() { return status == Status.PLANNED || status == Status.ACTIVE || status == Status.ON_HOLD; }

    public UUID getId() { return id; }
    public UUID getNgoId() { return ngoId; }
    public String getName() { return name; }
    public String getDescription() { return description; }
    public String getLocation() { return location; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public BigDecimal getBudgetAmount() { return budgetAmount; }
    public BigDecimal getSpentAmount() { return spentAmount; }
    public BigDecimal getFundsRaised() { return fundsRaised; }
    public int getProgress() { return progress; }
    public Status getStatus() { return status; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
}
