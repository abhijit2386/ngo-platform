package org.ngoplatform.registry.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.registry.app.Reference;
import org.ngoplatform.registry.app.RegistryService;
import org.ngoplatform.registry.domain.Ngo;
import org.ngoplatform.registry.domain.Parts;
import org.ngoplatform.registry.domain.Repos;
import org.springframework.core.io.InputStreamResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** NGO-001..030 for NGO members. */
@RestController
@RequestMapping("/api/v1/ngos")
public class NgoController {
    private final RegistryService svc;
    private final AccessGuard guard;
    private final Repos.Ngos ngos;
    private final Repos.Addresses addresses;
    private final Repos.Contacts contacts;
    private final Repos.Documents documents;
    private final Repos.Verifications verifications;
    private final Repos.ChangeRequests changes;

    public NgoController(RegistryService svc, AccessGuard guard, Repos.Ngos ngos, Repos.Addresses addresses,
                         Repos.Contacts contacts, Repos.Documents documents, Repos.Verifications verifications,
                         Repos.ChangeRequests changes) {
        this.svc = svc; this.guard = guard; this.ngos = ngos; this.addresses = addresses; this.contacts = contacts;
        this.documents = documents; this.verifications = verifications; this.changes = changes;
    }

    Dtos.NgoView view(Ngo n) {
        Parts.Address a = addresses.findById(n.getId()).orElse(null);
        return new Dtos.NgoView(n.getId(), n.getLegalName(), n.getDisplayName(), n.getRegistrationType(),
                n.getRegistrationNumber(), n.getRegistrationDate(), n.getRegistrationState(), n.getDarpanId(), n.getPan(),
                n.getDescription(), n.getAreasOfActivity(), n.getOfficialEmail(), n.getOfficialPhone(), n.getWebsite(),
                n.getLogoKey() != null, n.getBrandColor(), n.getTax80gNumber(), n.getTax80gValidUntil(),
                n.isTax80gVerified(), n.getAccountStatus().name(), n.getRegistrationStatus().name(),
                n.getVerificationStatus().name(), n.getPublicProfileStatus().name(), n.donationEligibilityStatus(),
                n.getPaymentOnboarding().name(), n.getVerifiedAt(), n.getSubmittedAt(), Dtos.AddressDto.of(a),
                contacts.findByNgoId(n.getId()).stream().map(Dtos.ContactView::of).toList(),
                n.isEditableBeforeVerification() ? svc.missingForSubmission(n) : List.of(), n.getVersion());
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.NgoView create(@Valid @RequestBody Dtos.CreateNgo req) {
        return view(svc.createDraft(req.organization(), req.secondaryAdmin()));
    }

    /** Registrations started by the current user (drafts and submitted). */
    @GetMapping("/mine")
    public List<Dtos.NgoView> mine() {
        return ngos.findByCreatedByOrderByCreatedAtDesc(guard.user().userId()).stream().map(this::view).toList();
    }

    @GetMapping("/{ngoId}")
    public Dtos.NgoView get(@PathVariable UUID ngoId) { return view(svc.readable(ngoId)); }

    @PutMapping("/{ngoId}/organization")
    public Dtos.NgoView org(@PathVariable UUID ngoId, @Valid @RequestBody Dtos.OrganizationDetails req) {
        return view(svc.updateOrganization(ngoId, req));
    }

    @PutMapping("/{ngoId}/registration")
    public Dtos.NgoView registration(@PathVariable UUID ngoId, @Valid @RequestBody Dtos.RegistrationDetails req) {
        return view(svc.updateRegistration(ngoId, req));
    }

    @PutMapping("/{ngoId}/address")
    public Dtos.NgoView address(@PathVariable UUID ngoId, @Valid @RequestBody Dtos.AddressDto req) {
        svc.updateAddress(ngoId, req);
        return view(svc.load(ngoId));
    }

    @PutMapping("/{ngoId}/contacts/{kind}")
    public Dtos.NgoView contact(@PathVariable UUID ngoId, @PathVariable String kind, @Valid @RequestBody Dtos.Person req) {
        if (!List.of("SECONDARY_ADMIN", "SIGNATORY").contains(kind)) {
            throw ApiException.badRequest("INVALID_CONTACT_KIND", "Use SECONDARY_ADMIN or SIGNATORY");
        }
        svc.updateContact(ngoId, kind, req);
        return view(svc.load(ngoId));
    }

    @PutMapping("/{ngoId}/public-profile")
    public Dtos.NgoView publicProfile(@PathVariable UUID ngoId, @Valid @RequestBody Dtos.PublicProfileUpdate req) {
        Ngo n = svc.editable(ngoId);
        for (String c : req.areasOfActivity()) {
            if (!Reference.CAUSES.contains(c)) throw ApiException.badRequest("INVALID_CAUSE", "Unknown area " + c);
        }
        n.updatePublicProfile(req.displayName().trim(), req.description(), req.areasOfActivity(), req.website(),
                req.brandColor() == null || req.brandColor().isBlank() ? null : req.brandColor());
        ngos.save(n);
        return view(n);
    }

    @PostMapping(value = "/{ngoId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.DocumentView upload(@PathVariable UUID ngoId, @RequestParam @NotBlank String type,
                                    @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate expiresOn,
                                    @RequestPart("file") MultipartFile file) throws IOException {
        return Dtos.DocumentView.of(svc.uploadDocument(ngoId, type, file.getBytes(), file.getOriginalFilename(), expiresOn));
    }

    @GetMapping("/{ngoId}/documents")
    public List<Dtos.DocumentView> docs(@PathVariable UUID ngoId) {
        svc.readable(ngoId);
        return documents.findByNgoIdAndStatusNotOrderByCreatedAtDesc(ngoId, "SUPERSEDED").stream()
                .map(Dtos.DocumentView::of).toList();
    }

    @GetMapping("/{ngoId}/documents/{docId}/content")
    public ResponseEntity<InputStreamResource> docContent(@PathVariable UUID ngoId, @PathVariable UUID docId) {
        svc.readable(ngoId);
        Parts.Document d = documents.findByIdAndNgoId(docId, ngoId).orElseThrow(() -> ApiException.notFound("Document"));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(d.getContentType()))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + d.getFileName() + "\"")
                .body(new InputStreamResource(svc.documentContent(d)));
    }

    @PostMapping("/{ngoId}/submit")
    public Dtos.NgoView submit(@PathVariable UUID ngoId) { return view(svc.submit(ngoId)); }

    @GetMapping("/{ngoId}/verification-history")
    public List<Dtos.VerificationHistory> history(@PathVariable UUID ngoId) {
        svc.readable(ngoId);
        return verifications.findByNgoIdOrderByCreatedAtDesc(ngoId).stream()
                .map(v -> new Dtos.VerificationHistory(v.getAction(), v.getMessageToNgo(), v.getCreatedAt())).toList();
    }

    @PostMapping("/{ngoId}/change-requests")
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.ChangeView change(@PathVariable UUID ngoId, @Valid @RequestBody Dtos.ChangeRequestDto req) {
        return Dtos.ChangeView.of(svc.requestChange(ngoId, req.field(), req.newValue(), req.reason()));
    }

    @GetMapping("/{ngoId}/change-requests")
    public List<Dtos.ChangeView> changes(@PathVariable UUID ngoId) {
        svc.readable(ngoId);
        return changes.findByNgoIdOrderByCreatedAtDesc(ngoId).stream().map(Dtos.ChangeView::of).toList();
    }

    @PostMapping("/{ngoId}/closure-request")
    public Dtos.NgoView closure(@PathVariable UUID ngoId) { return view(svc.requestClosure(ngoId)); }
}
