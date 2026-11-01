package org.ngoplatform.campaign.app;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.time.Clock;
import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.ngoplatform.campaign.domain.Campaign;
import org.ngoplatform.campaign.domain.Project;
import org.ngoplatform.campaign.domain.Repos;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.events.EventPublisher;
import org.ngoplatform.commons.events.EventTypes;
import org.ngoplatform.commons.money.Money;
import org.ngoplatform.commons.security.EntitlementResolver;
import org.ngoplatform.commons.security.Entitlements;
import org.ngoplatform.commons.security.Membership;
import org.ngoplatform.commons.storage.ObjectStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Campaigns & projects (spec §16) with plan limits on live campaigns/projects (spec §6.5).
 * Campaign workflow: DRAFT -submit-> PENDING_APPROVAL -approve-> PUBLISHED -close-> CLOSED -archive-> ARCHIVED;
 * reject sends a pending campaign back to DRAFT with a reason. See {@link CampaignRules} for who may do what.
 */
@Service
public class CampaignService {
    public record CampaignInput(String title, String summary, String story, String cause, BigDecimal goal, LocalDate start,
                                LocalDate end, UUID projectId) {}
    public record ProjectInput(String name, String description, String location, LocalDate start, LocalDate end) {}

    static final Set<Project.Status> ACTIVE_PROJECT = EnumSet.of(Project.Status.PLANNED, Project.Status.ACTIVE, Project.Status.ON_HOLD);

    private final Repos.Campaigns campaigns;
    private final Repos.Projects projects;
    private final EntitlementResolver entitlements;
    private final EventPublisher events;
    private final AuditLog audit;
    private final JdbcTemplate jdbc;
    private final Clock clock;
    private final NgoFacts ngoFacts;
    private final ObjectStorage storage;
    private final boolean testDonationsEnabled;

    public CampaignService(Repos.Campaigns campaigns, Repos.Projects projects, EntitlementResolver entitlements,
                           EventPublisher events, AuditLog audit, JdbcTemplate jdbc, Clock clock, NgoFacts ngoFacts,
                           ObjectStorage storage, @Value("${ngo.campaigns.test-donations:false}") boolean testDonationsEnabled) {
        this.campaigns = campaigns; this.projects = projects; this.entitlements = entitlements; this.events = events;
        this.audit = audit; this.jdbc = jdbc; this.clock = clock; this.ngoFacts = ngoFacts; this.storage = storage;
        this.testDonationsEnabled = testDonationsEnabled;
    }

    static final java.time.ZoneId INDIA = java.time.ZoneId.of("Asia/Kolkata");

    /** Campaign dates are Indian calendar dates. */
    public LocalDate today() { return LocalDate.now(clock.withZone(INDIA)); }

    static String slugify(String title) {
        String s = Normalizer.normalize(title, Normalizer.Form.NFD).replaceAll("\\p{M}", "").toLowerCase()
                .replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return s.isEmpty() ? "campaign" : s.substring(0, Math.min(s.length(), 100));
    }

    public Campaign load(UUID ngoId, UUID id) {
        return campaigns.findByIdAndNgoId(id, ngoId).orElseThrow(() -> ApiException.notFound("Campaign"));
    }

    private static String clean(String s) {
        if (s == null) return null;
        String t = s.strip();
        return t.isEmpty() ? null : t;
    }

    @Transactional
    public Campaign create(UUID ngoId, UUID actor, CampaignInput in) {
        CampaignRules.validate(in.cause(), in.goal(), in.start(), in.end());
        CampaignRules.checkRunnable(in.end(), today());
        if (in.projectId() != null) projects.findByIdAndNgoId(in.projectId(), ngoId).orElseThrow(() -> ApiException.notFound("Project"));
        Campaign c = new Campaign(ngoId, actor);
        String slug = slugify(in.title());
        String unique = slug;
        for (int i = 2; campaigns.existsByNgoIdAndSlug(ngoId, unique); i++) unique = slug + "-" + i;
        c.edit(in.title().strip(), unique, in.summary().strip(), clean(in.story()), in.cause(), Money.round(in.goal()),
                in.start(), in.end(), in.projectId());
        c.editedBy(actor);
        campaigns.save(c);
        audit.record(ngoId, actor, "CAMPAIGN_CREATED", "CAMPAIGN", c.getId(), Map.of());
        return c;
    }

    @Transactional
    public Campaign update(UUID ngoId, Membership m, UUID id, CampaignInput in, long expected) {
        CampaignRules.validate(in.cause(), in.goal(), in.start(), in.end());
        Campaign c = load(ngoId, id);
        if (c.getVersion() != expected) throw ApiException.conflict("STALE_VERSION", "Campaign changed by someone else. Reload.");
        CampaignRules.checkEdit(c.getStatus(), m.isAdmin());
        if (c.getStatus() == Campaign.Status.PUBLISHED) {
            if (in.goal().compareTo(c.getRaisedAmount()) < 0) {
                throw ApiException.rule("GOAL_BELOW_RAISED", "Goal cannot be below the amount already raised");
            }
            if (!in.start().equals(c.getStartDate())) throw ApiException.rule("START_LOCKED", "A live campaign's start date cannot change");
            CampaignRules.checkRunnable(in.end(), today());
        }
        if (in.projectId() != null) projects.findByIdAndNgoId(in.projectId(), ngoId).orElseThrow(() -> ApiException.notFound("Project"));
        c.edit(in.title().strip(), c.getSlug(), in.summary().strip(), clean(in.story()), in.cause(), Money.round(in.goal()),
                in.start(), in.end(), in.projectId());
        c.editedBy(m.userId());
        audit.record(ngoId, m.userId(), "CAMPAIGN_UPDATED", "CAMPAIGN", id, Map.of("status", c.getStatus().name()));
        return c;
    }

    /** A team member sends a draft to the administrators. */
    @Transactional
    public Campaign submit(UUID ngoId, Membership m, UUID id) {
        Campaign c = load(ngoId, id);
        CampaignRules.checkSubmit(c.getStatus());
        CampaignRules.checkRunnable(c.getEndDate(), today());
        c.submit(m.userId());
        events.publish(EventTypes.CAMPAIGN_SUBMITTED, ngoId, id, Map.of("title", c.getTitle(),
                "submittedBy", m.userId().toString()));
        audit.record(ngoId, m.userId(), "CAMPAIGN_SUBMITTED", "CAMPAIGN", id, Map.of());
        return c;
    }

    @Transactional
    public Campaign withdraw(UUID ngoId, Membership m, UUID id) {
        Campaign c = load(ngoId, id);
        CampaignRules.checkWithdraw(c.getStatus());
        c.withdraw();
        audit.record(ngoId, m.userId(), "CAMPAIGN_WITHDRAWN", "CAMPAIGN", id, Map.of());
        return c;
    }

    /**
     * An administrator approves exactly the version they reviewed: it goes live (pending request, or an admin's own
     * draft). Requires an active subscription, a verified NGO and room in the plan's active-campaign limit.
     */
    @Transactional
    public Campaign approve(UUID ngoId, Membership m, UUID id, long expected) {
        Campaign c = load(ngoId, id);
        CampaignRules.checkApprove(c.getStatus(), m.isAdmin());
        if (c.getVersion() != expected) throw ApiException.conflict("STALE_VERSION", "The campaign was edited after you opened it. Review the latest version.");
        CampaignRules.checkRunnable(c.getEndDate(), today());
        Entitlements e = entitlements.get(ngoId);
        if (!e.operationsAllowed()) throw ApiException.entitlement("SUBSCRIPTION_ACTIVE", "Renew your subscription to publish campaigns");
        if (!e.withinLimit(Entitlements.CAMPAIGN_ACTIVE_MAX, campaigns.countByNgoIdAndStatus(ngoId, Campaign.Status.PUBLISHED))) {
            throw ApiException.entitlement(Entitlements.CAMPAIGN_ACTIVE_MAX, "Your plan allows " + e.limit(Entitlements.CAMPAIGN_ACTIVE_MAX)
                    + " live campaigns. Close one or upgrade.");
        }
        NgoFacts.Facts ngo = ngoFacts.get(ngoId);
        if (!ngo.donationEligible()) {
            throw ApiException.rule("NGO_NOT_ELIGIBLE", "Campaigns go live only for verified, active organizations (" + ngo.eligibility() + ")");
        }
        UUID requester = c.getSubmittedBy() != null && c.getStatus() == Campaign.Status.PENDING_APPROVAL ? c.getSubmittedBy() : c.getCreatedBy();
        c.publish(m.userId(), ngo.displayName(), ngo.city());
        events.publish(EventTypes.CAMPAIGN_PUBLISHED, ngoId, id, Map.of("title", c.getTitle(),
                "requestedBy", requester.toString(), "approvedBy", m.userId().toString()));
        audit.record(ngoId, m.userId(), "CAMPAIGN_PUBLISHED", "CAMPAIGN", id, Map.of());
        return c;
    }

    @Transactional
    public Campaign reject(UUID ngoId, Membership m, UUID id, String reason) {
        Campaign c = load(ngoId, id);
        CampaignRules.checkReject(c.getStatus(), m.isAdmin(), reason);
        UUID requester = c.getSubmittedBy() != null ? c.getSubmittedBy() : c.getCreatedBy();
        c.reject(m.userId(), reason.strip());
        events.publish(EventTypes.CAMPAIGN_REJECTED, ngoId, id, Map.of("title", c.getTitle(),
                "requestedBy", requester.toString(), "reason", c.getRejectionReason()));
        audit.record(ngoId, m.userId(), "CAMPAIGN_REJECTED", "CAMPAIGN", id, Map.of());
        return c;
    }

    @Transactional
    public Campaign close(UUID ngoId, Membership m, UUID id) {
        Campaign c = load(ngoId, id);
        CampaignRules.checkClose(c.getStatus(), m.isAdmin());
        c.close();
        audit.record(ngoId, m.userId(), "CAMPAIGN_CLOSED", "CAMPAIGN", id, Map.of());
        return c;
    }

    @Transactional
    public Campaign archive(UUID ngoId, Membership m, UUID id) {
        Campaign c = load(ngoId, id);
        CampaignRules.checkArchive(c.getStatus(), m.isAdmin());
        // Archiving closes the books: the account sheet becomes final, so nothing may be waiting for approval.
        Integer pending = jdbc.queryForObject("select count(*) from campaign_expenses where campaign_id = ? and status = 'SUBMITTED'",
                Integer.class, id);
        if (pending != null && pending > 0) {
            throw ApiException.rule("EXPENSES_PENDING", pending + " expense(s) are still waiting for approval. Approve or reject them before archiving.");
        }
        c.archive();
        audit.record(ngoId, m.userId(), "CAMPAIGN_ARCHIVED", "CAMPAIGN", id, Map.of());
        return c;
    }

    // ---------------- photos (up to MAX_PHOTOS; position 0 is the cover)

    public static final int MAX_PHOTOS = 5;

    public record Photo(UUID id, int position, String key) {}

    public List<Photo> photos(UUID campaignId) {
        return jdbc.query("select id, position, image_key from campaign_photos where campaign_id = ? order by position, created_at",
                (rs, i) -> new Photo(rs.getObject(1, UUID.class), rs.getInt(2), rs.getString(3)), campaignId);
    }

    public Photo photo(UUID campaignId, UUID photoId) {
        return photos(campaignId).stream().filter(p -> p.id().equals(photoId)).findFirst()
                .orElseThrow(() -> ApiException.notFound("Photo"));
    }

    /**
     * Adds a photo (re-encoded: 16:9, 1200 + 480 px JPEG, metadata/GPS stripped). {@code asCover} puts it first; when
     * the gallery is full, a new cover replaces the current cover, otherwise the request is refused.
     */
    @Transactional
    public Campaign addPhoto(UUID ngoId, Membership m, UUID id, byte[] upload, boolean asCover) {
        Campaign c = load(ngoId, id);
        CampaignRules.checkEdit(c.getStatus(), m.isAdmin());
        if (upload.length > 5L * 1024 * 1024) throw ApiException.badRequest("IMAGE_TOO_LARGE", "Pictures can be at most 5 MB");
        List<Photo> existing = photos(id);
        if (existing.size() >= MAX_PHOTOS) {
            if (!asCover) throw ApiException.rule("PHOTO_LIMIT", "A campaign can have at most " + MAX_PHOTOS + " photos. Remove one first.");
            deletePhotoRow(existing.getFirst());
        }
        CoverImage.Result r = CoverImage.process(upload);
        UUID photoId = UUID.randomUUID();
        String base = "ngos/" + ngoId + "/campaigns/" + id + "/photo-" + photoId;
        storage.put(base + "-1200.jpg", r.large(), "image/jpeg");
        storage.put(base + "-480.jpg", r.small(), "image/jpeg");
        if (asCover) jdbc.update("update campaign_photos set position = position + 1 where campaign_id = ?", id);
        jdbc.update("insert into campaign_photos(id, campaign_id, position, image_key, created_by) values (?,?,?,?,?)",
                photoId, id, asCover ? 0 : photos(id).size(), base, m.userId());
        renumber(id);
        syncCover(c, m.userId());
        audit.record(ngoId, m.userId(), "CAMPAIGN_PHOTO_ADDED", "CAMPAIGN", id, Map.of("photoId", photoId.toString()));
        return c;
    }

    @Transactional
    public Campaign removePhoto(UUID ngoId, Membership m, UUID id, UUID photoId) {
        Campaign c = load(ngoId, id);
        CampaignRules.checkEdit(c.getStatus(), m.isAdmin());
        deletePhotoRow(photo(id, photoId));
        renumber(id);
        syncCover(c, m.userId());
        audit.record(ngoId, m.userId(), "CAMPAIGN_PHOTO_REMOVED", "CAMPAIGN", id, Map.of("photoId", photoId.toString()));
        return c;
    }

    @Transactional
    public Campaign makeCover(UUID ngoId, Membership m, UUID id, UUID photoId) {
        Campaign c = load(ngoId, id);
        CampaignRules.checkEdit(c.getStatus(), m.isAdmin());
        Photo p = photo(id, photoId);
        jdbc.update("update campaign_photos set position = position + 1 where campaign_id = ? and position < ?", id, p.position());
        jdbc.update("update campaign_photos set position = 0 where id = ?", photoId);
        renumber(id);
        syncCover(c, m.userId());
        return c;
    }

    private void deletePhotoRow(Photo p) {
        jdbc.update("delete from campaign_photos where id = ?", p.id());
        for (String k : List.of(p.key() + "-1200.jpg", p.key() + "-480.jpg")) {
            try { storage.delete(k); } catch (RuntimeException ignored) { /* best effort; orphan files are harmless */ }
        }
    }

    private void renumber(UUID campaignId) {
        jdbc.update("update campaign_photos p set position = r.rn - 1 from (select id, row_number() over (order by position, created_at) rn "
                + "from campaign_photos where campaign_id = ?) r where p.id = r.id", campaignId);
    }

    /** campaigns.image_key mirrors the cover so list views need no extra query; the version busts image caches. */
    private void syncCover(Campaign c, UUID actor) {
        List<Photo> ps = photos(c.getId());
        c.setImageKey(ps.isEmpty() ? null : ps.getFirst().key());
        c.editedBy(actor);
    }

    @Scheduled(cron = "${ngo.campaigns.close-cron:0 15 0 * * *}", zone = "Asia/Kolkata")
    @Transactional
    public void autoClose() {
        for (Campaign c : campaigns.findByStatusAndEndDateBefore(Campaign.Status.PUBLISHED, today())) {
            c.close();
            audit.record(c.getNgoId(), null, "CAMPAIGN_AUTO_CLOSED", "CAMPAIGN", c.getId(), Map.of());
        }
    }

    /** Development stand-in for online payments: credits a test donation so progress can be exercised end to end. */
    @Transactional
    public Campaign recordTestDonation(UUID ngoId, UUID actor, UUID id, BigDecimal amount, String donorLabel) {
        if (!testDonationsEnabled) throw ApiException.forbidden("TEST_DONATIONS_DISABLED", "Test donations are disabled");
        Campaign c = load(ngoId, id);
        CampaignRules.checkAcceptsDonation(c.getStatus(), c.getStartDate(), c.getEndDate(), today(), amount);
        BigDecimal amt = Money.round(amount);
        jdbc.update("insert into campaign_donations(donation_id, campaign_id, amount, is_test, donor_label, recorded_by) values (?,?,?,true,?,?)",
                UUID.randomUUID(), id, amt, clean(donorLabel), actor);
        c.addDonation(amt);
        audit.record(ngoId, actor, "CAMPAIGN_TEST_DONATION", "CAMPAIGN", id, Map.of("amount", amt.toPlainString()));
        return c;
    }

    // ---------------- donations from events (idempotent)

    @Transactional
    public void onDonationPaid(UUID ngoId, UUID donationId, UUID campaignId, UUID projectId, BigDecimal amount) {
        if (campaignId != null) {
            int ins = jdbc.update("insert into campaign_donations(donation_id, campaign_id, amount) values (?,?,?) on conflict do nothing",
                    donationId, campaignId, amount);
            if (ins == 1) campaigns.findByIdAndNgoId(campaignId, ngoId).ifPresent(c -> c.addDonation(amount));
        }
        if (projectId != null) projects.findByIdAndNgoId(projectId, ngoId).ifPresent(p -> p.addRaised(amount));
    }

    @Transactional
    public void onDonationRefunded(UUID donationId, BigDecimal refundedTotal) {
        List<Map<String, Object>> rows = jdbc.queryForList("select campaign_id, refunded from campaign_donations where donation_id = ?", donationId);
        if (rows.isEmpty()) return;
        BigDecimal prev = (BigDecimal) rows.getFirst().get("refunded");
        BigDecimal delta = refundedTotal.subtract(prev);
        if (delta.signum() <= 0) return;
        jdbc.update("update campaign_donations set refunded = ? where donation_id = ?", refundedTotal, donationId);
        campaigns.findById((UUID) rows.getFirst().get("campaign_id")).ifPresent(c -> c.removeAmount(delta));
    }

    // ---------------- projects

    public Project loadProject(UUID ngoId, UUID id) {
        return projects.findByIdAndNgoId(id, ngoId).orElseThrow(() -> ApiException.notFound("Project"));
    }

    @Transactional
    public Project createProject(UUID ngoId, UUID actor, ProjectInput in) {
        if (in.end() != null && in.end().isBefore(in.start())) throw ApiException.badRequest("INVALID_DATES", "End date is before start date");
        Entitlements e = entitlements.get(ngoId);
        if (!e.operationsAllowed()) throw ApiException.entitlement("SUBSCRIPTION_ACTIVE", "Renew your subscription to create projects");
        if (!e.withinLimit(Entitlements.PROJECT_ACTIVE_MAX, projects.countByNgoIdAndStatusIn(ngoId, ACTIVE_PROJECT))) {
            throw ApiException.entitlement(Entitlements.PROJECT_ACTIVE_MAX, "Your plan allows " + e.limit(Entitlements.PROJECT_ACTIVE_MAX)
                    + " active projects. Close one or upgrade.");
        }
        Project p = new Project(ngoId, actor);
        p.edit(in.name().trim(), in.description(), in.location(), in.start(), in.end());
        projects.saveAndFlush(p);
        activity(p.getId(), "CREATED", "Project created", actor);
        audit.record(ngoId, actor, "PROJECT_CREATED", "PROJECT", p.getId(), Map.of());
        return p;
    }

    @Transactional
    public Project updateProject(UUID ngoId, UUID actor, UUID id, ProjectInput in, long expected) {
        Project p = loadProject(ngoId, id);
        if (p.getVersion() != expected) throw ApiException.conflict("STALE_VERSION", "Project changed by someone else. Reload.");
        if (p.getStatus() == Project.Status.CLOSED) throw ApiException.rule("PROJECT_CLOSED", "Closed projects cannot be edited");
        if (in.end() != null && in.end().isBefore(in.start())) throw ApiException.badRequest("INVALID_DATES", "End date is before start date");
        p.edit(in.name().trim(), in.description(), in.location(), in.start(), in.end());
        activity(id, "UPDATED", "Project details updated", actor);
        return p;
    }

    static final Map<Project.Status, Set<Project.Status>> PROJECT_TRANSITIONS = Map.of(
            Project.Status.PLANNED, EnumSet.of(Project.Status.ACTIVE, Project.Status.CLOSED),
            Project.Status.ACTIVE, EnumSet.of(Project.Status.ON_HOLD, Project.Status.COMPLETED),
            Project.Status.ON_HOLD, EnumSet.of(Project.Status.ACTIVE, Project.Status.CLOSED),
            Project.Status.COMPLETED, EnumSet.of(Project.Status.CLOSED, Project.Status.ACTIVE),
            Project.Status.CLOSED, EnumSet.noneOf(Project.Status.class));

    @Transactional
    public Project changeStatus(UUID ngoId, UUID actor, UUID id, Project.Status to) {
        Project p = loadProject(ngoId, id);
        if (!PROJECT_TRANSITIONS.get(p.getStatus()).contains(to)) {
            throw ApiException.rule("INVALID_PROJECT_TRANSITION", "Cannot move project from " + p.getStatus() + " to " + to);
        }
        if (!ACTIVE_PROJECT.contains(p.getStatus()) && ACTIVE_PROJECT.contains(to)) {
            Entitlements e = entitlements.get(ngoId);
            if (!e.withinLimit(Entitlements.PROJECT_ACTIVE_MAX, projects.countByNgoIdAndStatusIn(ngoId, ACTIVE_PROJECT))) {
                throw ApiException.entitlement(Entitlements.PROJECT_ACTIVE_MAX, "Active project limit reached");
            }
        }
        p.setStatus(to);
        activity(id, "STATUS", "Status changed to " + to, actor);
        audit.record(ngoId, actor, "PROJECT_STATUS_" + to, "PROJECT", id, Map.of());
        return p;
    }

    @Transactional
    public Project setBudget(UUID ngoId, UUID actor, UUID id, Map<String, BigDecimal> lines) {
        Project p = loadProject(ngoId, id);
        BigDecimal total = BigDecimal.ZERO;
        jdbc.update("delete from project_budget_lines where project_id = ?", id);
        for (var e : lines.entrySet()) {
            if (e.getKey() == null || e.getKey().isBlank() || e.getKey().length() > 60) throw ApiException.badRequest("INVALID_CATEGORY", "Invalid budget category");
            if (e.getValue() == null || e.getValue().signum() < 0 || e.getValue().stripTrailingZeros().scale() > 2) {
                throw ApiException.badRequest("INVALID_AMOUNT", "Budget amounts must be ≥ 0 with at most 2 decimals");
            }
            jdbc.update("insert into project_budget_lines(id, project_id, category, amount) values (?,?,?,?)", UUID.randomUUID(), id, e.getKey().trim(), e.getValue());
            total = total.add(e.getValue());
        }
        p.setBudget(total);
        activity(id, "BUDGET", "Budget set to ₹" + total.toPlainString(), actor);
        return p;
    }

    @Transactional
    public void recalcProgress(UUID projectId) {
        Integer pct = jdbc.queryForObject("select coalesce(round(100.0 * sum(case when status = 'DONE' then weight else 0 end) "
                + "/ nullif(sum(weight), 0)), 0) from project_milestones where project_id = ?", Integer.class, projectId);
        projects.findById(projectId).ifPresent(p -> p.setProgress(pct == null ? 0 : pct));
    }

    @Transactional
    public void onExpenseApproved(UUID ngoId, UUID expenseId, UUID projectId, BigDecimal amount) {
        int ins = jdbc.update("insert into project_expense_facts(expense_id, project_id, amount) values (?,?,?) on conflict do nothing",
                expenseId, projectId, amount);
        if (ins == 1) projects.findByIdAndNgoId(projectId, ngoId).ifPresent(p -> {
            p.addSpent(amount);
            activity(projectId, "EXPENSE", "Expense of ₹" + amount.toPlainString() + " recorded", null);
        });
    }

    public void activity(UUID projectId, String kind, String text, UUID actor) {
        jdbc.update("insert into project_activities(id, project_id, kind, text, actor_id) values (?,?,?,?,?)",
                UUID.randomUUID(), projectId, kind, text, actor);
    }
}
