package org.ngoplatform.donor.api;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.Valid;
import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.security.Entitlements;
import org.ngoplatform.commons.security.Membership;
import org.ngoplatform.commons.security.Permissions;
import org.ngoplatform.commons.web.PageResponse;
import org.ngoplatform.donor.app.DonorService;
import org.ngoplatform.donor.domain.Donor;
import org.ngoplatform.donor.domain.Records;
import org.ngoplatform.donor.domain.Repos;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** DONOR-001..018 (NGO side). */
@RestController
@RequestMapping("/api/v1/ngos/{ngoId}/donors")
public class DonorController {
    private final DonorService svc;
    private final AccessGuard guard;
    private final Repos.Facts facts;
    private final Repos.Segments segments;
    private final Repos.Privacy privacy;
    private final ObjectMapper mapper;

    public DonorController(DonorService svc, AccessGuard guard, Repos.Facts facts, Repos.Segments segments,
                           Repos.Privacy privacy, ObjectMapper mapper) {
        this.svc = svc; this.guard = guard; this.facts = facts; this.segments = segments; this.privacy = privacy; this.mapper = mapper;
    }

    @GetMapping
    public PageResponse<Dtos.DonorView> list(@PathVariable UUID ngoId, @RequestParam(required = false) String q,
                                             @RequestParam(required = false) String tag, @RequestParam(required = false) String city,
                                             @RequestParam(required = false) BigDecimal minTotal,
                                             @RequestParam(required = false) Integer lapsedDays,
                                             @RequestParam(required = false) Integer activeWithinDays,
                                             @RequestParam(required = false) Donor.Type type,
                                             @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        guard.require(ngoId, Permissions.DONOR_VIEW);
        String level = guard.entitlements(ngoId).level(Entitlements.DONOR_SEGMENTATION_LEVEL);
        Page<Donor> p = svc.search(ngoId, new DonorService.Filter(q, tag, city, minTotal, lapsedDays, activeWithinDays, type),
                level, page, size);
        return PageResponse.of(p, Dtos.DonorView::of);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.DonorView create(@PathVariable UUID ngoId, @Valid @RequestBody Dtos.DonorRequest req) {
        Membership m = guard.require(ngoId, Permissions.DONOR_MANAGE);
        guard.requireOperational(ngoId);
        return Dtos.DonorView.of(svc.create(ngoId, m.userId(), req.input()));
    }

    @GetMapping("/{donorId}")
    public Map<String, Object> get(@PathVariable UUID ngoId, @PathVariable UUID donorId) {
        guard.require(ngoId, Permissions.DONOR_VIEW);
        Donor d = svc.load(ngoId, donorId);
        return Map.of("donor", Dtos.DonorView.of(d),
                "possibleDuplicates", svc.duplicatesOf(d).stream().map(Dtos.DonorView::of).toList());
    }

    @PutMapping("/{donorId}")
    public Dtos.DonorView update(@PathVariable UUID ngoId, @PathVariable UUID donorId, @Valid @RequestBody Dtos.DonorRequest req) {
        Membership m = guard.require(ngoId, Permissions.DONOR_MANAGE);
        if (req.version() == null) throw ApiException.badRequest("VERSION_REQUIRED", "version is required for updates");
        return Dtos.DonorView.of(svc.update(ngoId, m.userId(), donorId, req.input(), req.version()));
    }

    @GetMapping("/{donorId}/donations")
    public PageResponse<Map<String, Object>> donations(@PathVariable UUID ngoId, @PathVariable UUID donorId,
                                                        @RequestParam(defaultValue = "0") int page) {
        guard.require(ngoId, Permissions.DONATION_VIEW);
        svc.load(ngoId, donorId);
        return PageResponse.of(facts.findByDonorIdOrderByPaidAtDesc(donorId, PageRequest.of(page, 20)),
                f -> Map.<String, Object>of("donationId", f.getDonationId(), "amount", f.getAmount(),
                        "refunded", f.getRefunded(), "paidAt", f.getPaidAt(),
                        "campaignId", f.getCampaignId() == null ? "" : f.getCampaignId().toString()));
    }

    @PostMapping("/{targetId}/merge")
    public Dtos.DonorView merge(@PathVariable UUID ngoId, @PathVariable UUID targetId, @Valid @RequestBody Dtos.Merge req) {
        Membership m = guard.require(ngoId, Permissions.DONOR_MANAGE);
        return Dtos.DonorView.of(svc.merge(ngoId, m.userId(), req.sourceDonorId(), targetId));
    }

    @PostMapping("/{donorId}/erase")
    public Dtos.DonorView erase(@PathVariable UUID ngoId, @PathVariable UUID donorId) {
        Membership m = guard.requireAdmin(ngoId);
        return Dtos.DonorView.of(svc.erase(ngoId, m.userId(), donorId));
    }

    @PostMapping(value = "/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public Dtos.ImportView preview(@PathVariable UUID ngoId, @RequestPart("file") MultipartFile file) throws IOException {
        Membership m = guard.require(ngoId, Permissions.DONOR_MANAGE);
        guard.requireOperational(ngoId);
        if (file.getSize() > 2L * 1024 * 1024) throw ApiException.badRequest("FILE_TOO_LARGE", "CSV must be at most 2 MB");
        return view(svc.previewImport(ngoId, m.userId(), file.getBytes()));
    }

    @PostMapping("/imports/{importId}/commit")
    public Dtos.ImportView commit(@PathVariable UUID ngoId, @PathVariable UUID importId) {
        Membership m = guard.require(ngoId, Permissions.DONOR_MANAGE);
        guard.requireOperational(ngoId);
        return view(svc.commitImport(ngoId, m.userId(), importId));
    }

    private Dtos.ImportView view(Records.Import i) {
        Object rows;
        try { rows = mapper.readValue(i.getPayload(), new TypeReference<List<Map<String, Object>>>() {}); }
        catch (IOException e) { rows = List.of(); }
        return new Dtos.ImportView(i.getId(), i.getStatus(), i.getRowsTotal(), i.getRowsValid(), i.getRowsDuplicate(),
                i.getRowsInvalid(), rows);
    }

    /** DONOR-015: export is never premium-gated; it needs DONOR_VIEW + REPORT_EXPORT. */
    @GetMapping(value = "/export", produces = "text/csv")
    @Transactional(readOnly = true)
    public ResponseEntity<byte[]> export(@PathVariable UUID ngoId) {
        guard.require(ngoId, Permissions.DONOR_VIEW);
        guard.require(ngoId, Permissions.REPORT_EXPORT);
        StringBuilder sb = new StringBuilder("name,type,email,phone,pan,city,state,total_donated,donation_count,last_donation_at,tags\n");
        int page = 0;
        Page<Donor> p;
        do {
            p = svc.search(ngoId, new DonorService.Filter(null, null, null, null, null, null, null), "ADVANCED", page++, 100);
            for (Donor d : p) {
                sb.append(String.join(",", DonorService.csvCell(d.getName()), d.getType().name(), DonorService.csvCell(d.getEmail()),
                        DonorService.csvCell(d.getPhone()), DonorService.csvCell(d.getPan()), DonorService.csvCell(d.getCity()),
                        DonorService.csvCell(d.getState()), d.getTotalDonated().toPlainString(), String.valueOf(d.getDonationCount()),
                        d.getLastDonationAt() == null ? "" : d.getLastDonationAt().toString(),
                        DonorService.csvCell(String.join(";", d.getTags())))).append('\n');
            }
        } while (p.hasNext());
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"donors.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8)).body(sb.toString().getBytes(StandardCharsets.UTF_8));
    }

    // ---------------- segments (ADVANCED)

    @GetMapping("/segments")
    public List<Map<String, Object>> segments(@PathVariable UUID ngoId) {
        guard.require(ngoId, Permissions.DONOR_VIEW);
        return segments.findByNgoIdOrderByName(ngoId).stream()
                .map(s -> Map.<String, Object>of("id", s.getId(), "name", s.getName(), "criteria", s.getCriteria())).toList();
    }

    @PostMapping("/segments")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> saveSegment(@PathVariable UUID ngoId, @Valid @RequestBody Dtos.SegmentRequest req) throws IOException {
        Membership m = guard.require(ngoId, Permissions.DONOR_MANAGE);
        if (!"ADVANCED".equals(guard.requireOperational(ngoId).level(Entitlements.DONOR_SEGMENTATION_LEVEL))) {
            throw ApiException.entitlement("DONOR_SEGMENTATION_LEVEL", "Saved segments need the Professional plan or higher");
        }
        Records.Segment s = segments.save(new Records.Segment(ngoId, req.name().trim(), mapper.writeValueAsString(req), m.userId()));
        return Map.of("id", s.getId(), "name", s.getName());
    }

    // ---------------- privacy requests raised against this NGO (DONOR-016)

    @GetMapping("/privacy-requests")
    public List<Dtos.PrivacyView> privacyRequests(@PathVariable UUID ngoId) {
        guard.requireAdmin(ngoId);
        return privacy.findByNgoIdOrderByCreatedAtDesc(ngoId).stream().map(Dtos.PrivacyView::of).toList();
    }

    @PutMapping("/privacy-requests/{id}")
    @Transactional
    public Dtos.PrivacyView resolve(@PathVariable UUID ngoId, @PathVariable UUID id, @Valid @RequestBody Dtos.PrivacyResolution r) {
        Membership m = guard.requireAdmin(ngoId);
        Records.PrivacyRequest pr = privacy.findById(id).filter(x -> ngoId.equals(x.getNgoId()))
                .orElseThrow(() -> ApiException.notFound("Privacy request"));
        if ("COMPLETED".equals(r.status()) && "ERASURE".equals(pr.getType()) && pr.getDonorId() != null) {
            svc.erase(ngoId, m.userId(), pr.getDonorId());
        }
        pr.resolve(r.status(), r.resolution());
        return Dtos.PrivacyView.of(pr);
    }
}
