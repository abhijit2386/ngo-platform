package org.ngoplatform.donation.app;

import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.config.InternalClients;
import org.ngoplatform.commons.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Synchronous calls needed at checkout time (eligibility, donor resolution). */
@Component
public class Clients {
    private final RestClient registry;
    private final RestClient donor;
    private final RestClient campaign;
    private final RestClient identity;
    private final boolean donorResolveEnabled;

    public Clients(InternalClients clients, @Value("${ngo.services.ngo-registry}") String registry,
                   @Value("${ngo.services.donor}") String donor, @Value("${ngo.services.campaign}") String campaign,
                   @Value("${ngo.services.identity}") String identity,
                   @Value("${ngo.donors.resolve-enabled:false}") boolean donorResolveEnabled) {
        this.registry = clients.forBaseUrl(registry);
        this.donor = clients.forBaseUrl(donor);
        this.campaign = clients.forBaseUrl(campaign);
        this.identity = clients.forBaseUrl(identity);
        this.donorResolveEnabled = donorResolveEnabled;
    }

    /**
     * The signed-in donor's name exactly as in their profile (Google / Facebook / e-mail sign-up, edited by the donor):
     * receipts must match the account (owner decision 2026-11-01). Null when the profile has no name yet.
     */
    public String profileName(UUID userId) {
        try {
            Map<String, Object> c = identity.get().uri("/internal/v1/users/{id}/contact", userId).retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            Object n = c == null ? null : c.get("name");
            return n == null || n.toString().isBlank() ? null : n.toString().strip();
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "IDENTITY_UNAVAILABLE", "Please retry in a moment");
        }
    }

    /** Campaign facts at checkout: ngoId, title, status, startDate, endDate. */
    public Map<String, Object> campaign(UUID campaignId) {
        try {
            return campaign.get().uri("/internal/v1/campaigns/{id}", campaignId).retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (HttpClientErrorException.NotFound e) {
            throw ApiException.notFound("Campaign");
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "CAMPAIGN_UNAVAILABLE", "Please retry in a moment");
        }
    }

    public Map<String, Object> ngo(UUID ngoId) {
        try {
            return registry.get().uri("/internal/v1/ngos/{id}", ngoId).retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        } catch (HttpClientErrorException.NotFound e) {
            throw ApiException.notFound("Organization");
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "REGISTRY_UNAVAILABLE", "Please retry in a moment");
        }
    }

    /** NGO-scoped donor record from donor-service; null while that service is not deployed (resolve-enabled=false). */
    public UUID resolveDonor(UUID ngoId, UUID userId, String name, String email, String phone, String pan, boolean guest) {
        if (!donorResolveEnabled) return null;
        try {
            Map<String, Object> body = new java.util.HashMap<>();
            if (userId != null) body.put("userId", userId);
            body.put("name", name);
            if (email != null) body.put("email", email);
            if (phone != null) body.put("phone", phone);
            if (pan != null) body.put("pan", pan);
            body.put("guest", guest);
            Map<String, Object> r = donor.post().uri("/internal/v1/ngos/{id}/donors/resolve", ngoId).body(body).retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            return UUID.fromString(String.valueOf(r.get("donorId")));
        } catch (HttpClientErrorException.BadRequest e) {
            throw ApiException.badRequest("INVALID_DONOR_DETAILS", "Please check the donor details");
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "DONOR_SERVICE_UNAVAILABLE", "Please retry in a moment");
        }
    }
}
