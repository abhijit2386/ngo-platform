package org.ngoplatform.receipt.app;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.EntitlementResolver;
import org.ngoplatform.commons.security.Entitlements;
import org.ngoplatform.commons.storage.ObjectStorage;
import org.ngoplatform.receipt.domain.Repos;
import org.ngoplatform.receipt.domain.Template;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Receipt designers (spec §15.2/15.3). BASIC (Professional+): one active template, fixed field set, publish directly.
 * ADVANCED (Enterprise): multiple templates, conditions, versions, maker-checker approval, rollback.
 * Existing templates are never deleted on downgrade; they simply stop applying (standard receipts are used).
 */
@Service
public class TemplateService {
    private final Repos.Headers headers;
    private final Repos.Versions versions;
    private final Repos.Approvals approvals;
    private final ObjectStorage storage;
    private final EntitlementResolver entitlements;
    private final PdfRenderer pdf;
    private final AuditLog audit;
    private final ObjectMapper mapper;
    private final ReceiptService receipts;

    public TemplateService(Repos.Headers headers, Repos.Versions versions, Repos.Approvals approvals, ObjectStorage storage,
                           EntitlementResolver entitlements, PdfRenderer pdf, AuditLog audit, ObjectMapper mapper, ReceiptService receipts) {
        this.headers = headers; this.versions = versions; this.approvals = approvals; this.storage = storage;
        this.entitlements = entitlements; this.pdf = pdf; this.audit = audit; this.mapper = mapper; this.receipts = receipts;
    }

    static final TemplateDefinition.Page A5_LANDSCAPE = new TemplateDefinition.Page(595.28f, 419.53f);

    Template.Header.Kind allowedKind(UUID ngoId) {
        Entitlements e = entitlements.get(ngoId);
        if (!e.operationsAllowed()) throw ApiException.entitlement("SUBSCRIPTION_ACTIVE", "Renew your subscription to edit receipt templates");
        if (e.flag(Entitlements.RECEIPT_ADVANCED_DESIGNER)) return Template.Header.Kind.ADVANCED;
        if (e.flag(Entitlements.RECEIPT_BASIC_DESIGNER)) return Template.Header.Kind.BASIC;
        throw ApiException.entitlement(Entitlements.RECEIPT_BASIC_DESIGNER, "Custom receipts need the Professional plan or higher");
    }

    public Template.Header load(UUID ngoId, UUID id) {
        return headers.findByIdAndNgoId(id, ngoId).orElseThrow(() -> ApiException.notFound("Template"));
    }

    @Transactional
    public Template.Header create(UUID ngoId, UUID actor, String name, TemplateDefinition def) {
        Template.Header.Kind kind = allowedKind(ngoId);
        if (kind == Template.Header.Kind.BASIC && headers.countByNgoIdAndStatusNot(ngoId, Template.Header.Status.ARCHIVED) >= 1) {
            throw ApiException.entitlement(Entitlements.RECEIPT_ADVANCED_DESIGNER,
                    "Your plan allows one custom template. Edit the existing one or upgrade to Enterprise for more.");
        }
        if (headers.findByNgoIdOrderByUpdatedAtDesc(ngoId).stream()
                .anyMatch(x -> x.getStatus() != Template.Header.Status.ARCHIVED && x.getName().equalsIgnoreCase(name.trim()))) {
            throw ApiException.conflict("NAME_TAKEN", "A design with this name already exists");
        }
        Template.Header h = headers.save(new Template.Header(ngoId, name.trim(), kind, actor));
        versions.save(new Template.Version(h.getId(), 1, json(def == null ? TemplateDefinition.defaults(A5_LANDSCAPE) : def), null, actor));
        audit.record(ngoId, actor, "RECEIPT_TEMPLATE_CREATED", "RECEIPT_TEMPLATE", h.getId(), Map.of("kind", kind.name()));
        return h;
    }

    /** Saving creates a new draft version (history is immutable). */
    @Transactional
    public Template.Header saveDraft(UUID ngoId, UUID actor, UUID id, TemplateDefinition def, long expectedVersion) {
        allowedKind(ngoId);
        Template.Header h = load(ngoId, id);
        if (h.getVersion() != expectedVersion) throw ApiException.conflict("STALE_VERSION", "Template changed by someone else. Reload.");
        if (h.getStatus() == Template.Header.Status.PENDING_APPROVAL) throw ApiException.rule("PENDING_APPROVAL", "Withdraw approval first");
        Template.Version cur = versions.findByTemplateIdAndVersionNo(id, h.getCurrentVersion()).orElseThrow();
        int next = h.getCurrentVersion() + 1;
        versions.save(new Template.Version(id, next, json(def), cur.getBackgroundKey(), actor));
        h.newVersion(next);
        audit.record(ngoId, actor, "RECEIPT_TEMPLATE_SAVED", "RECEIPT_TEMPLATE", id, Map.of("version", next));
        return h;
    }

    /** Background: PNG/JPEG only (owner decision 2026-11-01), re-encoded as JPEG; page proportions follow the image. */
    @Transactional
    public Template.Header uploadBackground(UUID ngoId, UUID actor, UUID id, byte[] data) {
        allowedKind(ngoId);
        Template.Header h = load(ngoId, id);
        Images.Background bg = Images.background(data);
        String key = "ngos/" + ngoId + "/receipts/templates/" + id + "/bg-" + UUID.randomUUID() + ".jpg";
        storage.put(key, bg.jpeg(), "image/jpeg");
        Template.Version cur = versions.findByTemplateIdAndVersionNo(id, h.getCurrentVersion()).orElseThrow();
        int next = h.getCurrentVersion() + 1;
        String def;
        try {
            TemplateDefinition d = mapper.readValue(cur.getDefinition(), TemplateDefinition.class);
            def = json(d.withPage(TemplateDefinition.pageFor(bg.width(), bg.height())));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
        versions.save(new Template.Version(id, next, def, key, actor));
        h.newVersion(next);
        audit.record(ngoId, actor, "RECEIPT_TEMPLATE_BACKGROUND", "RECEIPT_TEMPLATE", id, Map.of("version", next));
        return h;
    }

    public TemplateDefinition definition(UUID templateId, int version) {
        try {
            return mapper.readValue(versions.findByTemplateIdAndVersionNo(templateId, version).orElseThrow().getDefinition(),
                    TemplateDefinition.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public TemplateDefinition.Result validate(UUID ngoId, UUID id) {
        Template.Header h = load(ngoId, id);
        return definition(id, h.getCurrentVersion()).validate(h.getKind() == Template.Header.Kind.ADVANCED);
    }

    /** REC-013: preview with this NGO's details and signatories and a sample donor (never real donor data). */
    public byte[] preview(UUID ngoId, UUID id) {
        Template.Header h = load(ngoId, id);
        Template.Version v = versions.findByTemplateIdAndVersionNo(id, h.getCurrentVersion()).orElseThrow();
        return receipts.previewTemplate(ngoId, definition(id, h.getCurrentVersion()), background(v));
    }

    /** The background picture of the current version (editor), or null. */
    public byte[] background(UUID ngoId, UUID id) {
        Template.Header h = load(ngoId, id);
        byte[] b = background(versions.findByTemplateIdAndVersionNo(id, h.getCurrentVersion()).orElseThrow());
        if (b == null) throw ApiException.notFound("Background");
        return b;
    }

    private byte[] background(Template.Version v) {
        if (v.getBackgroundKey() == null) return null;
        try (InputStream in = storage.get(v.getBackgroundKey())) { return in.readAllBytes(); } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** "Use the standard receipt again": the design stays saved and can be switched back on. */
    @Transactional
    public Template.Header setActive(UUID ngoId, UUID actor, UUID id, boolean active) {
        Template.Header h = load(ngoId, id);
        if (active) {
            allowedKind(ngoId);
            if (h.getPublishedVersion() == null) throw ApiException.rule("NOT_PUBLISHED", "Publish the design first");
            headers.findByNgoIdAndActiveTrue(ngoId).filter(o -> !o.getId().equals(id)).ifPresent(o -> { o.setActive(false); headers.saveAndFlush(o); });
        }
        h.setActive(active);
        audit.record(ngoId, actor, active ? "RECEIPT_TEMPLATE_ACTIVATED" : "RECEIPT_TEMPLATE_DEACTIVATED", "RECEIPT_TEMPLATE", id, Map.of());
        return h;
    }

    /** BASIC publishes directly; ADVANCED goes through maker-checker approval. */
    @Transactional
    public Template.Header submit(UUID ngoId, UUID actor, UUID id) {
        allowedKind(ngoId);
        Template.Header h = load(ngoId, id);
        TemplateDefinition.Result res = validate(ngoId, id);
        if (!res.ok()) throw ApiException.rule("TEMPLATE_INVALID", String.join("; ", res.errors()));
        if (h.getKind() == Template.Header.Kind.BASIC) {
            publish(h, h.getCurrentVersion(), actor);
            return h;
        }
        h.setStatus(Template.Header.Status.PENDING_APPROVAL);
        approvals.save(new Template.Approval(id, h.getCurrentVersion(), actor));
        audit.record(ngoId, actor, "RECEIPT_TEMPLATE_SUBMITTED", "RECEIPT_TEMPLATE", id, Map.of("version", h.getCurrentVersion()));
        return h;
    }

    @Transactional
    public Template.Header decide(UUID ngoId, UUID actor, UUID id, boolean approve, String comment) {
        Template.Header h = load(ngoId, id);
        if (h.getStatus() != Template.Header.Status.PENDING_APPROVAL) throw ApiException.rule("NOT_PENDING", "Nothing to approve");
        Template.Approval a = approvals.findFirstByTemplateIdAndDecisionIsNullOrderByCreatedAtDesc(id).orElseThrow();
        if (a.getRequestedBy().equals(actor)) throw ApiException.forbidden("MAKER_CHECKER", "Another person must approve this template");
        a.decide(approve, actor, comment);
        if (approve) publish(h, a.getVersionNo(), actor);
        else h.setStatus(Template.Header.Status.DRAFT);
        audit.record(ngoId, actor, approve ? "RECEIPT_TEMPLATE_APPROVED" : "RECEIPT_TEMPLATE_REJECTED", "RECEIPT_TEMPLATE", id, Map.of());
        return h;
    }

    private void publish(Template.Header h, int version, UUID actor) {
        h.publish(version);
        headers.findByNgoIdAndActiveTrue(h.getNgoId()).filter(o -> !o.getId().equals(h.getId())).ifPresent(o -> {
            o.setActive(false);
            headers.saveAndFlush(o);
        });
        h.setActive(true);
        audit.record(h.getNgoId(), actor, "RECEIPT_TEMPLATE_PUBLISHED", "RECEIPT_TEMPLATE", h.getId(), Map.of("version", version));
    }

    /** REC-019: roll back to an earlier version by copying it as the newest version. */
    @Transactional
    public Template.Header rollback(UUID ngoId, UUID actor, UUID id, int toVersion) {
        Template.Header h = load(ngoId, id);
        if (h.getKind() != Template.Header.Kind.ADVANCED) throw ApiException.entitlement(Entitlements.RECEIPT_ADVANCED_DESIGNER, "Rollback is an Enterprise feature");
        allowedKind(ngoId);
        Template.Version old = versions.findByTemplateIdAndVersionNo(id, toVersion).orElseThrow(() -> ApiException.notFound("Version"));
        int next = h.getCurrentVersion() + 1;
        versions.save(new Template.Version(id, next, old.getDefinition(), old.getBackgroundKey(), actor));
        h.newVersion(next);
        audit.record(ngoId, actor, "RECEIPT_TEMPLATE_ROLLED_BACK", "RECEIPT_TEMPLATE", id, Map.of("from", toVersion));
        return h;
    }

    @Transactional
    public Template.Header duplicate(UUID ngoId, UUID actor, UUID id, String name) {
        if (allowedKind(ngoId) != Template.Header.Kind.ADVANCED) {
            throw ApiException.entitlement(Entitlements.RECEIPT_ADVANCED_DESIGNER, "Multiple templates need Enterprise");
        }
        Template.Header src = load(ngoId, id);
        Template.Version v = versions.findByTemplateIdAndVersionNo(id, src.getCurrentVersion()).orElseThrow();
        Template.Header copy = headers.save(new Template.Header(ngoId, name.trim(), Template.Header.Kind.ADVANCED, actor));
        versions.save(new Template.Version(copy.getId(), 1, v.getDefinition(), v.getBackgroundKey(), actor));
        return copy;
    }

    @Transactional
    public Template.Header archive(UUID ngoId, UUID actor, UUID id) {
        Template.Header h = load(ngoId, id);
        h.setActive(false);
        h.setStatus(Template.Header.Status.ARCHIVED);
        audit.record(ngoId, actor, "RECEIPT_TEMPLATE_ARCHIVED", "RECEIPT_TEMPLATE", id, Map.of());
        return h;
    }

    String json(Object o) {
        try { return mapper.writeValueAsString(o); } catch (JsonProcessingException e) { throw new IllegalStateException(e); }
    }
}
