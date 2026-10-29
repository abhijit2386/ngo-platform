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

    public Clients(InternalClients clients, @Value("${ngo.services.ngo-registry}") String registry,
                   @Value("${ngo.services.donor}") String donor) {
        this.registry = clients.forBaseUrl(registry);
        this.donor = clients.forBaseUrl(donor);
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

    public UUID resolveDonor(UUID ngoId, UUID userId, String name, String email, String phone, String pan, boolean guest) {
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
