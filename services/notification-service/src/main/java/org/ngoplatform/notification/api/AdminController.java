package org.ngoplatform.notification.api;

import jakarta.validation.Valid;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.ngoplatform.commons.audit.AuditLog;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.security.CurrentUser;
import org.ngoplatform.commons.web.PageResponse;
import org.ngoplatform.notification.app.TemplateRenderer;
import org.ngoplatform.notification.domain.Delivery;
import org.ngoplatform.notification.domain.Repos;
import org.ngoplatform.notification.domain.Template;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** ADMIN-016 templates + preview (NOTIF-008), and the delivery dead-letter queue for platform support. */
@RestController
public class AdminController {
    private final AccessGuard guard;
    private final Repos.Templates templates;
    private final Repos.Deliveries deliveries;
    private final AuditLog audit;
    private final Clock clock;

    public AdminController(AccessGuard guard, Repos.Templates templates, Repos.Deliveries deliveries, AuditLog audit,
                           Clock clock) {
        this.guard = guard; this.templates = templates; this.deliveries = deliveries; this.audit = audit; this.clock = clock;
    }

    @GetMapping("/api/v1/admin/notification-templates")
    public List<Dtos.TemplateView> list() {
        guard.requirePlatformSupport();
        return templates.findAllByOrderByCodeAscChannelAscLanguageAsc().stream().map(AdminController::view).toList();
    }

    @GetMapping("/api/v1/admin/notification-templates/{id}")
    public Dtos.TemplateView get(@PathVariable UUID id) {
        guard.requirePlatformSupport();
        return view(templates.findById(id).orElseThrow(() -> ApiException.notFound("Template")));
    }

    /**
     * Edits keep the placeholder contract: a new version may not introduce variables the sender does not supply
     * (they would render empty in real messages).
     */
    @PutMapping("/api/v1/admin/notification-templates/{id}")
    @Transactional
    public Dtos.TemplateView update(@PathVariable UUID id, @Valid @RequestBody Dtos.TemplateUpdate body) {
        CurrentUser admin = guard.requirePlatformAdmin();
        Template t = templates.findById(id).orElseThrow(() -> ApiException.notFound("Template"));
        if (t.getVersion() != body.version()) {
            throw ApiException.conflict("STALE_TEMPLATE", "Someone else changed this template. Reload and try again.");
        }
        if ("EMAIL".equals(t.getChannel()) && (body.subject() == null || body.subject().isBlank())) {
            throw ApiException.badRequest("SUBJECT_REQUIRED", "E-mail templates need a subject");
        }
        Set<String> allowed = new java.util.HashSet<>(TemplateRenderer.placeholders(t.getSubject()));
        allowed.addAll(TemplateRenderer.placeholders(t.getBody()));
        Set<String> used = new java.util.LinkedHashSet<>(TemplateRenderer.placeholders(body.subject()));
        used.addAll(TemplateRenderer.placeholders(body.body()));
        used.removeAll(allowed);
        if (!used.isEmpty()) {
            throw ApiException.rule("UNKNOWN_PLACEHOLDER", "Unknown placeholders: " + String.join(", ", used));
        }
        t.update(body.subject(), body.body(), body.active(), admin.userId());
        templates.save(t);
        audit.record(null, admin.userId(), "NOTIFICATION_TEMPLATE_UPDATED", "NOTIFICATION_TEMPLATE", t.getId(),
                Map.of("code", t.getCode(), "channel", t.getChannel(), "language", t.getLanguage(), "active", body.active()));
        return view(t);
    }

    @PostMapping("/api/v1/admin/notification-templates/{id}/preview")
    public Dtos.Preview preview(@PathVariable UUID id, @RequestBody(required = false) Dtos.PreviewRequest body) {
        guard.requirePlatformSupport();
        Template t = templates.findById(id).orElseThrow(() -> ApiException.notFound("Template"));
        String subject = body != null && body.subject() != null ? body.subject() : t.getSubject();
        String text = body != null && body.body() != null ? body.body() : t.getBody();
        Set<String> names = new java.util.LinkedHashSet<>(TemplateRenderer.placeholders(subject));
        names.addAll(TemplateRenderer.placeholders(text));
        Map<String, Object> vars = new HashMap<>();
        for (String n : names) vars.put(n, "[" + n + "]");
        if (body != null && body.sample() != null) vars.putAll(body.sample());
        return new Dtos.Preview(TemplateRenderer.render(subject, vars), TemplateRenderer.render(text, vars), names);
    }

    @GetMapping("/api/v1/notifications/admin/deliveries")
    public PageResponse<Dtos.DeliveryView> deliveries(@RequestParam(defaultValue = "DEAD") Delivery.Status status,
                                                      @RequestParam(defaultValue = "0") int page) {
        guard.requirePlatformSupport();
        return PageResponse.of(deliveries.findByStatusOrderByCreatedAtDesc(status, PageRequest.of(Math.max(page, 0), 20)),
                Dtos.DeliveryView::of);
    }

    @PostMapping("/api/v1/notifications/admin/deliveries/{id}/retry")
    @Transactional
    public Dtos.DeliveryView retry(@PathVariable UUID id) {
        CurrentUser support = guard.requirePlatformSupport();
        Delivery d = deliveries.findById(id).orElseThrow(() -> ApiException.notFound("Delivery"));
        try {
            d.requeue(clock.instant());
        } catch (IllegalStateException e) {
            throw ApiException.rule("NOT_RETRYABLE", e.getMessage());
        }
        deliveries.save(d);
        audit.record(d.getNgoId(), support.userId(), "NOTIFICATION_RETRY_REQUESTED", "NOTIFICATION_DELIVERY", d.getId(), Map.of());
        return Dtos.DeliveryView.of(d);
    }

    private static Dtos.TemplateView view(Template t) {
        Set<String> p = new java.util.LinkedHashSet<>(TemplateRenderer.placeholders(t.getSubject()));
        p.addAll(TemplateRenderer.placeholders(t.getBody()));
        return Dtos.TemplateView.of(t, p);
    }
}
