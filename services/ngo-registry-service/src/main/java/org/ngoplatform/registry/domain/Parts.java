package org.ngoplatform.registry.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** Child records of the NGO aggregate. */
public final class Parts {
    private Parts() {}

    @Entity
    @Table(name = "ngo_addresses")
    public static class Address {
        @Id @Column(name = "ngo_id") private UUID ngoId;
        @Column(nullable = false) private String line1;
        private String line2;
        @Column(nullable = false) private String city;
        @Column(nullable = false) private String district;
        @Column(nullable = false) private String state;
        @Column(name = "pin_code", nullable = false, length = 6) @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR) private String pinCode;

        protected Address() {}
        public Address(UUID ngoId) { this.ngoId = ngoId; }
        public void update(String line1, String line2, String city, String district, String state, String pin) {
            this.line1 = line1; this.line2 = line2; this.city = city; this.district = district; this.state = state; this.pinCode = pin;
        }
        public UUID getNgoId() { return ngoId; }
        public String getLine1() { return line1; }
        public String getLine2() { return line2; }
        public String getCity() { return city; }
        public String getDistrict() { return district; }
        public String getState() { return state; }
        public String getPinCode() { return pinCode; }
    }

    @Entity
    @Table(name = "ngo_contacts")
    public static class Contact {
        @Id private UUID id;
        @Column(name = "ngo_id", nullable = false) private UUID ngoId;
        @Column(nullable = false) private String kind;
        @Column(nullable = false) private String name;
        private String email;
        private String phone;
        private String designation;

        protected Contact() {}
        public Contact(UUID ngoId, String kind) { this.id = UUID.randomUUID(); this.ngoId = ngoId; this.kind = kind; }
        public void update(String name, String email, String phone, String designation) {
            this.name = name; this.email = email; this.phone = phone; this.designation = designation;
        }
        public String getKind() { return kind; }
        public String getName() { return name; }
        public String getEmail() { return email; }
        public String getPhone() { return phone; }
        public String getDesignation() { return designation; }
    }

    @Entity
    @Table(name = "ngo_documents")
    public static class Document {
        @Id private UUID id;
        @Column(name = "ngo_id", nullable = false) private UUID ngoId;
        @Column(name = "doc_type", nullable = false) private String type;
        @Column(name = "object_key", nullable = false) private String objectKey;
        @Column(name = "file_name", nullable = false) private String fileName;
        @Column(name = "content_type", nullable = false) private String contentType;
        @Column(name = "size_bytes", nullable = false) private long sizeBytes;
        @Column(nullable = false, length = 64) @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR) private String sha256;
        @Column(nullable = false) private String status = "UPLOADED";
        @Column(name = "expires_on") private LocalDate expiresOn;
        @Column(name = "review_note") private String reviewNote;
        @Column(name = "uploaded_by", nullable = false) private UUID uploadedBy;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

        protected Document() {}
        public Document(UUID ngoId, String type, String objectKey, String fileName, String contentType, long size,
                        String sha256, LocalDate expiresOn, UUID uploadedBy) {
            this.id = UUID.randomUUID(); this.ngoId = ngoId; this.type = type; this.objectKey = objectKey;
            this.fileName = fileName; this.contentType = contentType; this.sizeBytes = size; this.sha256 = sha256;
            this.expiresOn = expiresOn; this.uploadedBy = uploadedBy;
        }
        public void review(String status, String note) { this.status = status; this.reviewNote = note; }
        public void supersede() { this.status = "SUPERSEDED"; }
        public UUID getId() { return id; }
        public UUID getNgoId() { return ngoId; }
        public String getType() { return type; }
        public String getObjectKey() { return objectKey; }
        public String getFileName() { return fileName; }
        public String getContentType() { return contentType; }
        public long getSizeBytes() { return sizeBytes; }
        public String getSha256() { return sha256; }
        public String getStatus() { return status; }
        public LocalDate getExpiresOn() { return expiresOn; }
        public String getReviewNote() { return reviewNote; }
        public Instant getCreatedAt() { return createdAt; }
        public UUID getUploadedBy() { return uploadedBy; }
    }

    @Entity
    @Table(name = "ngo_verifications")
    public static class VerificationEntry {
        @Id private UUID id;
        @Column(name = "ngo_id", nullable = false) private UUID ngoId;
        @Column(nullable = false) private String action;
        @Column(name = "actor_id") private UUID actorId;
        @Column(name = "internal_note") private String internalNote;
        @Column(name = "message_to_ngo") private String messageToNgo;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

        protected VerificationEntry() {}
        public VerificationEntry(UUID ngoId, String action, UUID actorId, String internalNote, String messageToNgo) {
            this.id = UUID.randomUUID(); this.ngoId = ngoId; this.action = action; this.actorId = actorId;
            this.internalNote = internalNote; this.messageToNgo = messageToNgo;
        }
        public String getAction() { return action; }
        public UUID getActorId() { return actorId; }
        public String getInternalNote() { return internalNote; }
        public String getMessageToNgo() { return messageToNgo; }
        public Instant getCreatedAt() { return createdAt; }
    }

    @Entity
    @Table(name = "ngo_change_requests")
    public static class ChangeRequest {
        @Id private UUID id;
        @Column(name = "ngo_id", nullable = false) private UUID ngoId;
        @Column(nullable = false) private String field;
        @Column(name = "old_value") private String oldValue;
        @Column(name = "new_value", nullable = false) private String newValue;
        private String reason;
        @Column(nullable = false) private String status = "PENDING";
        @Column(name = "requested_by", nullable = false) private UUID requestedBy;
        @Column(name = "decided_by") private UUID decidedBy;
        @Column(name = "decided_at") private Instant decidedAt;
        @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();

        protected ChangeRequest() {}
        public ChangeRequest(UUID ngoId, String field, String oldValue, String newValue, String reason, UUID by) {
            this.id = UUID.randomUUID(); this.ngoId = ngoId; this.field = field; this.oldValue = oldValue;
            this.newValue = newValue; this.reason = reason; this.requestedBy = by;
        }
        public void decide(boolean approve, UUID by) {
            this.status = approve ? "APPROVED" : "REJECTED"; this.decidedBy = by; this.decidedAt = Instant.now();
        }
        public UUID getId() { return id; }
        public UUID getNgoId() { return ngoId; }
        public String getField() { return field; }
        public String getOldValue() { return oldValue; }
        public String getNewValue() { return newValue; }
        public String getReason() { return reason; }
        public String getStatus() { return status; }
        public Instant getCreatedAt() { return createdAt; }
    }
}
