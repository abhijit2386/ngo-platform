package org.ngoplatform.receipt.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

public final class Template {
    private Template() {}

    @Entity
    @Table(name = "receipt_templates")
    public static class Header {
        public enum Kind { BASIC, ADVANCED }
        public enum Status { DRAFT, PENDING_APPROVAL, PUBLISHED, ARCHIVED }

        @Id private UUID id;
        @Column(name = "ngo_id", nullable = false) private UUID ngoId;
        @Column(nullable = false) private String name;
        @Enumerated(EnumType.STRING) @Column(nullable = false) private Kind kind;
        @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.DRAFT;
        @Column(name = "is_active", nullable = false) private boolean active;
        @Column(name = "current_version", nullable = false) private int currentVersion = 1;
        @Column(name = "published_version") private Integer publishedVersion;
        @Column(name = "created_by", nullable = false) private UUID createdBy;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
        @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
        @jakarta.persistence.Version private long version;

        protected Header() {}
        public Header(UUID ngoId, String name, Kind kind, UUID by) {
            this.id = UUID.randomUUID(); this.ngoId = ngoId; this.name = name; this.kind = kind; this.createdBy = by;
        }
        public void newVersion(int v) { currentVersion = v; if (status == Status.PUBLISHED || status == Status.ARCHIVED) status = Status.DRAFT; touch(); }
        public void setStatus(Status s) { status = s; touch(); }
        public void publish(int v) { status = Status.PUBLISHED; publishedVersion = v; touch(); }
        public void setActive(boolean a) { active = a; touch(); }
        public void rename(String n) { name = n; touch(); }
        private void touch() { updatedAt = Instant.now(); }
        public UUID getId() { return id; }
        public UUID getNgoId() { return ngoId; }
        public String getName() { return name; }
        public Kind getKind() { return kind; }
        public Status getStatus() { return status; }
        public boolean isActive() { return active; }
        public int getCurrentVersion() { return currentVersion; }
        public Integer getPublishedVersion() { return publishedVersion; }
        public UUID getCreatedBy() { return createdBy; }
        public Instant getUpdatedAt() { return updatedAt; }
        public long getVersion() { return version; }
    }

    @Entity
    @Table(name = "receipt_template_versions")
    public static class Version {
        @Id private UUID id;
        @Column(name = "template_id", nullable = false) private UUID templateId;
        @Column(name = "version_no", nullable = false) private int versionNo;
        @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private String definition;
        @Column(name = "background_key") private String backgroundKey;
        @Column(name = "created_by", nullable = false) private UUID createdBy;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
        protected Version() {}
        public Version(UUID templateId, int no, String definition, String backgroundKey, UUID by) {
            this.id = UUID.randomUUID(); this.templateId = templateId; this.versionNo = no; this.definition = definition;
            this.backgroundKey = backgroundKey; this.createdBy = by;
        }
        public UUID getTemplateId() { return templateId; }
        public int getVersionNo() { return versionNo; }
        public String getDefinition() { return definition; }
        public String getBackgroundKey() { return backgroundKey; }
        public UUID getCreatedBy() { return createdBy; }
        public Instant getCreatedAt() { return createdAt; }
    }

    @Entity
    @Table(name = "receipt_template_approvals")
    public static class Approval {
        @Id private UUID id;
        @Column(name = "template_id", nullable = false) private UUID templateId;
        @Column(name = "version_no", nullable = false) private int versionNo;
        @Column(name = "requested_by", nullable = false) private UUID requestedBy;
        @Column(name = "decided_by") private UUID decidedBy;
        private String decision;
        private String comment;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
        @Column(name = "decided_at") private Instant decidedAt;
        protected Approval() {}
        public Approval(UUID templateId, int v, UUID by) { this.id = UUID.randomUUID(); this.templateId = templateId; this.versionNo = v; this.requestedBy = by; }
        public void decide(boolean ok, UUID by, String comment) { decision = ok ? "APPROVED" : "REJECTED"; decidedBy = by; this.comment = comment; decidedAt = Instant.now(); }
        public UUID getRequestedBy() { return requestedBy; }
        public int getVersionNo() { return versionNo; }
        public String getDecision() { return decision; }
        public String getComment() { return comment; }
        public Instant getCreatedAt() { return createdAt; }
    }
}
