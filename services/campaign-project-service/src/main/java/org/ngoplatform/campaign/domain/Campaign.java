package org.ngoplatform.campaign.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "campaigns")
public class Campaign {
    public enum Status { DRAFT, PENDING_APPROVAL, PUBLISHED, CLOSED, ARCHIVED }

    @Id private UUID id;
    @Column(name = "ngo_id", nullable = false) private UUID ngoId;
    @Column(nullable = false) private String title;
    @Column(nullable = false) private String slug;
    @Column(nullable = false) private String summary;
    private String story;
    private String cause;
    @Column(name = "goal_amount", nullable = false) private BigDecimal goalAmount;
    @Column(name = "raised_amount", nullable = false) private BigDecimal raisedAmount = BigDecimal.ZERO;
    @Column(name = "donation_count", nullable = false) private int donationCount;
    @Column(name = "start_date", nullable = false) private LocalDate startDate;
    @Column(name = "end_date") private LocalDate endDate;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.DRAFT;
    @Column(name = "image_key") private String imageKey;
    @Column(name = "project_id") private UUID projectId;
    @Column(name = "created_by", nullable = false) private UUID createdBy;
    @Column(name = "approved_by") private UUID approvedBy;
    @Column(name = "published_at") private Instant publishedAt;
    @Column(name = "closed_at") private Instant closedAt;
    @Column(name = "submitted_by") private UUID submittedBy;
    @Column(name = "submitted_at") private Instant submittedAt;
    @Column(name = "rejection_reason") private String rejectionReason;
    @Column(name = "rejected_by") private UUID rejectedBy;
    @Column(name = "rejected_at") private Instant rejectedAt;
    @Column(name = "updated_by") private UUID updatedBy;
    @Column(name = "ngo_name") private String ngoName;
    @Column(name = "ngo_city") private String ngoCity;
    @Column(name = "image_version", nullable = false) private int imageVersion;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version private long version;

    protected Campaign() {}

    public Campaign(UUID ngoId, UUID createdBy) { this.id = UUID.randomUUID(); this.ngoId = ngoId; this.createdBy = createdBy; }

    public void edit(String title, String slug, String summary, String story, String cause, BigDecimal goal, LocalDate start,
                     LocalDate end, UUID projectId) {
        this.title = title; this.slug = slug; this.summary = summary; this.story = story; this.cause = cause;
        this.goalAmount = goal; this.startDate = start; this.endDate = end; this.projectId = projectId; touch();
    }

    public void editedBy(UUID actor) { updatedBy = actor; }

    public void submit(UUID actor) {
        status = Status.PENDING_APPROVAL; submittedBy = actor; submittedAt = Instant.now(); rejectionReason = null; touch();
    }

    public void withdraw() { status = Status.DRAFT; touch(); }

    public void publish(UUID approver, String ngoName, String ngoCity) {
        status = Status.PUBLISHED; approvedBy = approver; publishedAt = Instant.now(); rejectionReason = null;
        this.ngoName = ngoName; this.ngoCity = ngoCity; touch();
    }

    /** Sent back to the team as a draft with the administrator's reason. */
    public void reject(UUID admin, String reason) {
        status = Status.DRAFT; rejectedBy = admin; rejectedAt = Instant.now(); rejectionReason = reason; touch();
    }

    public void close() { status = Status.CLOSED; closedAt = Instant.now(); touch(); }
    public void archive() { status = Status.ARCHIVED; touch(); }
    public void setImageKey(String k) { imageKey = k; imageVersion++; touch(); }
    public void addDonation(BigDecimal amount) { raisedAmount = raisedAmount.add(amount); donationCount++; touch(); }
    public void removeAmount(BigDecimal amount) { raisedAmount = raisedAmount.subtract(amount).max(BigDecimal.ZERO); touch(); }
    private void touch() { updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getNgoId() { return ngoId; }
    public String getTitle() { return title; }
    public String getSlug() { return slug; }
    public String getSummary() { return summary; }
    public String getStory() { return story; }
    public String getCause() { return cause; }
    public BigDecimal getGoalAmount() { return goalAmount; }
    public BigDecimal getRaisedAmount() { return raisedAmount; }
    public int getDonationCount() { return donationCount; }
    public LocalDate getStartDate() { return startDate; }
    public LocalDate getEndDate() { return endDate; }
    public Status getStatus() { return status; }
    public String getImageKey() { return imageKey; }
    public UUID getProjectId() { return projectId; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getPublishedAt() { return publishedAt; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
    public UUID getApprovedBy() { return approvedBy; }
    public Instant getClosedAt() { return closedAt; }
    public UUID getSubmittedBy() { return submittedBy; }
    public Instant getSubmittedAt() { return submittedAt; }
    public String getRejectionReason() { return rejectionReason; }
    public Instant getRejectedAt() { return rejectedAt; }
    public UUID getUpdatedBy() { return updatedBy; }
    public String getNgoName() { return ngoName; }
    public String getNgoCity() { return ngoCity; }
    public int getImageVersion() { return imageVersion; }
    public Instant getUpdatedAt() { return updatedAt; }
}
