package org.ngoplatform.registry.api;

import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.web.PageResponse;
import org.ngoplatform.registry.app.Reference;
import org.ngoplatform.registry.app.RegistryService;
import org.ngoplatform.registry.domain.Ngo;
import org.ngoplatform.registry.domain.Repos;
import org.springframework.core.io.InputStreamResource;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;

/** DAPP-002..006: donor discovery. Only published, active NGOs; no private verification data. */
@RestController
@RequestMapping("/api/v1/public")
public class PublicController {
    private final Repos.Ngos ngos;
    private final Repos.Addresses addresses;
    private final RegistryService svc;
    private final JdbcTemplate jdbc;

    public PublicController(Repos.Ngos ngos, Repos.Addresses addresses, RegistryService svc, JdbcTemplate jdbc) {
        this.ngos = ngos; this.addresses = addresses; this.svc = svc; this.jdbc = jdbc;
    }

    @GetMapping("/ngos")
    public PageResponse<Dtos.PublicNgo> search(@RequestParam(required = false) String q,
                                               @RequestParam(required = false) String city,
                                               @RequestParam(required = false) String cause,
                                               @RequestParam(defaultValue = "0") int page,
                                               @RequestParam(defaultValue = "20") int size) {
        if (q != null && (q.isBlank() || q.length() > 80)) q = q.isBlank() ? null : q.substring(0, 80);
        if (city != null && city.isBlank()) city = null;
        if (cause != null && !Reference.CAUSES.contains(cause)) throw ApiException.badRequest("INVALID_CAUSE", "Unknown cause");
        var result = ngos.searchPublic(q, city, cause, PageRequest.of(Math.max(page, 0), Math.min(Math.max(size, 1), 50)));
        return PageResponse.of(result, n -> Dtos.PublicNgo.of(n, addresses.findById(n.getId()).orElse(null)));
    }

    @GetMapping("/ngos/{id}")
    public Dtos.PublicNgo get(@PathVariable UUID id) {
        Ngo n = ngos.findById(id).filter(x -> x.getPublicProfileStatus() == Ngo.PublicProfileStatus.PUBLISHED
                && x.getAccountStatus() == Ngo.AccountStatus.ACTIVE).orElseThrow(() -> ApiException.notFound("Organization"));
        return Dtos.PublicNgo.of(n, addresses.findById(id).orElse(null));
    }

    @GetMapping("/ngos/{id}/logo")
    public ResponseEntity<InputStreamResource> logo(@PathVariable UUID id) {
        Ngo n = ngos.findById(id).orElseThrow(() -> ApiException.notFound("Organization"));
        if (n.getPublicProfileStatus() != Ngo.PublicProfileStatus.PUBLISHED) throw ApiException.notFound("Logo");
        return ResponseEntity.ok().cacheControl(CacheControl.maxAge(java.time.Duration.ofHours(1)))
                .contentType(n.getLogoKey() != null && n.getLogoKey().endsWith(".png") ? MediaType.IMAGE_PNG : MediaType.IMAGE_JPEG)
                .body(new InputStreamResource(svc.logo(id)));
    }

    @GetMapping("/reference")
    public Map<String, Object> reference() {
        return Map.of("states", Reference.STATES, "causes", Reference.CAUSES.stream().sorted().toList(),
                "documentTypes", Reference.DOC_TYPES.stream().sorted().toList(),
                "requiredDocuments", Reference.REQUIRED_DOCS.stream().sorted().toList());
    }

    /** APP-010/011: maintenance mode and minimum supported app build. */
    @GetMapping("/app-config")
    public Map<String, String> appConfig() {
        Map<String, String> out = new java.util.HashMap<>();
        jdbc.query("select key, value from app_config", rs -> { out.put(rs.getString(1), rs.getString(2)); });
        return out;
    }
}
