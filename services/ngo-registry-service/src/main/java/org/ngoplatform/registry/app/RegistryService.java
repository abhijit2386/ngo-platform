package org.ngoplatform.registry.app;

import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.events.EventPublisher;
import org.ngoplatform.commons.events.EventTypes;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.security.CurrentUser;
import org.ngoplatform.commons.security.MembershipResolver;
import org.ngoplatform.commons.security.Permissions;
import org.ngoplatform.commons.storage.FileInspector;
import org.ngoplatform.commons.storage.ObjectStorage;
import org.ngoplatform.registry.api.Dtos;
import org.ngoplatform.registry.domain.Ngo;
import org.ngoplatform.registry.domain.Parts;
import org.ngoplatform.registry.domain.Repos;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class RegistryService {
    private static final long MAX_DOC = 10L * 1024 * 1024;
    private static final Set<String> DOC_TYPES_ALLOWED = Set.of("application/pdf", "image/png", "image/jpeg");

    private final Repos.Ngos ngos;
    private final Repos.Addresses addresses;
    private final Repos.Contacts contacts;
    private final Repos.Documents documents;
    private final Repos.Verifications verifications;
    private final Repos.ChangeRequests changes;
    private final ObjectStorage storage;
    private final EventPublisher events;
    private final AuditLog audit;
    private final AccessGuard guard;
    private final MembershipResolver memberships;
    private final Clock clock;
    private final Duration reverifyAfter;

    public RegistryService(Repos.Ngos ngos, Repos.Addresses addresses, Repos.Contacts contacts,
                           Repos.Documents documents, Repos.Verifications verifications, Repos.ChangeRequests changes,
                           ObjectStorage storage, EventPublisher events, AuditLog audit, AccessGuard guard,
                           MembershipResolver memberships, Clock clock,
                           @Value("${ngo.verification.reverify-after:P365D}") Duration reverifyAfter) {
        this.ngos = ngos; this.addresses = addresses; this.contacts = contacts; this.documents = documents;
        this.verifications = verifications; this.changes = changes; this.storage = storage; this.events = events;
        this.audit = audit; this.guard = guard; this.memberships = memberships; this.clock = clock;
        this.reverifyAfter = reverifyAfter;
    }

    // ------------------------------------------------------------------ access

    /**
     * Who may edit an NGO: members with NGO_PROFILE_MANAGE, or the creator while the tenant bootstrap event is still
     * in flight (a few seconds after creating the draft).
     */
    public Ngo editable(UUID ngoId) {
        Ngo n = load(ngoId);
        CurrentUser u = guard.user();
        boolean member = memberships.find(u.userId(), ngoId).map(m -> m.can(Permissions.NGO_PROFILE_MANAGE)).orElse(false);
        boolean creatorBootstrap = n.getCreatedBy().equals(u.userId()) && memberships.find(u.userId(), ngoId).isEmpty();
        if (!member && !creatorBootstrap) throw ApiException.forbidden("PERMISSION_DENIED", "You cannot edit this organization");
        if (n.getAccountStatus() == Ngo.AccountStatus.CLOSED) throw ApiException.rule("NGO_CLOSED", "Organization is closed");
        return n;
    }

    public Ngo readable(UUID ngoId) {
        Ngo n = load(ngoId);
        CurrentUser u = guard.user();
        boolean member = memberships.find(u.userId(), ngoId).map(m -> m.isActive()).orElse(false);
        if (!member && !n.getCreatedBy().equals(u.userId()) && !u.isPlatformSupport()) {
            throw ApiException.forbidden("NOT_A_MEMBER", "You are not a member of this organization");
        }
        return n;
    }

    public Ngo load(UUID ngoId) { return ngos.findById(ngoId).orElseThrow(() -> ApiException.notFound("Organization")); }

    // ------------------------------------------------------------------ wizard

    @Transactional
    public Ngo createDraft(Dtos.OrganizationDetails req, Dtos.Person secondaryAdmin) {
        CurrentUser u = guard.user();
        long drafts = ngos.findByCreatedByOrderByCreatedAtDesc(u.userId()).stream()
                .filter(n -> n.getRegistrationStatus() == Ngo.RegistrationStatus.DRAFT).count();
        if (drafts >= 3) throw ApiException.rule("TOO_MANY_DRAFTS", "Finish or discard your existing registration drafts first");
        validateCauses(req.areasOfActivity());
        Ngo n = new Ngo(u.userId(), req.legalName().trim(), req.displayName().trim(), req.registrationType());
        n.updateOrganization(req.legalName().trim(), req.displayName().trim(), req.registrationType(), req.description(),
                req.areasOfActivity(), lower(req.officialEmail()), req.officialPhone(), req.website());
        ngos.save(n);
        Parts.Contact primary = new Parts.Contact(n.getId(), "PRIMARY_ADMIN");
        primary.update(u.name() == null ? u.email() : u.name(), u.email(), null, "Administrator");
        contacts.save(primary);
        if (secondaryAdmin != null) upsertContact(n.getId(), "SECONDARY_ADMIN", secondaryAdmin);
        audit.record(n.getId(), u.userId(), "NGO_DRAFT_CREATED", "NGO", n.getId(), Map.of());
        Map<String, Object> payload = new HashMap<>();
        payload.put("primaryAdminUserId", u.userId().toString());
        payload.put("primaryAdminEmail", u.email());
        if (secondaryAdmin != null && secondaryAdmin.email() != null) payload.put("secondaryAdminEmail", lower(secondaryAdmin.email()));
        payload.put("displayName", n.getDisplayName());
        events.publish(EventTypes.NGO_REGISTERED, n.getId(), n.getId(), payload);
        return n;
    }

    @Transactional
    public Ngo updateOrganization(UUID ngoId, Dtos.OrganizationDetails req) {
        Ngo n = editable(ngoId);
        validateCauses(req.areasOfActivity());
        boolean legalLocked = !n.isEditableBeforeVerification();
        if (legalLocked && (!n.getLegalName().equals(req.legalName().trim()) || n.getRegistrationType() != req.registrationType())) {
            throw ApiException.rule("SENSITIVE_CHANGE_REQUIRES_REVIEW",
                    "Legal name and registration type changes need a change request after verification");
        }
        n.updateOrganization(req.legalName().trim(), req.displayName().trim(), req.registrationType(), req.description(),
                req.areasOfActivity(), lower(req.officialEmail()), req.officialPhone(), req.website());
        audit.record(ngoId, guard.user().userId(), "NGO_ORGANIZATION_UPDATED", "NGO", ngoId, Map.of());
        return n;
    }

    @Transactional
    public Ngo updateRegistration(UUID ngoId, Dtos.RegistrationDetails req) {
        Ngo n = editable(ngoId);
        if (!n.isEditableBeforeVerification()) {
            throw ApiException.rule("SENSITIVE_CHANGE_REQUIRES_REVIEW",
                    "Registration details can only be changed through a change request after verification");
        }
        if (req.registrationDate() != null && req.registrationDate().isAfter(LocalDate.now(clock))) {
            throw ApiException.badRequest("REGISTRATION_DATE_FUTURE", "Registration date cannot be in the future");
        }
        if (!Reference.STATES.contains(req.registrationState())) {
            throw ApiException.badRequest("INVALID_STATE", "Select a valid state or union territory");
        }
        if (req.tax80gValidUntil() != null && req.tax80gNumber() == null) {
            throw ApiException.badRequest("80G_NUMBER_REQUIRED", "Enter the 80G registration number");
        }
        n.updateRegistration(req.registrationNumber().trim(), req.registrationDate(), req.registrationState(),
                upper(req.darpanId()), upper(req.pan()), blankToNull(req.tax80gNumber()), req.tax80gValidUntil());
        audit.record(ngoId, guard.user().userId(), "NGO_REGISTRATION_UPDATED", "NGO", ngoId, Map.of());
        return n;
    }

    @Transactional
    public Parts.Address updateAddress(UUID ngoId, Dtos.AddressDto req) {
        Ngo n = editable(ngoId);
        if (!Reference.STATES.contains(req.state())) throw ApiException.badRequest("INVALID_STATE", "Select a valid state");
        Parts.Address a = addresses.findById(n.getId()).orElseGet(() -> new Parts.Address(n.getId()));
        a.update(req.line1().trim(), blankToNull(req.line2()), req.city().trim(), req.district().trim(), req.state(), req.pinCode());
        addresses.save(a);
        audit.record(ngoId, guard.user().userId(), "NGO_ADDRESS_UPDATED", "NGO", ngoId, Map.of());
        return a;
    }

    @Transactional
    public void updateContact(UUID ngoId, String kind, Dtos.Person p) {
        editable(ngoId);
        upsertContact(ngoId, kind, p);
        audit.record(ngoId, guard.user().userId(), "NGO_CONTACT_UPDATED", "NGO", ngoId, Map.of("kind", kind));
    }

    private void upsertContact(UUID ngoId, String kind, Dtos.Person p) {
        Parts.Contact c = contacts.findByNgoIdAndKind(ngoId, kind).orElseGet(() -> new Parts.Contact(ngoId, kind));
        c.update(p.name().trim(), lower(p.email()), p.phone(), p.designation());
        contacts.save(c);
    }

    @Transactional
    public Parts.Document uploadDocument(UUID ngoId, String type, byte[] data, String originalName, LocalDate expiresOn) {
        Ngo n = editable(ngoId);
        if (!Reference.DOC_TYPES.contains(type)) throw ApiException.badRequest("INVALID_DOC_TYPE", "Unknown document type");
        if (expiresOn != null && expiresOn.isBefore(LocalDate.now(clock))) {
            throw ApiException.badRequest("DOCUMENT_EXPIRED", "This document has already expired");
        }
        Set<String> allowed = "LOGO".equals(type) ? Set.of("image/png", "image/jpeg") : DOC_TYPES_ALLOWED;
        FileInspector.Inspected f = FileInspector.inspect(data, "LOGO".equals(type) ? 2L * 1024 * 1024 : MAX_DOC, allowed);
        UUID docId = UUID.randomUUID();
        String key = "ngos/" + ngoId + "/documents/" + docId + "." + f.extension();
        storage.put(key, data, f.contentType());
        for (Parts.Document old : documents.findByNgoIdAndTypeAndStatusNot(ngoId, type, "SUPERSEDED")) {
            old.supersede();
            if (!"LOGO".equals(type)) events.publish(EventTypes.DOCUMENT_FILE_REMOVED, ngoId, old.getId(), VaultFiles.removed(old));
        }
        Parts.Document d = documents.save(new Parts.Document(ngoId, type, key, FileInspector.safeName(originalName),
                f.contentType(), f.size(), f.sha256(), expiresOn, guard.user().userId()));
        // Listed in the NGO's document vault (document-service); the file stays here.
        if (!"LOGO".equals(type)) events.publish(EventTypes.DOCUMENT_FILE_REGISTERED, ngoId, d.getId(), VaultFiles.registered(d));
        if ("LOGO".equals(type)) n.setLogoKey(key);
        audit.record(ngoId, guard.user().userId(), "NGO_DOCUMENT_UPLOADED", "NGO_DOCUMENT", d.getId(), Map.of("type", type));
        return d;
    }

    public InputStream documentContent(Parts.Document d) { return storage.get(d.getObjectKey()); }

    public InputStream logo(UUID ngoId) {
        Ngo n = load(ngoId);
        if (n.getLogoKey() == null) throw ApiException.notFound("Logo");
        return storage.get(n.getLogoKey());
    }

    /** NGO-013 Review: what is still missing before submission. */
    @Transactional(readOnly = true)
    public List<String> missingForSubmission(Ngo n) {
        List<String> missing = new ArrayList<>();
        if (n.getRegistrationNumber() == null) missing.add("REGISTRATION_DETAILS");
        if (n.getOfficialEmail() == null || n.getOfficialPhone() == null) missing.add("CONTACT_DETAILS");
        if (n.getDescription() == null || n.getDescription().length() < 30) missing.add("DESCRIPTION");
        if (n.getAreasOfActivity().isEmpty()) missing.add("AREAS_OF_ACTIVITY");
        if (addresses.findById(n.getId()).isEmpty()) missing.add("ADDRESS");
        if (contacts.findByNgoIdAndKind(n.getId(), "SIGNATORY").isEmpty()) missing.add("AUTHORIZED_SIGNATORY");
        Set<String> present = documents.findByNgoIdAndStatusNotOrderByCreatedAtDesc(n.getId(), "SUPERSEDED").stream()
                .filter(d -> !"REJECTED".equals(d.getStatus())).map(Parts.Document::getType).collect(Collectors.toSet());
        for (String req : Reference.REQUIRED_DOCS) if (!present.contains(req)) missing.add("DOCUMENT_" + req);
        if (n.getPan() != null && !present.contains("PAN_CARD")) missing.add("DOCUMENT_PAN_CARD");
        if (n.getTax80gNumber() != null && !present.contains("CERTIFICATE_80G")) missing.add("DOCUMENT_CERTIFICATE_80G");
        return missing;
    }

    @Transactional
    public Ngo submit(UUID ngoId) {
        Ngo n = editable(ngoId);
        boolean resubmission = n.getVerificationStatus() == Ngo.VerificationStatus.INFO_REQUESTED
                || n.getVerificationStatus() == Ngo.VerificationStatus.REJECTED;
        if (n.getRegistrationStatus() == Ngo.RegistrationStatus.SUBMITTED && !resubmission) {
            throw ApiException.rule("ALREADY_SUBMITTED", "This registration is already under review");
        }
        List<String> missing = missingForSubmission(n);
        if (!missing.isEmpty()) throw ApiException.rule("REGISTRATION_INCOMPLETE", "Missing: " + String.join(", ", missing));
        if (!ngos.findLiveByRegistration(n.getRegistrationState(), n.getRegistrationNumberNorm(), n.getId()).isEmpty()) {
            throw ApiException.conflict("DUPLICATE_REGISTRATION",
                    "Another organization is already registered with this registration number in " + n.getRegistrationState()
                            + ". Contact support if this is your organization.");
        }
        n.setRiskFlags(riskFlags(n));
        n.submit(clock.instant());
        UUID actor = guard.user().userId();
        verifications.save(new Parts.VerificationEntry(n.getId(), resubmission ? "RESUBMITTED" : "SUBMITTED", actor, null, null));
        audit.record(ngoId, actor, resubmission ? "NGO_RESUBMITTED" : "NGO_SUBMITTED", "NGO", ngoId, Map.of());
        return n;
    }

    /** Signals for reviewers; never auto-rejects. */
    List<String> riskFlags(Ngo n) {
        List<String> flags = new ArrayList<>();
        if (n.getPan() != null && !ngos.findOthersByPan(n.getPan(), n.getId()).isEmpty()) flags.add("DUPLICATE_PAN");
        if (!ngos.findOthersByName(n.getDisplayName(), n.getId()).isEmpty()) flags.add("SIMILAR_NAME");
        if (n.getDarpanId() == null) flags.add("NO_DARPAN_ID");
        if (n.getRegistrationDate() != null && n.getRegistrationDate().isAfter(LocalDate.now(clock).minusMonths(6))) {
            flags.add("RECENTLY_REGISTERED");
        }
        if (n.getOfficialEmail() != null && n.getOfficialEmail().matches(".*@(gmail|yahoo|hotmail|outlook)\\..*")) {
            flags.add("FREE_EMAIL_DOMAIN");
        }
        return flags;
    }

    // ------------------------------------------------------------------ platform review

    @Transactional
    public Ngo decide(UUID ngoId, Dtos.VerificationDecision d) {
        CurrentUser admin = guard.requirePlatformAdmin();
        Ngo n = load(ngoId);
        if (n.getVerificationStatus() != Ngo.VerificationStatus.PENDING) {
            throw ApiException.rule("NOT_PENDING_REVIEW", "Organization is " + n.getVerificationStatus());
        }
        switch (d.decision()) {
            case APPROVE -> {
                n.approve(clock.instant(), clock.instant().plus(reverifyAfter), Boolean.TRUE.equals(d.confirm80g()));
                documents.findByNgoIdAndStatusNotOrderByCreatedAtDesc(ngoId, "SUPERSEDED").stream()
                        .filter(doc -> "UPLOADED".equals(doc.getStatus())).forEach(doc -> doc.review("ACCEPTED", null));
                Map<String, Object> approved = new HashMap<>();
                approved.put("displayName", n.getDisplayName());
                if (d.messageToNgo() != null && !d.messageToNgo().isBlank()) approved.put("message", d.messageToNgo());
                events.publish(EventTypes.NGO_VERIFIED, ngoId, ngoId, approved);
                publishEligibility(n);
            }
            case REJECT -> {
                if (d.messageToNgo() == null || d.messageToNgo().isBlank()) {
                    throw ApiException.badRequest("MESSAGE_REQUIRED", "Explain the rejection to the organization");
                }
                n.reject();
                events.publish(EventTypes.NGO_REJECTED, ngoId, ngoId, Map.of("reason", d.messageToNgo(), "displayName", n.getDisplayName()));
            }
            case REQUEST_INFO -> {
                if (d.messageToNgo() == null || d.messageToNgo().isBlank()) {
                    throw ApiException.badRequest("MESSAGE_REQUIRED", "Tell the organization what is needed");
                }
                n.requestInfo();
                events.publish(EventTypes.NGO_INFO_REQUESTED, ngoId, ngoId, Map.of("message", d.messageToNgo(), "displayName", n.getDisplayName()));
            }
        }
        if (d.documentReviews() != null) {
            d.documentReviews().forEach(r -> documents.findByIdAndNgoId(r.documentId(), ngoId)
                    .ifPresent(doc -> doc.review(r.accepted() ? "ACCEPTED" : "REJECTED", r.note())));
        }
        verifications.save(new Parts.VerificationEntry(ngoId, d.decision().name(), admin.userId(), d.internalNote(), d.messageToNgo()));
        audit.record(ngoId, admin.userId(), "NGO_VERIFICATION_" + d.decision().name(), "NGO", ngoId, Map.of());
        return n;
    }

    @Transactional
    public Ngo setSuspension(UUID ngoId, boolean suspend, String reason) {
        CurrentUser admin = guard.requirePlatformAdmin();
        Ngo n = load(ngoId);
        if (suspend) {
            if (reason == null || reason.isBlank()) throw ApiException.badRequest("REASON_REQUIRED", "A reason is required");
            n.suspend();
            events.publish(EventTypes.NGO_SUSPENDED, ngoId, ngoId, Map.of("reason", reason));
        } else {
            n.reinstate();
        }
        publishEligibility(n);
        verifications.save(new Parts.VerificationEntry(ngoId, suspend ? "SUSPENDED" : "REINSTATED", admin.userId(), reason, reason));
        audit.record(ngoId, admin.userId(), suspend ? "NGO_SUSPENDED" : "NGO_REINSTATED", "NGO", ngoId, Map.of());
        return n;
    }

    private void publishEligibility(Ngo n) {
        events.publish(EventTypes.NGO_DONATION_ELIGIBILITY_CHANGED, n.getId(), n.getId(),
                Map.of("eligible", n.donationEligible(), "status", n.donationEligibilityStatus()));
    }

    // ------------------------------------------------------------------ sensitive changes (NGO-027)

    @Transactional
    public Parts.ChangeRequest requestChange(UUID ngoId, String field, String newValue, String reason) {
        Ngo n = load(ngoId);
        var m = guard.require(ngoId, Permissions.NGO_SENSITIVE_CHANGE);
        if (n.isEditableBeforeVerification()) {
            throw ApiException.rule("EDIT_DIRECTLY", "Before verification you can edit these details directly");
        }
        String old = switch (field) {
            case "LEGAL_NAME" -> n.getLegalName();
            case "REGISTRATION_NUMBER" -> n.getRegistrationNumber();
            case "PAN" -> n.getPan();
            case "REGISTRATION_TYPE" -> n.getRegistrationType().name();
            default -> throw ApiException.badRequest("INVALID_FIELD", "Field cannot be changed this way");
        };
        if ("PAN".equals(field) && !newValue.matches("^[A-Z]{5}[0-9]{4}[A-Z]$")) {
            throw ApiException.badRequest("INVALID_PAN", "PAN must look like ABCDE1234F");
        }
        if ("REGISTRATION_TYPE".equals(field)) {
            try { Ngo.RegistrationType.valueOf(newValue); } catch (IllegalArgumentException e) {
                throw ApiException.badRequest("INVALID_TYPE", "Unknown registration type");
            }
        }
        Parts.ChangeRequest cr = changes.save(new Parts.ChangeRequest(ngoId, field, old, newValue.trim(), reason, m.userId()));
        audit.record(ngoId, m.userId(), "NGO_CHANGE_REQUESTED", "NGO_CHANGE", cr.getId(), Map.of("field", field));
        return cr;
    }

    @Transactional
    public Parts.ChangeRequest decideChange(UUID changeId, boolean approve) {
        CurrentUser admin = guard.requirePlatformAdmin();
        Parts.ChangeRequest cr = changes.findById(changeId).orElseThrow(() -> ApiException.notFound("Change request"));
        if (!"PENDING".equals(cr.getStatus())) throw ApiException.rule("NOT_PENDING", "Already decided");
        cr.decide(approve, admin.userId());
        if (approve) load(cr.getNgoId()).applyLegalChange(cr.getField(), cr.getNewValue());
        audit.record(cr.getNgoId(), admin.userId(), approve ? "NGO_CHANGE_APPROVED" : "NGO_CHANGE_REJECTED", "NGO_CHANGE",
                changeId, Map.of("field", cr.getField()));
        return cr;
    }

    @Transactional
    public Ngo requestClosure(UUID ngoId) {
        guard.requireAdmin(ngoId);
        Ngo n = load(ngoId);
        n.requestClosure();
        publishEligibility(n);
        audit.record(ngoId, guard.user().userId(), "NGO_CLOSURE_REQUESTED", "NGO", ngoId, Map.of());
        return n;
    }

    // ------------------------------------------------------------------ periodic checks

    /** Flags due re-verification and expiring documents for review; never suspends automatically. */
    @Scheduled(cron = "${ngo.verification.sweep-cron:0 30 2 * * *}")
    @Transactional
    public void periodicChecks() {
        Instant now = clock.instant();
        for (Ngo n : ngos.findByVerificationStatusAndReverifyDueAtBefore(Ngo.VerificationStatus.APPROVED, now)) {
            if (!n.getRiskFlags().contains("REVERIFICATION_DUE")) {
                List<String> f = new ArrayList<>(n.getRiskFlags());
                f.add("REVERIFICATION_DUE");
                n.setRiskFlags(f);
                verifications.save(new Parts.VerificationEntry(n.getId(), "REVERIFICATION_DUE", null, null,
                        "Please review and re-upload current registration documents."));
            }
        }
        LocalDate soon = LocalDate.now(clock).plusDays(30);
        for (Parts.Document d : documents.findByExpiresOnBeforeAndStatus(soon, "ACCEPTED")) {
            Ngo n = load(d.getNgoId());
            String flag = "DOCUMENT_EXPIRING_" + d.getType();
            if (!n.getRiskFlags().contains(flag)) {
                List<String> f = new ArrayList<>(n.getRiskFlags());
                f.add(flag);
                n.setRiskFlags(f);
            }
        }
    }

    private static void validateCauses(List<String> causes) {
        for (String c : causes) {
            if (!Reference.CAUSES.contains(c)) throw ApiException.badRequest("INVALID_CAUSE", "Unknown area of activity " + c);
        }
    }

    private static String lower(String s) { return s == null ? null : s.trim().toLowerCase(); }
    private static String upper(String s) { return s == null || s.isBlank() ? null : s.trim().toUpperCase(); }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }

    public static byte[] readAll(InputStream in) {
        try (in) { return in.readAllBytes(); } catch (IOException e) { throw new IllegalStateException(e); }
    }
}
