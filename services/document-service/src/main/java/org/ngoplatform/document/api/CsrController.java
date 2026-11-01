package org.ngoplatform.document.api;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.security.Entitlements;
import org.ngoplatform.commons.security.Membership;
import org.ngoplatform.document.app.CsrRules;
import org.ngoplatform.document.app.CsrService;
import org.ngoplatform.document.app.DocFiles;
import org.ngoplatform.document.app.NgoNames;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/**
 * CSR compliance pack (Enterprise: CSR_COMPLIANCE_PACK). NGO administrators build and share packs; reading old packs
 * stays possible after a downgrade. The company opens /api/v1/public/csr/{code} (no login) until the link expires
 * or is stopped; every open and download is logged for the NGO.
 */
@RestController
public class CsrController {
    public record PackRequest(String title, String recipientName, String recipientEmail, String message, Integer validDays,
                              Boolean autoFill, Long version) {
        CsrService.Input input() { return new CsrService.Input(title, recipientName, recipientEmail, message, validDays); }
    }
    public record ItemRequest(UUID documentId, String kind) {}
    public record ShareRequest(Long version, Boolean acceptWarnings) {}

    private static final String BASE = "/api/v1/ngos/{ngoId}/csr";
    private static final String NEEDS = "CSR compliance packs are part of the Enterprise plan";
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");
    private static final MediaType HTML = MediaType.parseMediaType("text/html; charset=UTF-8");
    /** The API-wide policy (default-src 'none') would block this page's inline styles; nothing else is allowed. */
    static final String PAGE_CSP = "default-src 'none'; style-src 'unsafe-inline'; base-uri 'none'; form-action 'none'; frame-ancestors 'none'";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);

    private final CsrService csr;
    private final AccessGuard guard;
    private final NgoNames ngoNames;
    private final String publicApi;

    public CsrController(CsrService csr, AccessGuard guard, NgoNames ngoNames,
                         @Value("${ngo.documents.public-api-url:http://localhost:8080}") String publicApi) {
        this.csr = csr; this.guard = guard; this.ngoNames = ngoNames; this.publicApi = publicApi.replaceAll("/+$", "");
    }

    // ------------------------------------------------------------------ NGO administrators

    /** Kinds of papers a CSR team asks for, plus whether this NGO's plan includes packs. */
    @GetMapping(BASE + "/overview")
    public Map<String, Object> overview(@PathVariable UUID ngoId) {
        guard.requireAdmin(ngoId);
        Entitlements e = guard.entitlements(ngoId);
        List<Map<String, Object>> kinds = new ArrayList<>();
        for (CsrRules.Kind k : CsrRules.KINDS) kinds.add(Map.of("kind", k.code(), "label", k.label(), "required", k.required()));
        List<Map<String, Object>> packs = new ArrayList<>();
        for (CsrService.Pack p : csr.list(ngoId)) packs.add(summary(p));
        return Map.of("enabled", e.flag(Entitlements.CSR_COMPLIANCE_PACK), "kinds", kinds, "packs", packs);
    }

    @GetMapping(BASE + "/suggestions")
    public Map<String, List<Map<String, Object>>> suggestions(@PathVariable UUID ngoId) {
        guard.requireAdmin(ngoId);
        return csr.suggestions(ngoId);
    }

    @PostMapping(BASE + "/packs")
    public Map<String, Object> create(@PathVariable UUID ngoId, @RequestBody PackRequest req) {
        Membership m = admin(ngoId, true);
        return detail(csr.create(ngoId, m.userId(), req.input(), req.autoFill() == null || req.autoFill()));
    }

    @GetMapping(BASE + "/packs/{id}")
    public Map<String, Object> get(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.requireAdmin(ngoId);
        return detail(csr.pack(ngoId, id));
    }

    @PutMapping(BASE + "/packs/{id}")
    public Map<String, Object> update(@PathVariable UUID ngoId, @PathVariable UUID id, @RequestBody PackRequest req) {
        Membership m = admin(ngoId, true);
        return detail(csr.update(ngoId, m.userId(), id, req.input(), req.version() == null ? -1 : req.version()));
    }

    @PostMapping(BASE + "/packs/{id}/items")
    public Map<String, Object> addItem(@PathVariable UUID ngoId, @PathVariable UUID id, @RequestBody ItemRequest req) {
        Membership m = admin(ngoId, true);
        if (req.documentId() == null) throw ApiException.badRequest("DOCUMENT_REQUIRED", "Choose a document from the vault");
        return detail(csr.addItem(ngoId, m.userId(), id, req.documentId(), req.kind()));
    }

    @DeleteMapping(BASE + "/packs/{id}/items/{itemId}")
    public Map<String, Object> removeItem(@PathVariable UUID ngoId, @PathVariable UUID id, @PathVariable UUID itemId) {
        Membership m = admin(ngoId, true);
        return detail(csr.removeItem(ngoId, m.userId(), id, itemId));
    }

    @PostMapping(BASE + "/packs/{id}/share")
    public Map<String, Object> share(@PathVariable UUID ngoId, @PathVariable UUID id, @RequestBody ShareRequest req) {
        Membership m = admin(ngoId, true);
        return detail(csr.share(ngoId, m.userId(), id, req.version() == null ? -1 : req.version(), Boolean.TRUE.equals(req.acceptWarnings())));
    }

    /** Stopping a link is always allowed (also after a downgrade): the NGO must be able to withdraw its papers. */
    @PostMapping(BASE + "/packs/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable UUID ngoId, @PathVariable UUID id) {
        Membership m = guard.requireAdmin(ngoId);
        return detail(csr.revoke(ngoId, m.userId(), id));
    }

    @PostMapping(BASE + "/packs/{id}/copy")
    public Map<String, Object> copy(@PathVariable UUID ngoId, @PathVariable UUID id) {
        Membership m = admin(ngoId, true);
        return detail(csr.copy(ngoId, m.userId(), id));
    }

    @GetMapping(BASE + "/packs/{id}/views")
    public List<Map<String, Object>> views(@PathVariable UUID ngoId, @PathVariable UUID id) {
        guard.requireAdmin(ngoId);
        return csr.views(csr.pack(ngoId, id).id());
    }

    private Membership admin(UUID ngoId, boolean write) {
        Membership m = guard.requireAdmin(ngoId);
        if (write) guard.requireFlag(ngoId, Entitlements.CSR_COMPLIANCE_PACK, NEEDS);
        return m;
    }

    private Map<String, Object> summary(CsrService.Pack p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("title", p.title());
        m.put("recipientName", p.recipientName());
        m.put("status", p.effectiveStatus(Instant.now()));
        m.put("documents", csr.items(p.id()).size());
        m.put("sharedAt", p.sharedAt());
        m.put("expiresAt", p.expiresAt());
        m.put("createdAt", p.createdAt());
        if (!"DRAFT".equals(p.status())) m.putAll(csr.viewSummary(p.id()));
        return m;
    }

    private Map<String, Object> detail(CsrService.Pack p) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", p.id());
        m.put("title", p.title());
        m.put("recipientName", p.recipientName());
        m.put("recipientEmail", p.recipientEmail());
        m.put("message", p.message());
        m.put("validDays", p.validDays());
        m.put("status", p.effectiveStatus(Instant.now()));
        m.put("sharedAt", p.sharedAt());
        m.put("expiresAt", p.expiresAt());
        m.put("revokedAt", p.revokedAt());
        m.put("createdAt", p.createdAt());
        m.put("version", p.version());
        m.put("items", csr.itemViews(p));
        if ("DRAFT".equals(p.status())) {
            CsrRules.Readiness r = csr.readiness(p);
            m.put("readiness", Map.of("errors", r.errors(), "warnings", r.warnings(), "checklist", r.checklist(), "ready", r.ready()));
        } else {
            m.put("link", publicApi + "/api/v1/public/csr/" + p.code());
            m.putAll(csr.viewSummary(p.id()));
        }
        return m;
    }

    // ------------------------------------------------------------------ the company (public, no login)

    @GetMapping("/api/v1/public/csr/{code}")
    public ResponseEntity<?> page(@PathVariable String code, @RequestParam(required = false) String format,
                                  @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String agent) {
        boolean json = "json".equalsIgnoreCase(format);
        CsrService.Pack p;
        try {
            p = csr.byCode(code);
        } catch (ApiException e) {
            if (json) throw e;
            return ResponseEntity.status(e.status()).contentType(HTML).cacheControl(CacheControl.noStore()).header("Content-Security-Policy", PAGE_CSP)
                    .body(messagePage(e.status().value() == 410 ? "Link no longer available" : "Link not found", e.getMessage()));
        }
        csr.logView(p.id(), null, "OPENED", agent);
        String ngo = ngoNames.name(p.ngoId());
        List<Map<String, Object>> items = new ArrayList<>();
        for (CsrService.Item i : csr.items(p.id())) {
            if (i.frozenAt() == null) continue;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("id", i.id());
            m.put("kind", i.kind());
            m.put("kindLabel", CsrRules.kind(i.kind()).label());
            m.put("title", i.title());
            m.put("fileType", i.fileType());
            m.put("sizeBytes", i.sizeBytes());
            m.put("sha256", i.sha256());
            m.put("expiresOn", i.expiresOn());
            m.put("available", csr.available(i));
            m.put("url", publicApi + "/api/v1/public/csr/" + code + "/files/" + i.id());
            items.add(m);
        }
        if (json) {
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("organisation", ngo);
            out.put("title", p.title());
            out.put("recipientName", p.recipientName());
            out.put("message", p.message());
            out.put("sharedAt", p.sharedAt());
            out.put("expiresAt", p.expiresAt());
            out.put("items", items);
            out.put("zipUrl", publicApi + "/api/v1/public/csr/" + code + "/all.zip");
            return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(out);
        }
        return ResponseEntity.ok().contentType(HTML).cacheControl(CacheControl.noStore()).header("Content-Security-Policy", PAGE_CSP)
                .header("X-Robots-Tag", "noindex, nofollow").body(packPage(p, ngo, items, code));
    }

    @GetMapping("/api/v1/public/csr/{code}/files/{itemId}")
    public ResponseEntity<InputStreamResource> file(@PathVariable String code, @PathVariable UUID itemId,
                                                    @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String agent) {
        CsrService.Pack p = csr.byCode(code);
        CsrService.Item i = csr.frozenItem(p, itemId);
        InputStreamResource body = new InputStreamResource(csr.open(i));
        csr.logView(p.id(), i.id(), "DOWNLOADED", agent);
        String name = DocFiles.downloadName(i.title(), i.storageKey());
        String ascii = name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "");
        String encoded = java.net.URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded)
                .contentType(MediaType.parseMediaType(i.contentType() == null ? "application/octet-stream" : i.contentType()))
                .body(body);
    }

    @GetMapping("/api/v1/public/csr/{code}/all.zip")
    public ResponseEntity<StreamingResponseBody> zip(@PathVariable String code,
                                                     @RequestHeader(value = HttpHeaders.USER_AGENT, required = false) String agent) {
        CsrService.Pack p = csr.byCode(code);
        csr.logView(p.id(), null, "DOWNLOADED_ALL", agent);
        String name = (ngoNames.name(p.ngoId()) + " - " + p.title()).replaceAll("[^A-Za-z0-9 ._-]", "").strip();
        if (name.isEmpty()) name = "csr-documents";
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.parseMediaType("application/zip"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + name + ".zip\"")
                .body(out -> csr.writeZip(p, out));
    }

    // ------------------------------------------------------------------ pages

    static String esc(Object o) {
        if (o == null) return "";
        return o.toString().replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }

    static String size(Long b) {
        if (b == null) return "";
        if (b < 1024) return b + " B";
        if (b < 1024 * 1024) return Math.round(b / 102.4) / 10.0 + " KB";
        return Math.round(b / 104857.6) / 10.0 + " MB";
    }

    private static final String STYLE = "<style>body{font-family:system-ui,-apple-system,Segoe UI,Roboto,sans-serif;margin:0;background:#f6f7f9;color:#14213d}"
            + "main{max-width:760px;margin:0 auto;padding:24px 16px}header{background:#087a4b;color:#fff;border-radius:14px;padding:20px 22px}"
            + "h1{margin:0 0 4px;font-size:22px}header p{margin:0;opacity:.9}.card{background:#fff;border-radius:14px;padding:18px 20px;margin-top:16px;"
            + "box-shadow:0 1px 3px rgba(0,0,0,.06)}.note{white-space:pre-wrap}ul{list-style:none;padding:0;margin:0}li{display:flex;gap:12px;align-items:center;"
            + "padding:12px 0;border-bottom:1px solid #eceef2}li:last-child{border-bottom:0}.k{font-size:12px;color:#087a4b;font-weight:600;text-transform:uppercase;"
            + "letter-spacing:.03em}.t{font-weight:600}.m{font-size:12px;color:#5b6478}.grow{flex:1;min-width:0}a.btn{background:#087a4b;color:#fff;text-decoration:none;"
            + "padding:8px 14px;border-radius:8px;font-weight:600;white-space:nowrap}a.all{display:inline-block;margin-top:14px}.gone{color:#b42318;font-size:13px}"
            + "footer{font-size:12px;color:#5b6478;margin-top:18px;line-height:1.5}</style>";

    private String packPage(CsrService.Pack p, String ngo, List<Map<String, Object>> items, String code) {
        StringBuilder li = new StringBuilder();
        for (Map<String, Object> i : items) {
            boolean ok = Boolean.TRUE.equals(i.get("available"));
            String sha = String.valueOf(i.get("sha256"));
            li.append("<li><div class=\"grow\"><div class=\"k\">").append(esc(i.get("kindLabel"))).append("</div><div class=\"t\">")
                    .append(esc(i.get("title"))).append("</div><div class=\"m\">").append(esc(i.get("fileType"))).append(" · ")
                    .append(esc(size((Long) i.get("sizeBytes"))))
                    .append(i.get("expiresOn") == null ? "" : " · valid till " + esc(i.get("expiresOn")))
                    .append(" · <span title=\"SHA-256 ").append(esc(sha)).append("\">fingerprint ").append(esc(sha.length() > 12 ? sha.substring(0, 12) : sha))
                    .append("</span></div></div>")
                    .append(ok ? "<a class=\"btn\" href=\"" + esc(i.get("url")) + "\">Download</a>" : "<span class=\"gone\">Withdrawn by the organisation</span>")
                    .append("</li>");
        }
        String expires = p.expiresAt() == null ? "" : DAY.format(p.expiresAt().atZone(INDIA));
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex,nofollow\"><title>" + esc(ngo) + " - CSR documents - Yogdaan</title>" + STYLE + "</head><body><main>"
                + "<header><h1>" + esc(ngo) + "</h1><p>" + esc(p.title()) + " · shared with " + esc(p.recipientName()) + "</p></header>"
                + (p.message() == null ? "" : "<div class=\"card note\">" + esc(p.message()) + "</div>")
                + "<div class=\"card\"><ul>" + li + "</ul><a class=\"btn all\" href=\"" + esc(publicApi + "/api/v1/public/csr/" + code + "/all.zip")
                + "\">Download all (ZIP)</a></div>"
                + "<footer>This link is valid until " + esc(expires) + ". The organisation can see when documents are opened and can withdraw the link "
                + "at any time. Each file's SHA-256 fingerprint was recorded when it was shared, so you can check that a file is unchanged.<br>"
                + "Shared through Yogdaan (yogdaan.co.in) - donations and accounts app for NGOs.</footer></main></body></html>";
    }

    static String messagePage(String title, String text) {
        return "<!doctype html><html lang=\"en\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
                + "<meta name=\"robots\" content=\"noindex,nofollow\"><title>" + esc(title) + " - Yogdaan</title>" + STYLE + "</head><body><main>"
                + "<div class=\"card\"><h1>" + esc(title) + "</h1><p>" + esc(text) + "</p></div>"
                + "<footer>Yogdaan (yogdaan.co.in) - donations and accounts app for NGOs.</footer></main></body></html>";
    }
}
