package org.ngoplatform.donor.domain;

import jakarta.persistence.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

public final class Records {
    private Records() {}

    @Entity
    @Table(name = "donor_donation_facts")
    public static class DonationFact {
        @Id @Column(name = "donation_id") private UUID donationId;
        @Column(name = "donor_id", nullable = false) private UUID donorId;
        @Column(name = "ngo_id", nullable = false) private UUID ngoId;
        @Column(nullable = false) private BigDecimal amount;
        @Column(nullable = false) private BigDecimal refunded = BigDecimal.ZERO;
        @Column(name = "campaign_id") private UUID campaignId;
        @Column(name = "paid_at", nullable = false) private Instant paidAt;
        protected DonationFact() {}
        public DonationFact(UUID donationId, UUID donorId, UUID ngoId, BigDecimal amount, UUID campaignId, Instant paidAt) {
            this.donationId = donationId; this.donorId = donorId; this.ngoId = ngoId; this.amount = amount;
            this.campaignId = campaignId; this.paidAt = paidAt;
        }
        public void reassign(UUID donorId) { this.donorId = donorId; }
        public void refund(BigDecimal total) { this.refunded = total; }
        public UUID getDonationId() { return donationId; }
        public UUID getDonorId() { return donorId; }
        public BigDecimal getAmount() { return amount; }
        public BigDecimal getRefunded() { return refunded; }
        public UUID getCampaignId() { return campaignId; }
        public Instant getPaidAt() { return paidAt; }
    }

    @Entity
    @Table(name = "donor_profiles")
    public static class Profile {
        @Id @Column(name = "user_id") private UUID userId;
        @Column(name = "display_name") private String displayName;
        private String phone;
        private String pan;
        private String city;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
        @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
        protected Profile() {}
        public Profile(UUID userId) { this.userId = userId; }
        public void update(String name, String phone, String pan, String city) {
            this.displayName = name; this.phone = phone; this.pan = pan; this.city = city; this.updatedAt = Instant.now();
        }
        public UUID getUserId() { return userId; }
        public String getDisplayName() { return displayName; }
        public String getPhone() { return phone; }
        public String getPan() { return pan; }
        public String getCity() { return city; }
    }

    @Entity
    @Table(name = "donor_preferences")
    public static class Preferences {
        @Id @Column(name = "user_id") private UUID userId;
        @Column(name = "email_receipts", nullable = false) private boolean emailReceipts = true;
        @Column(name = "campaign_updates", nullable = false) private boolean campaignUpdates = true;
        @Column(nullable = false) private boolean marketing;
        @Column(name = "push_enabled", nullable = false) private boolean pushEnabled = true;
        @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
        protected Preferences() {}
        public Preferences(UUID userId) { this.userId = userId; }
        public void update(boolean receipts, boolean updates, boolean marketing, boolean push) {
            this.emailReceipts = receipts; this.campaignUpdates = updates; this.marketing = marketing; this.pushEnabled = push;
            this.updatedAt = Instant.now();
        }
        public boolean isEmailReceipts() { return emailReceipts; }
        public boolean isCampaignUpdates() { return campaignUpdates; }
        public boolean isMarketing() { return marketing; }
        public boolean isPushEnabled() { return pushEnabled; }
    }

    @Entity
    @Table(name = "donor_consents")
    public static class Consent {
        @Id private UUID id;
        @Column(name = "user_id", nullable = false) private UUID userId;
        @Column(nullable = false) private String purpose;
        @Column(nullable = false) private boolean granted;
        @Column(name = "notice_version", nullable = false) private String noticeVersion;
        @Column(name = "recorded_at", nullable = false) private Instant recordedAt = Instant.now();
        protected Consent() {}
        public Consent(UUID userId, String purpose, boolean granted, String version) {
            this.id = UUID.randomUUID(); this.userId = userId; this.purpose = purpose; this.granted = granted; this.noticeVersion = version;
        }
    }

    public static class FollowId implements Serializable {
        private UUID userId;
        private UUID ngoId;
        public FollowId() {}
        public FollowId(UUID userId, UUID ngoId) { this.userId = userId; this.ngoId = ngoId; }
        @Override public boolean equals(Object o) {
            return o instanceof FollowId f && Objects.equals(userId, f.userId) && Objects.equals(ngoId, f.ngoId);
        }
        @Override public int hashCode() { return Objects.hash(userId, ngoId); }
    }

    @Entity
    @Table(name = "donor_follows")
    @IdClass(FollowId.class)
    public static class Follow {
        @Id @Column(name = "user_id") private UUID userId;
        @Id @Column(name = "ngo_id") private UUID ngoId;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
        protected Follow() {}
        public Follow(UUID userId, UUID ngoId) { this.userId = userId; this.ngoId = ngoId; }
        public UUID getNgoId() { return ngoId; }
        public Instant getCreatedAt() { return createdAt; }
    }

    public static class SavedId implements Serializable {
        private UUID userId;
        private UUID campaignId;
        public SavedId() {}
        public SavedId(UUID userId, UUID campaignId) { this.userId = userId; this.campaignId = campaignId; }
        @Override public boolean equals(Object o) {
            return o instanceof SavedId f && Objects.equals(userId, f.userId) && Objects.equals(campaignId, f.campaignId);
        }
        @Override public int hashCode() { return Objects.hash(userId, campaignId); }
    }

    @Entity
    @Table(name = "saved_campaigns")
    @IdClass(SavedId.class)
    public static class SavedCampaign {
        @Id @Column(name = "user_id") private UUID userId;
        @Id @Column(name = "campaign_id") private UUID campaignId;
        @Column(name = "ngo_id", nullable = false) private UUID ngoId;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
        protected SavedCampaign() {}
        public SavedCampaign(UUID userId, UUID campaignId, UUID ngoId) { this.userId = userId; this.campaignId = campaignId; this.ngoId = ngoId; }
        public UUID getCampaignId() { return campaignId; }
        public UUID getNgoId() { return ngoId; }
    }

    @Entity
    @Table(name = "privacy_requests")
    public static class PrivacyRequest {
        @Id private UUID id;
        @Column(name = "user_id") private UUID userId;
        @Column(name = "ngo_id") private UUID ngoId;
        @Column(name = "donor_id") private UUID donorId;
        @Column(nullable = false) private String type;
        private String details;
        @Column(nullable = false) private String status = "OPEN";
        private String resolution;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
        @Column(name = "resolved_at") private Instant resolvedAt;
        protected PrivacyRequest() {}
        public PrivacyRequest(UUID userId, UUID ngoId, UUID donorId, String type, String details) {
            this.id = UUID.randomUUID(); this.userId = userId; this.ngoId = ngoId; this.donorId = donorId;
            this.type = type; this.details = details;
        }
        public void resolve(String status, String resolution) { this.status = status; this.resolution = resolution; this.resolvedAt = Instant.now(); }
        public UUID getId() { return id; }
        public UUID getUserId() { return userId; }
        public UUID getNgoId() { return ngoId; }
        public UUID getDonorId() { return donorId; }
        public String getType() { return type; }
        public String getDetails() { return details; }
        public String getStatus() { return status; }
        public String getResolution() { return resolution; }
        public Instant getCreatedAt() { return createdAt; }
    }

    @Entity
    @Table(name = "donor_segments")
    public static class Segment {
        @Id private UUID id;
        @Column(name = "ngo_id", nullable = false) private UUID ngoId;
        @Column(nullable = false) private String name;
        @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private String criteria;
        @Column(name = "created_by", nullable = false) private UUID createdBy;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
        protected Segment() {}
        public Segment(UUID ngoId, String name, String criteria, UUID by) {
            this.id = UUID.randomUUID(); this.ngoId = ngoId; this.name = name; this.criteria = criteria; this.createdBy = by;
        }
        public UUID getId() { return id; }
        public UUID getNgoId() { return ngoId; }
        public String getName() { return name; }
        public String getCriteria() { return criteria; }
    }

    @Entity
    @Table(name = "donor_imports")
    public static class Import {
        @Id private UUID id;
        @Column(name = "ngo_id", nullable = false) private UUID ngoId;
        @Column(nullable = false) private String status = "PREVIEWED";
        @Column(name = "rows_total", nullable = false) private int rowsTotal;
        @Column(name = "rows_valid", nullable = false) private int rowsValid;
        @Column(name = "rows_duplicate", nullable = false) private int rowsDuplicate;
        @Column(name = "rows_invalid", nullable = false) private int rowsInvalid;
        @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private String payload;
        @Column(name = "created_by", nullable = false) private UUID createdBy;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
        protected Import() {}
        public Import(UUID ngoId, int total, int valid, int dup, int invalid, String payload, UUID by) {
            this.id = UUID.randomUUID(); this.ngoId = ngoId; this.rowsTotal = total; this.rowsValid = valid;
            this.rowsDuplicate = dup; this.rowsInvalid = invalid; this.payload = payload; this.createdBy = by;
        }
        public void setStatus(String s) { this.status = s; }
        public UUID getId() { return id; }
        public UUID getNgoId() { return ngoId; }
        public String getStatus() { return status; }
        public int getRowsTotal() { return rowsTotal; }
        public int getRowsValid() { return rowsValid; }
        public int getRowsDuplicate() { return rowsDuplicate; }
        public int getRowsInvalid() { return rowsInvalid; }
        public String getPayload() { return payload; }
    }
}
