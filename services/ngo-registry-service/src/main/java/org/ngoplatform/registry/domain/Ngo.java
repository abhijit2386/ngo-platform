package org.ngoplatform.registry.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * NGO aggregate. Account, registration, verification, public-profile, donation-eligibility (derived) and
 * payment-onboarding statuses are deliberately separate (spec §7.1, §10).
 */
@Entity
@Table(name = "ngos")
public class Ngo {
    public enum RegistrationType { PUBLIC_TRUST, SOCIETY, SECTION_8_COMPANY, OTHER }
    public enum AccountStatus { ACTIVE, SUSPENDED, CLOSURE_REQUESTED, CLOSED }
    public enum RegistrationStatus { DRAFT, SUBMITTED }
    public enum VerificationStatus { NOT_SUBMITTED, PENDING, INFO_REQUESTED, APPROVED, REJECTED }
    public enum PublicProfileStatus { HIDDEN, PUBLISHED }
    public enum PaymentOnboarding { NOT_STARTED, PENDING, ACTIVE, REJECTED }

    @Id private UUID id;
    @Column(name = "legal_name", nullable = false) private String legalName;
    @Column(name = "display_name", nullable = false) private String displayName;
    @Enumerated(EnumType.STRING) @Column(name = "registration_type", nullable = false) private RegistrationType registrationType;
    @Column(name = "registration_number") private String registrationNumber;
    @Column(name = "registration_number_norm") private String registrationNumberNorm;
    @Column(name = "registration_date") private LocalDate registrationDate;
    @Column(name = "registration_state") private String registrationState;
    @Column(name = "darpan_id") private String darpanId;
    private String pan;
    private String description;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "areas_of_activity", nullable = false, columnDefinition = "jsonb")
    private List<String> areasOfActivity = new ArrayList<>();
    @Column(name = "official_email") private String officialEmail;
    @Column(name = "official_phone") private String officialPhone;
    private String website;
    @Column(name = "logo_key") private String logoKey;
    @Column(name = "brand_color") private String brandColor;
    @Column(name = "tax_80g_number") private String tax80gNumber;
    @Column(name = "tax_80g_valid_until") private LocalDate tax80gValidUntil;
    @Column(name = "tax_80g_verified", nullable = false) private boolean tax80gVerified;
    @Enumerated(EnumType.STRING) @Column(name = "account_status", nullable = false) private AccountStatus accountStatus = AccountStatus.ACTIVE;
    @Enumerated(EnumType.STRING) @Column(name = "registration_status", nullable = false) private RegistrationStatus registrationStatus = RegistrationStatus.DRAFT;
    @Enumerated(EnumType.STRING) @Column(name = "verification_status", nullable = false) private VerificationStatus verificationStatus = VerificationStatus.NOT_SUBMITTED;
    @Enumerated(EnumType.STRING) @Column(name = "public_profile_status", nullable = false) private PublicProfileStatus publicProfileStatus = PublicProfileStatus.HIDDEN;
    @Column(name = "compliance_hold", nullable = false) private boolean complianceHold;
    @Enumerated(EnumType.STRING) @Column(name = "payment_onboarding_status", nullable = false) private PaymentOnboarding paymentOnboarding = PaymentOnboarding.NOT_STARTED;
    @JdbcTypeCode(SqlTypes.JSON) @Column(name = "risk_flags", nullable = false, columnDefinition = "jsonb")
    private List<String> riskFlags = new ArrayList<>();
    @Column(name = "verified_at") private Instant verifiedAt;
    @Column(name = "reverify_due_at") private Instant reverifyDueAt;
    @Column(name = "submitted_at") private Instant submittedAt;
    @Column(name = "created_by", nullable = false) private UUID createdBy;
    @Column(name = "created_at", nullable = false) private Instant createdAt = Instant.now();
    @Column(name = "updated_at", nullable = false) private Instant updatedAt = Instant.now();
    @Version private long version;

    protected Ngo() {}

    public Ngo(UUID createdBy, String legalName, String displayName, RegistrationType type) {
        this.id = UUID.randomUUID(); this.createdBy = createdBy; this.legalName = legalName;
        this.displayName = displayName; this.registrationType = type;
    }

    /** Donation eligibility is derived, never stored independently, so it cannot drift from its inputs. */
    public boolean donationEligible() {
        return accountStatus == AccountStatus.ACTIVE && verificationStatus == VerificationStatus.APPROVED && !complianceHold;
    }

    public String donationEligibilityStatus() {
        if (complianceHold) return "SUSPENDED_COMPLIANCE";
        if (accountStatus != AccountStatus.ACTIVE) return "ACCOUNT_" + accountStatus.name();
        if (verificationStatus != VerificationStatus.APPROVED) return "NOT_VERIFIED";
        return "ELIGIBLE";
    }

    public boolean isEditableBeforeVerification() {
        return registrationStatus == RegistrationStatus.DRAFT || verificationStatus == VerificationStatus.INFO_REQUESTED
                || verificationStatus == VerificationStatus.REJECTED;
    }

    public static String normalizeRegNumber(String n) {
        return n == null ? null : n.toUpperCase().replaceAll("[^A-Z0-9]", "");
    }

    public void updateOrganization(String legalName, String displayName, RegistrationType type, String description,
                                   List<String> areas, String email, String phone, String website) {
        this.legalName = legalName; this.displayName = displayName; this.registrationType = type;
        this.description = description; this.areasOfActivity = new ArrayList<>(areas);
        this.officialEmail = email; this.officialPhone = phone; this.website = website; touch();
    }

    public void updateRegistration(String number, LocalDate date, String state, String darpan, String pan,
                                   String tax80gNumber, LocalDate tax80gValidUntil) {
        this.registrationNumber = number; this.registrationNumberNorm = normalizeRegNumber(number);
        this.registrationDate = date; this.registrationState = state; this.darpanId = darpan; this.pan = pan;
        if (!java.util.Objects.equals(this.tax80gNumber, tax80gNumber)) this.tax80gVerified = false;
        this.tax80gNumber = tax80gNumber; this.tax80gValidUntil = tax80gValidUntil; touch();
    }

    public void updatePublicProfile(String displayName, String description, List<String> areas, String website,
                                    String brandColor) {
        this.displayName = displayName; this.description = description; this.areasOfActivity = new ArrayList<>(areas);
        this.website = website; this.brandColor = brandColor; touch();
    }

    public void setLogoKey(String key) { this.logoKey = key; touch(); }

    public void submit(Instant now) {
        this.registrationStatus = RegistrationStatus.SUBMITTED;
        this.verificationStatus = VerificationStatus.PENDING;
        this.submittedAt = now; touch();
    }

    public void requestInfo() { this.verificationStatus = VerificationStatus.INFO_REQUESTED; touch(); }

    public void approve(Instant now, Instant reverifyDue, boolean confirm80g) {
        this.verificationStatus = VerificationStatus.APPROVED;
        this.publicProfileStatus = PublicProfileStatus.PUBLISHED;
        this.verifiedAt = now; this.reverifyDueAt = reverifyDue;
        if (confirm80g && tax80gNumber != null) this.tax80gVerified = true;
        touch();
    }

    public void reject() {
        this.verificationStatus = VerificationStatus.REJECTED;
        this.publicProfileStatus = PublicProfileStatus.HIDDEN; touch();
    }

    public void suspend() {
        this.accountStatus = AccountStatus.SUSPENDED; this.complianceHold = true;
        this.publicProfileStatus = PublicProfileStatus.HIDDEN; touch();
    }

    /** Reinstating lifts the compliance hold; a still-pending reverification keeps donations closed. */
    public void reinstate() {
        this.accountStatus = AccountStatus.ACTIVE; this.complianceHold = false;
        if (verificationStatus == VerificationStatus.APPROVED) this.publicProfileStatus = PublicProfileStatus.PUBLISHED;
        touch();
    }

    public void requestClosure() { this.accountStatus = AccountStatus.CLOSURE_REQUESTED; this.publicProfileStatus = PublicProfileStatus.HIDDEN; touch(); }
    public void setPaymentOnboarding(PaymentOnboarding s) { this.paymentOnboarding = s; touch(); }
    public void setRiskFlags(List<String> flags) { this.riskFlags = new ArrayList<>(flags); touch(); }
    public void applyLegalChange(String field, String value) {
        switch (field) {
            case "LEGAL_NAME" -> this.legalName = value;
            case "REGISTRATION_NUMBER" -> { this.registrationNumber = value; this.registrationNumberNorm = normalizeRegNumber(value); }
            case "PAN" -> this.pan = value;
            case "REGISTRATION_TYPE" -> this.registrationType = RegistrationType.valueOf(value);
            default -> throw new IllegalArgumentException(field);
        }
        touch();
    }
    private void touch() { this.updatedAt = Instant.now(); }

    public UUID getId() { return id; }
    public String getLegalName() { return legalName; }
    public String getDisplayName() { return displayName; }
    public RegistrationType getRegistrationType() { return registrationType; }
    public String getRegistrationNumber() { return registrationNumber; }
    public String getRegistrationNumberNorm() { return registrationNumberNorm; }
    public LocalDate getRegistrationDate() { return registrationDate; }
    public String getRegistrationState() { return registrationState; }
    public String getDarpanId() { return darpanId; }
    public String getPan() { return pan; }
    public String getDescription() { return description; }
    public List<String> getAreasOfActivity() { return areasOfActivity; }
    public String getOfficialEmail() { return officialEmail; }
    public String getOfficialPhone() { return officialPhone; }
    public String getWebsite() { return website; }
    public String getLogoKey() { return logoKey; }
    public String getBrandColor() { return brandColor; }
    public String getTax80gNumber() { return tax80gNumber; }
    public LocalDate getTax80gValidUntil() { return tax80gValidUntil; }
    public boolean isTax80gVerified() { return tax80gVerified; }
    public AccountStatus getAccountStatus() { return accountStatus; }
    public RegistrationStatus getRegistrationStatus() { return registrationStatus; }
    public VerificationStatus getVerificationStatus() { return verificationStatus; }
    public PublicProfileStatus getPublicProfileStatus() { return publicProfileStatus; }
    public boolean isComplianceHold() { return complianceHold; }
    public PaymentOnboarding getPaymentOnboarding() { return paymentOnboarding; }
    public List<String> getRiskFlags() { return riskFlags; }
    public Instant getVerifiedAt() { return verifiedAt; }
    public Instant getReverifyDueAt() { return reverifyDueAt; }
    public Instant getSubmittedAt() { return submittedAt; }
    public UUID getCreatedBy() { return createdBy; }
    public Instant getCreatedAt() { return createdAt; }
    public long getVersion() { return version; }
}
