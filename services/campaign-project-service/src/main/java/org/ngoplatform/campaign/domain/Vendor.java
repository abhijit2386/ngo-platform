package org.ngoplatform.campaign.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;
import org.ngoplatform.campaign.app.AccountRules;

/** A supplier on the NGO's vendor list (shared by all campaigns). */
@Entity
@Table(name = "vendors")
public class Vendor {
    @Id private UUID id;
    @Column(name = "ngo_id", nullable = false) private UUID ngoId;
    @Column(nullable = false) private String name;
    private String phone;
    private String email;
    private String gstin;
    private String pan;
    private String address;
    private String category;
    private String notes;
    @Column(nullable = false) private boolean active = true;
    @Column(name = "created_by", nullable = false) private UUID createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version private long version;

    protected Vendor() {}

    public Vendor(UUID ngoId, UUID createdBy) { this.id = UUID.randomUUID(); this.ngoId = ngoId; this.createdBy = createdBy; }

    public void edit(AccountRules.VendorInput v) {
        name = v.name(); phone = v.phone(); email = v.email(); gstin = v.gstin(); pan = v.pan(); address = v.address();
        category = v.category(); notes = v.notes(); updatedAt = Instant.now();
    }

    public void setActive(boolean a) { active = a; updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public UUID getNgoId() { return ngoId; }
    public String getName() { return name; }
    public String getPhone() { return phone; }
    public String getEmail() { return email; }
    public String getGstin() { return gstin; }
    public String getPan() { return pan; }
    public String getAddress() { return address; }
    public String getCategory() { return category; }
    public String getNotes() { return notes; }
    public boolean isActive() { return active; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
}
