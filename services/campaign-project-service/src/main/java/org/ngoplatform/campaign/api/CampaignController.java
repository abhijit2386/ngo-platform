package org.ngoplatform.campaign.api;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.time.Duration;
import java.util.LinkedHashMap;
import org.ngoplatform.campaign.app.CampaignRules;
import org.ngoplatform.campaign.app.CampaignService;
import org.ngoplatform.campaign.domain.Campaign;
import org.ngoplatform.campaign.domain.Project;
import org.ngoplatform.campaign.domain.Repos;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.security.EntitlementResolver;
import org.ngoplatform.commons.security.Entitlements;
import org.ngoplatform.commons.security.Membership;
import org.ngoplatform.commons.security.Permissions;
import org.ngoplatform.commons.storage.FileInspector;
import org.ngoplatform.commons.storage.ObjectStorage;
import org.ngoplatform.commons.web.PageResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** CAMP-001..012 (campaign approval workflow), PROJ-001..013 and public campaign discovery (DAPP-007/008). */
@RestController
public class CampaignController {
    public record CampaignRequest(@NotBlank @Size(min = 5, max = 120) String title,
                                  @NotBlank @Size(min = 10, max = 300) String summary,
                                  @Size(max = 5000) String story, @Size(max = 40) String cause,
                                  @NotNull @Digits(integer = 10, fraction = 2) BigDecimal goalAmount,
                                  @NotNull LocalDate startDate, @NotNull LocalDate endDate, UUID projectId, Long version) {
        CampaignService.CampaignInput input() {
            return new CampaignService.CampaignInput(title, summary, story, cause, goalAmount, startDate, endDate, projectId);
        }
    }

    /** NGO team view (any status). People are ids; the app resolves names from the team list. */
    public record CampaignView(UUID id, UUID ngoId, String title, String slug, String summary, String story, String cause,
                               BigDecimal goalAmount, BigDecimal raisedAmount, int donationCount, LocalDate startDate,
                               LocalDate endDate, String status, boolean hasImage, int imageVersion, UUID projectId,
                               UUID createdBy, Instant createdAt, UUID updatedBy, Instant updatedAt, UUID submittedBy,
                               Instant submittedAt, String rejectionReason, Instant rejectedAt, UUID approvedBy,
                               Instant publishedAt, Instant closedAt, long version, List<UUID> photoIds, String shareUrl) {
        static CampaignView of(Campaign c) { return of(c, null, null); }

        static CampaignView of(Campaign c, List<UUID> photoIds, String shareUrl) {
            return new CampaignView(c.getId(), c.getNgoId(), c.getTitle(), c.getSlug(), c.getSummary(), c.getStory(), c.getCause(),
                    c.getGoalAmount(), c.getRaisedAmount(), c.getDonationCount(), c.getStartDate(), c.getEndDate(),
                    c.getStatus().name(), c.getImageKey() != null, c.getImageVersion(), c.getProjectId(), c.getCreatedBy(),
                    c.getCreatedAt(), c.getUpdatedBy(), c.getUpdatedAt(), c.getSubmittedBy(), c.getSubmittedAt(),
                    c.getRejectionReason(), c.getRejectedAt(), c.getApprovedBy(), c.getPublishedAt(), c.getClosedAt(), c.getVersion(),
                    photoIds, shareUrl);
        }
    }

    /** Donor-facing view: no internal people or workflow data. phase = LIVE | UPCOMING | ENDED. */
    public record PublicCampaignView(UUID id, UUID ngoId, String ngoName, String ngoCity, String title, String summary,
                                     String story, String cause, BigDecimal goalAmount, BigDecimal raisedAmount,
                                     int donationCount, LocalDate startDate, LocalDate endDate, String phase,
                                     boolean hasImage, int imageVersion, Instant publishedAt, List<UUID> photoIds,
                                     String shareUrl) {
        static PublicCampaignView of(Campaign c, LocalDate today, List<UUID> photoIds, String shareUrl) {
            String phase = c.getStatus() != Campaign.Status.PUBLISHED || (c.getEndDate() != null && today.isAfter(c.getEndDate()))
                    ? "ENDED" : today.isBefore(c.getStartDate()) ? "UPCOMING" : "LIVE";
            return new PublicCampaignView(c.getId(), c.getNgoId(), c.getNgoName(), c.getNgoCity(), c.getTitle(), c.getSummary(),
                    c.getStory(), c.getCause(), c.getGoalAmount(), c.getRaisedAmount(), c.getDonationCount(), c.getStartDate(),
                    c.getEndDate(), phase, c.getImageKey() != null, c.getImageVersion(), c.getPublishedAt(), photoIds, shareUrl);
        }
    }

    public record ApproveRequest(@NotNull Long version) {}
    public record RejectRequest(@NotBlank @Size(min = 5, max = 500) String reason) {}
    public record TestDonation(@NotNull @Digits(integer = 10, fraction = 2) BigDecimal amount, @Size(max = 80) String donorLabel) {}

    public record ProjectRequest(@NotBlank @Size(min = 3, max = 120) String name, @Size(max = 3000) String description,
                                 @Size(max = 120) String location, @NotNull LocalDate startDate, LocalDate endDate, Long version) {
        CampaignService.ProjectInput input() { return new CampaignService.ProjectInput(name, description, location, startDate, endDate); }
    }

    public record ProjectView(UUID id, String name, String description, String location, LocalDate startDate, LocalDate endDate,
                              BigDecimal budgetAmount, BigDecimal spentAmount, BigDecimal fundsRaised, int progress,
                              String status, long version) {
        static ProjectView of(Project p) {
            return new ProjectView(p.getId(), p.getName(), p.getDescription(), p.getLocation(), p.getStartDate(), p.getEndDate(),
                    p.getBudgetAmount(), p.getSpentAmount(), p.getFundsRaised(), p.getProgress(), p.getStatus().name(), p.getVersion());
        }
    }

    public record Milestone(@NotBlank @Size(max = 120) String title, LocalDate dueDate, @Min(1) @Max(100) int weight) {}
    public record MilestoneStatus(@NotBlank @Pattern(regexp = "PENDING|IN_PROGRESS|DONE|MISSED") String status) {}
    public record Assignment(@NotNull UUID userId, @NotBlank @Size(max = 40) String role) {}
    public record StatusChange(@NotNull Project.Status status) {}
    public record Note(@NotBlank @Size(max = 1000) String text) {}

    private final CampaignService svc;
    private final AccessGuard guard;
    private final Repos.Campaigns campaigns;
    private final Repos.Projects projects;
    private final ObjectStorage storage;
    private final JdbcTemplate jdbc;
    private final EntitlementResolver entitlements;
    private final boolean testDonations;
    private final String publicWebUrl;

    public CampaignController(CampaignService svc, AccessGuard guard, Repos.Campaigns campaigns, Repos.Projects projects,
                              ObjectStorage storage, JdbcTemplate jdbc, EntitlementResolver entitlements,
                              @Value("${ngo.campaigns.test-donations:false}") boolean testDonations,
                              @Value("${ngo.public-web-url:http://localhost:8080}") String publicWebUrl) {
        this.svc = svc; this.guard = guard; this.campaigns = campaigns; this.projects = projects; this.storage = storage;
        this.jdbc = jdbc; this.entitlements = entitlements; this.testDonations = testDonations;
        this.publicWebUrl = publicWebUrl.replaceAll("/+$", "");
    }

    // ---------------- campaigns (NGO)

    @GetMapping("/api/v1/ngos/{ngoId}/campaigns")
    public PageResponse<CampaignView> list(@PathVariable UUID ngoId, @RequestParam(required = false) Campaign.Status status,
                                           @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "20") int size) {
        guard.require(ngoId, Permissions.CAMPAIGN_VIEW);
        var p = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 100));
        return PageResponse.of(status == null ? campaigns.findByNgoIdAndStatusNotOrderByUpdatedAtDesc(ngoId, Campaign.Status.ARCHIVED, p)
                : campaigns.findByNgoIdAndStatusOrderByUpdatedAtDesc(ngoId, status, p), CampaignView::of);
    }

    /** Counts per status (list tabs) and the plan's live-campaign limit (-1 = unlimited). */
    @GetMapping("/api/v1/ngos/{ngoId}/campaigns/summary")
    public Map<String, Object> summary(@PathVariable UUID ngoId) {
        guard.require(ngoId, Permissions.CAMPAIGN_VIEW);
        Map<String, Object> counts = new LinkedHashMap<>();
        for (Campaign.Status st : Campaign.Status.values()) counts.put(st.name(), campaigns.countByNgoIdAndStatus(ngoId, st));
        Integer limit;
        try { limit = entitlements.get(ngoId).limit(Entitlements.CAMPAIGN_ACTIVE_MAX); } catch (RuntimeException e) { limit = null; }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("counts", counts);
        if (limit != null) out.put("liveLimit", limit);
        out.put("testDonations", testDonations);
        return out;
    }

    @PostMapping("/api/v1/ngos/{ngoId}/campaigns")
    @ResponseStatus(HttpStatus.CREATED)
    public CampaignView create(@PathVariable UUID ngoId, @Valid @RequestBody CampaignRequest req) {
        Membership m = guard.require(ngoId, Permissions.CAMPAIGN_MANAGE);
        guard.requireOperational(ngoId);
        return view(svc.create(ngoId, m.userId(), req.input()));
    }

    @GetMapping("/api/v1/ngos/{ngoId}/campaigns/{id}")
    public CampaignView get(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.require(ngoId, Permissions.CAMPAIGN_VIEW);
        return view(svc.load(ngoId, id));
    }

    @PutMapping("/api/v1/ngos/{ngoId}/campaigns/{id}")
    public CampaignView update(@PathVariable UUID ngoId, @PathVariable UUID id, @Valid @RequestBody CampaignRequest req) {
        Membership m = guard.require(ngoId, Permissions.CAMPAIGN_MANAGE);
        if (req.version() == null) throw ApiException.badRequest("VERSION_REQUIRED", "version is required");
        return view(svc.update(ngoId, m, id, req.input(), req.version()));
    }

    @PostMapping("/api/v1/ngos/{ngoId}/campaigns/{id}/submit")
    public CampaignView submit(@PathVariable UUID ngoId, @PathVariable UUID id) {
        Membership m = guard.require(ngoId, Permissions.CAMPAIGN_MANAGE);
        guard.requireOperational(ngoId);
        return view(svc.submit(ngoId, m, id));
    }

    @PostMapping("/api/v1/ngos/{ngoId}/campaigns/{id}/withdraw")
    public CampaignView withdraw(@PathVariable UUID ngoId, @PathVariable UUID id) {
        Membership m = guard.require(ngoId, Permissions.CAMPAIGN_MANAGE);
        return view(svc.withdraw(ngoId, m, id));
    }

    @PostMapping("/api/v1/ngos/{ngoId}/campaigns/{id}/approve")
    public CampaignView approve(@PathVariable UUID ngoId, @PathVariable UUID id, @Valid @RequestBody ApproveRequest req) {
        Membership m = guard.require(ngoId, Permissions.CAMPAIGN_APPROVE);
        return view(svc.approve(ngoId, m, id, req.version()));
    }

    @PostMapping("/api/v1/ngos/{ngoId}/campaigns/{id}/reject")
    public CampaignView reject(@PathVariable UUID ngoId, @PathVariable UUID id, @Valid @RequestBody RejectRequest req) {
        Membership m = guard.require(ngoId, Permissions.CAMPAIGN_APPROVE);
        return view(svc.reject(ngoId, m, id, req.reason()));
    }

    @PostMapping("/api/v1/ngos/{ngoId}/campaigns/{id}/close")
    public CampaignView close(@PathVariable UUID ngoId, @PathVariable UUID id) {
        Membership m = guard.require(ngoId, Permissions.CAMPAIGN_MANAGE);
        return view(svc.close(ngoId, m, id));
    }

    @PostMapping("/api/v1/ngos/{ngoId}/campaigns/{id}/archive")
    public CampaignView archive(@PathVariable UUID ngoId, @PathVariable UUID id) {
        Membership m = guard.require(ngoId, Permissions.CAMPAIGN_MANAGE);
        return view(svc.archive(ngoId, m, id));
    }

    @PostMapping(value = "/api/v1/ngos/{ngoId}/campaigns/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public CampaignView image(@PathVariable UUID ngoId, @PathVariable UUID id, @RequestPart("file") MultipartFile file) throws IOException {
        Membership m = guard.require(ngoId, Permissions.CAMPAIGN_MANAGE);
        return view(svc.addPhoto(ngoId, m, id, file.getBytes(), true));
    }

    /** Adds a photo to the gallery (max 5; the first one is the cover). */
    @PostMapping(value = "/api/v1/ngos/{ngoId}/campaigns/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public CampaignView addPhoto(@PathVariable UUID ngoId, @PathVariable UUID id, @RequestPart("file") MultipartFile file) throws IOException {
        Membership m = guard.require(ngoId, Permissions.CAMPAIGN_MANAGE);
        return view(svc.addPhoto(ngoId, m, id, file.getBytes(), false));
    }

    @DeleteMapping("/api/v1/ngos/{ngoId}/campaigns/{id}/photos/{photoId}")
    public CampaignView removePhoto(@PathVariable UUID ngoId, @PathVariable UUID id, @PathVariable UUID photoId) {
        Membership m = guard.require(ngoId, Permissions.CAMPAIGN_MANAGE);
        return view(svc.removePhoto(ngoId, m, id, photoId));
    }

    @PutMapping("/api/v1/ngos/{ngoId}/campaigns/{id}/photos/{photoId}/cover")
    public CampaignView makeCover(@PathVariable UUID ngoId, @PathVariable UUID id, @PathVariable UUID photoId) {
        Membership m = guard.require(ngoId, Permissions.CAMPAIGN_MANAGE);
        return view(svc.makeCover(ngoId, m, id, photoId));
    }

    @GetMapping("/api/v1/ngos/{ngoId}/campaigns/{id}/photos/{photoId}")
    public ResponseEntity<byte[]> teamPhoto(@PathVariable UUID ngoId, @PathVariable UUID id, @PathVariable UUID photoId,
                                            @RequestParam(defaultValue = "large") String size) throws IOException {
        guard.require(ngoId, Permissions.CAMPAIGN_VIEW);
        svc.load(ngoId, id);
        return image(svc.photo(id, photoId).key(), size, CacheControl.maxAge(Duration.ofDays(30)).cachePrivate());
    }

    @GetMapping("/api/v1/ngos/{ngoId}/campaigns/{id}/image")
    public ResponseEntity<byte[]> teamImage(@PathVariable UUID ngoId, @PathVariable UUID id,
                                            @RequestParam(defaultValue = "large") String size) throws IOException {
        guard.require(ngoId, Permissions.CAMPAIGN_VIEW);
        Campaign c = svc.load(ngoId, id);
        if (c.getImageKey() == null) throw ApiException.notFound("Image");
        return cover(c, size, CacheControl.noCache().cachePrivate());
    }

    /** Donations credited to the campaign (newest first). Donor labels need DONATION_VIEW. */
    @GetMapping("/api/v1/ngos/{ngoId}/campaigns/{id}/donations")
    public List<Map<String, Object>> donations(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.require(ngoId, Permissions.DONATION_VIEW);
        svc.load(ngoId, id);
        return jdbc.queryForList("select donation_id as id, amount, refunded, is_test as test, donor_label as \"donorLabel\", "
                + "created_at as \"createdAt\" from campaign_donations where campaign_id = ? order by created_at desc limit 100", id);
    }

    @PostMapping("/api/v1/ngos/{ngoId}/campaigns/{id}/test-donations")
    @ResponseStatus(HttpStatus.CREATED)
    public CampaignView testDonation(@PathVariable UUID ngoId, @PathVariable UUID id, @Valid @RequestBody TestDonation req) {
        Membership m = guard.require(ngoId, Permissions.DONATION_RECORD);
        return view(svc.recordTestDonation(ngoId, m.userId(), id, req.amount(), req.donorLabel()));
    }

    // ---------------- public discovery (DAPP-007/008)

    @GetMapping("/api/v1/public/campaigns")
    public PageResponse<PublicCampaignView> publicList(@RequestParam(required = false) UUID ngoId,
                                                       @RequestParam(required = false) String cause,
                                                       @RequestParam(required = false) String q,
                                                       @RequestParam(defaultValue = "0") int page,
                                                       @RequestParam(defaultValue = "20") int size) {
        if (cause != null && !CampaignRules.CAUSES.contains(cause)) throw ApiException.badRequest("INVALID_CAUSE", "Unknown cause");
        if (q != null && q.length() > 60) throw ApiException.badRequest("QUERY_TOO_LONG", "Search text is too long");
        var p = PageRequest.of(Math.max(0, page), Math.min(Math.max(1, size), 50), Sort.by(Sort.Direction.DESC, "publishedAt"));
        LocalDate today = svc.today();
        return PageResponse.of(campaigns.findAll(Repos.publicCampaigns(ngoId, cause, q), p),
                c -> PublicCampaignView.of(c, today, null, shareUrl(c)));
    }

    @GetMapping("/api/v1/public/campaigns/{id}")
    public PublicCampaignView publicGet(@PathVariable UUID id) {
        return campaigns.findById(id).filter(c -> c.getStatus() == Campaign.Status.PUBLISHED || c.getStatus() == Campaign.Status.CLOSED)
                .map(c -> PublicCampaignView.of(c, svc.today(), photoIds(c), shareUrl(c))).orElseThrow(() -> ApiException.notFound("Campaign"));
    }

    @GetMapping("/api/v1/public/campaigns/{id}/photos/{photoId}")
    public ResponseEntity<byte[]> publicPhoto(@PathVariable UUID id, @PathVariable UUID photoId,
                                              @RequestParam(defaultValue = "large") String size) throws IOException {
        campaigns.findById(id).filter(CampaignController::isPublic).orElseThrow(() -> ApiException.notFound("Photo"));
        // Photo keys contain the photo id, so a replaced photo gets a new URL: safe to cache for long.
        return image(svc.photo(id, photoId).key(), size, CacheControl.maxAge(Duration.ofDays(30)).cachePublic());
    }

    @GetMapping("/api/v1/public/campaigns/{id}/image")
    public ResponseEntity<byte[]> publicImage(@PathVariable UUID id, @RequestParam(defaultValue = "large") String size) throws IOException {
        Campaign c = campaigns.findById(id).filter(x -> x.getImageKey() != null
                        && (x.getStatus() == Campaign.Status.PUBLISHED || x.getStatus() == Campaign.Status.CLOSED))
                .orElseThrow(() -> ApiException.notFound("Image"));
        return cover(c, size, CacheControl.maxAge(Duration.ofHours(1)).cachePublic());
    }

    static boolean isPublic(Campaign c) {
        return c.getStatus() == Campaign.Status.PUBLISHED || c.getStatus() == Campaign.Status.CLOSED;
    }

    /** Full team view: gallery ids and, once public, the share link. */
    private CampaignView view(Campaign c) { return CampaignView.of(c, photoIds(c), shareUrl(c)); }

    private List<UUID> photoIds(Campaign c) { return svc.photos(c.getId()).stream().map(CampaignService.Photo::id).toList(); }

    /** Public web page with link-preview tags (SharePageController); null until the campaign is public. */
    private String shareUrl(Campaign c) { return isPublic(c) ? publicWebUrl + "/c/" + c.getId() : null; }

    private ResponseEntity<byte[]> cover(Campaign c, String size, CacheControl cache) throws IOException {
        return image(c.getImageKey(), size, cache);
    }

    private ResponseEntity<byte[]> image(String base, String size, CacheControl cache) throws IOException {
        String key = base + ("small".equals(size) ? "-480.jpg" : "-1200.jpg");
        try (InputStream in = storage.get(key)) {
            return ResponseEntity.ok().cacheControl(cache).contentType(MediaType.IMAGE_JPEG).body(in.readAllBytes());
        }
    }

    // ---------------- projects

    @GetMapping("/api/v1/ngos/{ngoId}/projects")
    public PageResponse<ProjectView> projects(@PathVariable UUID ngoId, @RequestParam(defaultValue = "0") int page) {
        guard.require(ngoId, Permissions.PROJECT_VIEW);
        return PageResponse.of(projects.findByNgoIdOrderByCreatedAtDesc(ngoId, PageRequest.of(Math.max(0, page), 50)), ProjectView::of);
    }

    @PostMapping("/api/v1/ngos/{ngoId}/projects")
    @ResponseStatus(HttpStatus.CREATED)
    public ProjectView createProject(@PathVariable UUID ngoId, @Valid @RequestBody ProjectRequest req) {
        Membership m = guard.require(ngoId, Permissions.PROJECT_MANAGE);
        return ProjectView.of(svc.createProject(ngoId, m.userId(), req.input()));
    }

    @GetMapping("/api/v1/ngos/{ngoId}/projects/{id}")
    public Map<String, Object> project(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.require(ngoId, Permissions.PROJECT_VIEW);
        Project p = svc.loadProject(ngoId, id);
        return Map.of("project", ProjectView.of(p),
                "milestones", jdbc.queryForList("select id, title, due_date, status, weight, completed_at from project_milestones where project_id = ? order by due_date nulls last", id),
                "assignments", jdbc.queryForList("select user_id, role, assigned_at from project_assignments where project_id = ?", id),
                "budget", jdbc.queryForList("select category, amount from project_budget_lines where project_id = ? order by category", id),
                "activity", jdbc.queryForList("select kind, text, actor_id, created_at from project_activities where project_id = ? order by created_at desc limit 50", id));
    }

    @PutMapping("/api/v1/ngos/{ngoId}/projects/{id}")
    public ProjectView updateProject(@PathVariable UUID ngoId, @PathVariable UUID id, @Valid @RequestBody ProjectRequest req) {
        Membership m = guard.require(ngoId, Permissions.PROJECT_MANAGE);
        if (req.version() == null) throw ApiException.badRequest("VERSION_REQUIRED", "version is required");
        return ProjectView.of(svc.updateProject(ngoId, m.userId(), id, req.input(), req.version()));
    }

    @PutMapping("/api/v1/ngos/{ngoId}/projects/{id}/status")
    public ProjectView projectStatus(@PathVariable UUID ngoId, @PathVariable UUID id, @Valid @RequestBody StatusChange req) {
        Membership m = guard.require(ngoId, Permissions.PROJECT_MANAGE);
        return ProjectView.of(svc.changeStatus(ngoId, m.userId(), id, req.status()));
    }

    @PutMapping("/api/v1/ngos/{ngoId}/projects/{id}/budget")
    public ProjectView budget(@PathVariable UUID ngoId, @PathVariable UUID id, @RequestBody Map<String, BigDecimal> lines) {
        Membership m = guard.require(ngoId, Permissions.PROJECT_MANAGE);
        if (lines.size() > 50) throw ApiException.badRequest("TOO_MANY_LINES", "At most 50 budget lines");
        return ProjectView.of(svc.setBudget(ngoId, m.userId(), id, lines));
    }

    @PostMapping("/api/v1/ngos/{ngoId}/projects/{id}/milestones")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Map<String, Object> addMilestone(@PathVariable UUID ngoId, @PathVariable UUID id, @Valid @RequestBody Milestone req) {
        Membership m = guard.require(ngoId, Permissions.PROJECT_MANAGE);
        svc.loadProject(ngoId, id);
        UUID mid = UUID.randomUUID();
        jdbc.update("insert into project_milestones(id, project_id, title, due_date, status, weight) values (?,?,?,?,'PENDING',?)",
                mid, id, req.title().trim(), req.dueDate(), req.weight());
        svc.activity(id, "MILESTONE", "Milestone added: " + req.title(), m.userId());
        svc.recalcProgress(id);
        return Map.of("id", mid);
    }

    @PutMapping("/api/v1/ngos/{ngoId}/projects/{id}/milestones/{milestoneId}")
    @Transactional
    public Map<String, Object> milestoneStatus(@PathVariable UUID ngoId, @PathVariable UUID id, @PathVariable UUID milestoneId,
                                               @Valid @RequestBody MilestoneStatus req) {
        Membership m = guard.require(ngoId, Permissions.PROJECT_MANAGE);
        svc.loadProject(ngoId, id);
        int n = jdbc.update("update project_milestones set status = ?, completed_at = case when ? = 'DONE' then now() else null end "
                + "where id = ? and project_id = ?", req.status(), req.status(), milestoneId, id);
        if (n == 0) throw ApiException.notFound("Milestone");
        svc.activity(id, "MILESTONE", "Milestone " + req.status().toLowerCase(), m.userId());
        svc.recalcProgress(id);
        return Map.of("progress", svc.loadProject(ngoId, id).getProgress());
    }

    @PutMapping("/api/v1/ngos/{ngoId}/projects/{id}/assignments")
    @Transactional
    public List<Map<String, Object>> assign(@PathVariable UUID ngoId, @PathVariable UUID id, @Valid @RequestBody Assignment req) {
        Membership m = guard.require(ngoId, Permissions.PROJECT_MANAGE);
        svc.loadProject(ngoId, id);
        jdbc.update("insert into project_assignments(project_id, user_id, role) values (?,?,?) on conflict (project_id, user_id) do update set role = excluded.role",
                id, req.userId(), req.role());
        svc.activity(id, "ASSIGNMENT", "Team member assigned", m.userId());
        return jdbc.queryForList("select user_id, role, assigned_at from project_assignments where project_id = ?", id);
    }

    @DeleteMapping("/api/v1/ngos/{ngoId}/projects/{id}/assignments/{userId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void unassign(@PathVariable UUID ngoId, @PathVariable UUID id, @PathVariable UUID userId) {
        guard.require(ngoId, Permissions.PROJECT_MANAGE);
        svc.loadProject(ngoId, id);
        jdbc.update("delete from project_assignments where project_id = ? and user_id = ?", id, userId);
    }

    @PostMapping("/api/v1/ngos/{ngoId}/projects/{id}/activity")
    @Transactional
    public Map<String, String> note(@PathVariable UUID ngoId, @PathVariable UUID id, @Valid @RequestBody Note req) {
        Membership m = guard.require(ngoId, Permissions.PROJECT_VIEW);
        svc.loadProject(ngoId, id);
        svc.activity(id, "NOTE", req.text().trim(), m.userId());
        return Map.of("status", "ok");
    }

    @PostMapping(value = "/api/v1/ngos/{ngoId}/projects/{id}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public Map<String, Object> document(@PathVariable UUID ngoId, @PathVariable UUID id, @RequestPart("file") MultipartFile file) throws IOException {
        Membership m = guard.require(ngoId, Permissions.PROJECT_MANAGE);
        svc.loadProject(ngoId, id);
        var f = FileInspector.inspect(file.getBytes(), 10L * 1024 * 1024, Set.of("application/pdf", "image/png", "image/jpeg"));
        String key = "ngos/" + ngoId + "/projects/" + id + "/" + UUID.randomUUID() + "." + f.extension();
        storage.put(key, file.getBytes(), f.contentType());
        UUID docId = UUID.randomUUID();
        jdbc.update("insert into project_documents(id, project_id, object_key, file_name, content_type, size_bytes, uploaded_by) values (?,?,?,?,?,?,?)",
                docId, id, key, FileInspector.safeName(file.getOriginalFilename()), f.contentType(), f.size(), m.userId());
        return Map.of("id", docId);
    }

    @GetMapping("/api/v1/ngos/{ngoId}/projects/{id}/documents")
    public List<Map<String, Object>> documents(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.require(ngoId, Permissions.PROJECT_VIEW);
        svc.loadProject(ngoId, id);
        return jdbc.queryForList("select id, file_name, content_type, size_bytes, created_at from project_documents where project_id = ? order by created_at desc", id);
    }

    // ---------------- internal

    @GetMapping("/internal/v1/ngos/{ngoId}/usage")
    public Map<String, Long> usage(@PathVariable UUID ngoId) {
        return Map.of("activeCampaigns", campaigns.countByNgoIdAndStatus(ngoId, Campaign.Status.PUBLISHED),
                "activeProjects", projects.countByNgoIdAndStatusIn(ngoId, Set.of(Project.Status.PLANNED, Project.Status.ACTIVE, Project.Status.ON_HOLD)));
    }

    @GetMapping("/internal/v1/campaigns/{id}")
    public Map<String, Object> internalCampaign(@PathVariable UUID id) {
        Campaign c = campaigns.findById(id).orElseThrow(() -> ApiException.notFound("Campaign"));
        return Map.of("id", c.getId(), "ngoId", c.getNgoId(), "title", c.getTitle(), "status", c.getStatus().name());
    }
}
