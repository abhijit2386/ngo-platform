package org.ngoplatform.document.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.security.Membership;
import org.ngoplatform.commons.security.MembershipResolver;
import org.ngoplatform.commons.security.Permissions;
import org.ngoplatform.document.app.DocFiles;
import org.ngoplatform.document.app.DocumentRules;
import org.ngoplatform.document.app.DocumentService;
import org.ngoplatform.document.app.LinkSigner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/** Document vault API (DOCUMENT_VIEW to browse, DOCUMENT_UPLOAD to add; administrators see everything). */
@RestController
public class DocumentController {
    public record MetaRequest(String title, String description, String category, List<String> tags, String visibility,
                              LocalDate documentDate, LocalDate expiresOn, String linkType, UUID linkId, UUID linkParentId,
                              String linkLabel, Long version) {
        DocumentService.UploadInput input() {
            return new DocumentService.UploadInput(title, description, category, tags, visibility, documentDate, expiresOn, linkType,
                    linkId, linkParentId, linkLabel);
        }
    }

    public record DocView(UUID id, String title, String description, String category, List<String> tags, String fileType,
                          String contentType, long sizeBytes, String originalName, String source, String visibility, String linkType,
                          UUID linkId, UUID linkParentId, String linkLabel, LocalDate documentDate, LocalDate expiresOn, UUID uploadedBy,
                          UUID updatedBy, String status, UUID deletedBy, Instant deletedAt, LocalDate purgeAfter, Instant createdAt,
                          Instant updatedAt, long version, boolean canManage) {
        static DocView of(DocumentService.Doc d, Membership m) {
            boolean manage = "UPLOAD".equals(d.source()) && (m.isAdmin() || m.userId().equals(d.uploadedBy()));
            return new DocView(d.id(), d.title(), d.description(), d.category(), d.tags(), d.fileType(), d.contentType(), d.sizeBytes(),
                    d.originalName(), d.source(), d.visibility(), d.linkType(), d.linkId(), d.linkParentId(), d.linkLabel(),
                    d.documentDate(), d.expiresOn(), d.uploadedBy(), d.updatedBy(), d.status(), d.deletedBy(), d.deletedAt(),
                    d.purgeAfter(), d.createdAt(), d.updatedAt(), d.version(), manage);
        }
    }

    private final DocumentService svc;
    private final AccessGuard guard;
    private final MembershipResolver memberships;
    private final LinkSigner links;
    private final ObjectMapper json;
    private final String publicApi;

    public DocumentController(DocumentService svc, AccessGuard guard, MembershipResolver memberships, LinkSigner links, ObjectMapper json,
                              @Value("${ngo.documents.public-api-url:http://localhost:8080}") String publicApi) {
        this.svc = svc; this.guard = guard; this.memberships = memberships; this.links = links; this.json = json;
        this.publicApi = publicApi.replaceAll("/+$", "");
    }

    private static final String BASE = "/api/v1/ngos/{ngoId}/library";

    @GetMapping(BASE)
    public Map<String, Object> search(@PathVariable UUID ngoId, @RequestParam(required = false) String q,
                                      @RequestParam(required = false) String category, @RequestParam(required = false) String type,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                                      @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                                      @RequestParam(required = false) UUID uploadedBy, @RequestParam(required = false) String tag,
                                      @RequestParam(required = false) String linkType, @RequestParam(required = false) UUID linkId,
                                      @RequestParam(defaultValue = "false") boolean mine, @RequestParam(required = false) String sort,
                                      @RequestParam(defaultValue = "0") int page, @RequestParam(defaultValue = "30") int size) {
        Membership m = guard.require(ngoId, Permissions.DOCUMENT_VIEW);
        if (from != null && to != null && to.isBefore(from)) throw ApiException.badRequest("INVALID_DATES", "The end date is before the start date");
        var p = svc.search(ngoId, m, new DocumentService.Filter(q, blank(category), blank(type), from, to, uploadedBy, tag, blank(linkType), linkId,
                mine, sort), Math.max(0, page), Math.min(Math.max(1, size), 100));
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("items", p.items().stream().map(d -> DocView.of(d, m)).toList());
        out.put("total", p.total());
        out.put("page", p.page());
        out.put("size", p.size());
        return out;
    }

    /** Category counts, tags in use and storage used (the vault's home screen). */
    @GetMapping(BASE + "/overview")
    public Map<String, Object> overview(@PathVariable UUID ngoId) {
        Membership m = guard.require(ngoId, Permissions.DOCUMENT_VIEW);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("categories", svc.categoryCounts(ngoId, m));
        out.put("tags", svc.tags(ngoId, m));
        var u = svc.usage(ngoId);
        out.put("usedBytes", u.usedBytes());
        out.put("files", u.files());
        out.put("limitBytes", u.limitBytes());
        out.put("canUpload", m.can(Permissions.DOCUMENT_UPLOAD));
        return out;
    }

    /** Upload: part "file" + part "meta" (JSON: title, category, tags, visibility, dates, optional link). */
    @PostMapping(value = BASE, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public DocView upload(@PathVariable UUID ngoId, @RequestPart("file") MultipartFile file, @RequestPart("meta") String meta) throws IOException {
        Membership m = guard.require(ngoId, Permissions.DOCUMENT_UPLOAD);
        return DocView.of(svc.upload(ngoId, m, parse(meta).input(), file.getBytes(), file.getOriginalFilename()), m);
    }

    @GetMapping(BASE + "/{id}")
    public DocView get(@PathVariable UUID ngoId, @PathVariable UUID id) {
        Membership m = guard.require(ngoId, Permissions.DOCUMENT_VIEW);
        return DocView.of(svc.get(ngoId, m, id), m);
    }

    @PutMapping(BASE + "/{id}")
    public DocView update(@PathVariable UUID ngoId, @PathVariable UUID id, @RequestBody MetaRequest req) {
        Membership m = guard.require(ngoId, Permissions.DOCUMENT_UPLOAD);
        if (req.version() == null) throw ApiException.badRequest("VERSION_REQUIRED", "version is required");
        return DocView.of(svc.update(ngoId, m, id, req.input(), req.version()), m);
    }

    @DeleteMapping(BASE + "/{id}")
    public DocView delete(@PathVariable UUID ngoId, @PathVariable UUID id) {
        Membership m = guard.require(ngoId, Permissions.DOCUMENT_UPLOAD);
        return DocView.of(svc.delete(ngoId, m, id), m);
    }

    @PostMapping(BASE + "/{id}/restore")
    public DocView restore(@PathVariable UUID ngoId, @PathVariable UUID id) {
        Membership m = guard.require(ngoId, Permissions.DOCUMENT_UPLOAD);
        return DocView.of(svc.restore(ngoId, m, id), m);
    }

    @GetMapping(BASE + "/bin")
    public List<DocView> bin(@PathVariable UUID ngoId) {
        Membership m = guard.require(ngoId, Permissions.DOCUMENT_VIEW);
        return svc.bin(ngoId, m).stream().map(d -> DocView.of(d, m)).toList();
    }

    /** The file itself, for the app (member's token). Recorded as VIEWED. */
    @GetMapping(BASE + "/{id}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable UUID ngoId, @PathVariable UUID id) {
        Membership m = guard.require(ngoId, Permissions.DOCUMENT_VIEW);
        DocumentService.Doc d = svc.get(ngoId, m, id);
        if (!"ACTIVE".equals(d.status()) && !m.isAdmin()) throw ApiException.notFound("Document");
        svc.record(ngoId, id, m.userId(), "VIEWED");
        return file(d, false);
    }

    /** A 5-minute link (open in a browser / share with someone who should get the file now). */
    @PostMapping(BASE + "/{id}/link")
    public Map<String, Object> link(@PathVariable UUID ngoId, @PathVariable UUID id) {
        Membership m = guard.require(ngoId, Permissions.DOCUMENT_VIEW);
        DocumentService.Doc d = svc.get(ngoId, m, id);
        if (!"ACTIVE".equals(d.status())) throw ApiException.notFound("Document");
        String token = links.sign(id, m.userId());
        svc.record(ngoId, id, m.userId(), "LINK_CREATED");
        return Map.of("url", publicApi + "/api/v1/public/files/" + token, "expiresAt", links.expiry(token));
    }

    @GetMapping(BASE + "/{id}/activity")
    public List<Map<String, Object>> activity(@PathVariable UUID ngoId, @PathVariable UUID id) {
        Membership m = guard.requireAdmin(ngoId);
        svc.get(ngoId, m, id);
        return svc.activity(ngoId, id);
    }

    /** Signed short-lived link: access is re-checked against the member's current role when it is used. */
    @GetMapping("/api/v1/public/files/{token}")
    public ResponseEntity<InputStreamResource> download(@PathVariable String token) {
        LinkSigner.Grant g = links.verify(token);
        DocumentService.Doc d = svc.byId(g.documentId());
        Membership m = memberships.find(g.userId(), d.ngoId()).filter(Membership::isActive)
                .filter(x -> x.can(Permissions.DOCUMENT_VIEW))
                .orElseThrow(() -> ApiException.forbidden("LINK_INVALID", "This download link is not valid any more"));
        if (!"ACTIVE".equals(d.status()) || !DocumentRules.canSee(m, new DocumentRules.Doc(d.uploadedBy(), d.visibility(), d.requiredPermission(), d.source()))) {
            throw ApiException.forbidden("LINK_INVALID", "This download link is not valid any more");
        }
        svc.record(d.ngoId(), d.id(), g.userId(), "DOWNLOADED");
        return file(d, true);
    }

    private ResponseEntity<InputStreamResource> file(DocumentService.Doc d, boolean attachment) {
        InputStream in = svc.open(d);
        String name = DocFiles.downloadName(d.title(), d.storageKey());
        // Plain ASCII filename for every client, plus RFC 5987 filename* for Hindi/Marathi titles.
        String ascii = name.replaceAll("[^\\x20-\\x7E]", "_").replace("\"", "");
        String encoded = java.net.URLEncoder.encode(name, StandardCharsets.UTF_8).replace("+", "%20");
        String cd = (attachment ? "attachment" : "inline") + "; filename=\"" + ascii + "\"; filename*=UTF-8''" + encoded;
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).header("X-Content-Type-Options", "nosniff")
                .header(HttpHeaders.CONTENT_DISPOSITION, cd)
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .contentType(MediaType.parseMediaType(d.contentType()))
                .body(new InputStreamResource(in));
    }

    private MetaRequest parse(String meta) {
        try {
            return json.readValue(meta, MetaRequest.class);
        } catch (IOException e) {
            throw ApiException.badRequest("INVALID_META", "The document details could not be read");
        }
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s; }
}
