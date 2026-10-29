package org.ngoplatform.donor.api;

import jakarta.validation.Valid;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.config.InternalClients;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.security.CurrentUser;
import org.ngoplatform.donor.domain.Donor;
import org.ngoplatform.donor.domain.Records;
import org.ngoplatform.donor.domain.Repos;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** DAPP-022..030: donor's own profile, follows, saved campaigns, preferences, privacy requests. */
@RestController
@RequestMapping("/api/v1/donor")
public class DonorSelfController {
    private final AccessGuard guard;
    private final Repos.Profiles profiles;
    private final Repos.Preferences prefs;
    private final Repos.Consents consents;
    private final Repos.Follows follows;
    private final Repos.Saved saved;
    private final Repos.Privacy privacy;
    private final Repos.Donors donors;
    private final RestClient registry;

    public DonorSelfController(AccessGuard guard, Repos.Profiles profiles, Repos.Preferences prefs, Repos.Consents consents,
                               Repos.Follows follows, Repos.Saved saved, Repos.Privacy privacy, Repos.Donors donors,
                               InternalClients clients, @Value("${ngo.services.ngo-registry}") String registryUrl) {
        this.guard = guard; this.profiles = profiles; this.prefs = prefs; this.consents = consents; this.follows = follows;
        this.saved = saved; this.privacy = privacy; this.donors = donors; this.registry = clients.forBaseUrl(registryUrl);
    }

    @GetMapping("/profile")
    @Transactional
    public Map<String, Object> profile() {
        CurrentUser u = guard.user();
        Records.Profile p = profiles.findById(u.userId()).orElseGet(() -> profiles.save(new Records.Profile(u.userId())));
        Records.Preferences pr = prefs.findById(u.userId()).orElseGet(() -> prefs.save(new Records.Preferences(u.userId())));
        return Map.of("displayName", p.getDisplayName() == null ? (u.name() == null ? "" : u.name()) : p.getDisplayName(),
                "email", u.email() == null ? "" : u.email(), "phone", p.getPhone() == null ? "" : p.getPhone(),
                "pan", p.getPan() == null ? "" : p.getPan(), "city", p.getCity() == null ? "" : p.getCity(),
                "preferences", Map.of("emailReceipts", pr.isEmailReceipts(), "campaignUpdates", pr.isCampaignUpdates(),
                        "marketing", pr.isMarketing(), "pushEnabled", pr.isPushEnabled()));
    }

    @PutMapping("/profile")
    @Transactional
    public Map<String, Object> updateProfile(@Valid @RequestBody Dtos.ProfileRequest req) {
        CurrentUser u = guard.user();
        Records.Profile p = profiles.findById(u.userId()).orElseGet(() -> new Records.Profile(u.userId()));
        p.update(req.displayName(), blank(req.phone()), req.pan() == null || req.pan().isBlank() ? null : req.pan().toUpperCase(), blank(req.city()));
        profiles.save(p);
        return profile();
    }

    @PutMapping("/preferences")
    @Transactional
    public Map<String, Object> preferences(@RequestBody Dtos.PreferencesRequest req) {
        CurrentUser u = guard.user();
        Records.Preferences p = prefs.findById(u.userId()).orElseGet(() -> new Records.Preferences(u.userId()));
        p.update(req.emailReceipts(), req.campaignUpdates(), req.marketing(), req.pushEnabled());
        prefs.save(p);
        consents.save(new Records.Consent(u.userId(), "MARKETING", req.marketing(), "2026-10"));
        // Opting out of updates propagates to every NGO record linked to this account.
        if (!req.campaignUpdates()) donors.findByDonorUserIdAndStatus(u.userId(), Donor.Status.ACTIVE).forEach(d -> d.setOptIn(false));
        return profile();
    }

    @GetMapping("/following")
    public List<Map<String, Object>> following() {
        CurrentUser u = guard.user();
        return follows.findByUserIdOrderByCreatedAtDesc(u.userId()).stream().map(f -> publicNgo(f.getNgoId()))
                .filter(m -> !m.isEmpty()).toList();
    }

    private Map<String, Object> publicNgo(UUID ngoId) {
        try {
            Map<String, Object> m = registry.get().uri("/api/v1/public/ngos/{id}", ngoId).retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            return m == null ? Map.of() : m;
        } catch (RestClientException e) {
            return Map.of(); // NGO no longer public
        }
    }

    @PutMapping("/following/{ngoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void follow(@PathVariable UUID ngoId) {
        CurrentUser u = guard.user();
        if (publicNgo(ngoId).isEmpty()) throw ApiException.notFound("Organization");
        if (!follows.existsById(new Records.FollowId(u.userId(), ngoId))) follows.save(new Records.Follow(u.userId(), ngoId));
    }

    @DeleteMapping("/following/{ngoId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void unfollow(@PathVariable UUID ngoId) {
        follows.deleteById(new Records.FollowId(guard.user().userId(), ngoId));
    }

    @GetMapping("/saved-campaigns")
    public List<Map<String, Object>> savedCampaigns() {
        return saved.findByUserId(guard.user().userId()).stream()
                .map(s -> Map.<String, Object>of("campaignId", s.getCampaignId(), "ngoId", s.getNgoId())).toList();
    }

    @PutMapping("/saved-campaigns/{ngoId}/{campaignId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void save(@PathVariable UUID ngoId, @PathVariable UUID campaignId) {
        UUID user = guard.user().userId();
        if (!saved.existsById(new Records.SavedId(user, campaignId))) saved.save(new Records.SavedCampaign(user, campaignId, ngoId));
    }

    @DeleteMapping("/saved-campaigns/{campaignId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void unsave(@PathVariable UUID campaignId) {
        saved.deleteById(new Records.SavedId(guard.user().userId(), campaignId));
    }

    @PostMapping("/privacy-requests")
    @ResponseStatus(HttpStatus.CREATED)
    @Transactional
    public Dtos.PrivacyView privacyRequest(@Valid @RequestBody Dtos.PrivacyRequestDto req) {
        CurrentUser u = guard.user();
        UUID donorId = null;
        if (req.ngoId() != null) {
            donorId = donors.findActiveByUser(req.ngoId(), u.userId()).map(Donor::getId).orElse(null);
        }
        return Dtos.PrivacyView.of(privacy.save(new Records.PrivacyRequest(u.userId(), req.ngoId(), donorId, req.type(), req.details())));
    }

    @GetMapping("/privacy-requests")
    public List<Dtos.PrivacyView> myPrivacyRequests() {
        return privacy.findByUserIdOrderByCreatedAtDesc(guard.user().userId()).stream().map(Dtos.PrivacyView::of).toList();
    }

    private static String blank(String s) { return s == null || s.isBlank() ? null : s.trim(); }
}
