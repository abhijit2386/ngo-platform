package org.ngoplatform.donor.domain;

import jakarta.persistence.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "donors")
public class Donor {
    public enum Type { INDIVIDUAL, ORGANIZATION }
    public enum Status { ACTIVE, MERGED, ERASED }

    @Id private UUID id;
    @Column(name = "ngo_id", nullable = false) private UUID ngoId;
    @Column(name = "donor_user_id") private UUID donorUserId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Type type;
    @Column(nullable = false) private String name;
    private String email;
    private String phone;
    private String pan;
    @Column(name = "address_line") private String addressLine;
    private String city;
    private String state;
    @Column(name = "pin_code", length = 6) @org.hibernate.annotations.JdbcTypeCode(org.hibernate.type.SqlTypes.CHAR) private String pinCode;
    @Column(name = "contact_person") private String contactPerson;
    @JdbcTypeCode(SqlTypes.JSON) @Column(nullable = false, columnDefinition = "jsonb") private List<String> tags = new ArrayList<>();
    private String notes;
    @Column(nullable = false) private String source;
    @Column(name = "communication_opt_in", nullable = false) private boolean communicationOptIn = true;
    @Column(name = "total_donated", nullable = false) private BigDecimal totalDonated = BigDecimal.ZERO;
    @Column(name = "donation_count", nullable = false) private int donationCount;
    @Column(name = "first_donation_at") private Instant firstDonationAt;
    @Column(name = "last_donation_at") private Instant lastDonationAt;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private Status status = Status.ACTIVE;
    @Column(name = "merged_into") private UUID mergedInto;
    @Column(name = "created_by") private UUID createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version private long version;

    protected Donor() {}

    public Donor(UUID ngoId, Type type, String name, String source, UUID createdBy) {
        this.id = UUID.randomUUID(); this.ngoId = ngoId; this.type = type; this.name = name; this.source = source;
        this.createdBy = createdBy;
    }

    public void update(Type type, String name, String email, String phone, String pan, String addressLine, String city,
                       String state, String pin, String contactPerson, List<String> tags, String notes, Boolean optIn) {
        this.type = type; this.name = name; this.email = email; this.phone = phone; this.pan = pan;
        this.addressLine = addressLine; this.city = city; this.state = state; this.pinCode = pin;
        this.contactPerson = contactPerson; this.tags = tags == null ? new ArrayList<>() : new ArrayList<>(tags);
        this.notes = notes; if (optIn != null) this.communicationOptIn = optIn; touch();
    }

    /** Fills only missing fields (used when an online donation matches an existing record). */
    public void enrich(String email, String phone, String pan, UUID userId) {
        if (this.email == null && email != null) this.email = email;
        if (this.phone == null && phone != null) this.phone = phone;
        if (this.pan == null && pan != null) this.pan = pan;
        if (this.donorUserId == null && userId != null) this.donorUserId = userId;
        touch();
    }

    public void applyDonation(BigDecimal amount, Instant at) {
        totalDonated = totalDonated.add(amount);
        donationCount++;
        if (firstDonationAt == null || at.isBefore(firstDonationAt)) firstDonationAt = at;
        if (lastDonationAt == null || at.isAfter(lastDonationAt)) lastDonationAt = at;
        touch();
    }

    public void applyRefund(BigDecimal amount) {
        totalDonated = totalDonated.subtract(amount).max(BigDecimal.ZERO); touch();
    }

    public void absorb(Donor other) {
        totalDonated = totalDonated.add(other.totalDonated);
        donationCount += other.donationCount;
        if (other.firstDonationAt != null && (firstDonationAt == null || other.firstDonationAt.isBefore(firstDonationAt))) firstDonationAt = other.firstDonationAt;
        if (other.lastDonationAt != null && (lastDonationAt == null || other.lastDonationAt.isAfter(lastDonationAt))) lastDonationAt = other.lastDonationAt;
        enrich(other.email, other.phone, other.pan, other.donorUserId);
        for (String t : other.tags) if (!tags.contains(t)) tags.add(t);
        touch();
    }

    public void markMerged(UUID into) {
        this.status = Status.MERGED; this.mergedInto = into; this.email = null; this.phone = null; touch();
    }

    /** Erasure keeps the financial totals (legal retention) but removes personal data. */
    public void erase() {
        this.status = Status.ERASED; this.name = "Erased donor"; this.email = null; this.phone = null;
        this.addressLine = null; this.city = null; this.pinCode = null; this.contactPerson = null; this.notes = null;
        this.tags = new ArrayList<>(); this.communicationOptIn = false; this.donorUserId = null; touch();
    }

    public void setOptIn(boolean v) { this.communicationOptIn = v; touch(); }
    private void touch() { this.updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getNgoId() { return ngoId; }
    public UUID getDonorUserId() { return donorUserId; }
    public Type getType() { return type; }
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getPhone() { return phone; }
    public String getPan() { return pan; }
    public String getAddressLine() { return addressLine; }
    public String getCity() { return city; }
    public String getState() { return state; }
    public String getPinCode() { return pinCode; }
    public String getContactPerson() { return contactPerson; }
    public List<String> getTags() { return tags; }
    public String getNotes() { return notes; }
    public String getSource() { return source; }
    public boolean isCommunicationOptIn() { return communicationOptIn; }
    public BigDecimal getTotalDonated() { return totalDonated; }
    public int getDonationCount() { return donationCount; }
    public Instant getFirstDonationAt() { return firstDonationAt; }
    public Instant getLastDonationAt() { return lastDonationAt; }
    public Status getStatus() { return status; }
    public UUID getMergedInto() { return mergedInto; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
}
