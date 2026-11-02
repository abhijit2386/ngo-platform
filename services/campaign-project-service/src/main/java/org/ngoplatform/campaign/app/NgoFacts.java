package org.ngoplatform.campaign.app;

import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.config.InternalClients;
import org.ngoplatform.commons.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** NGO facts from ngo-registry-service, needed when a campaign goes live. */
@Component
public class NgoFacts {
    public record Facts(String displayName, String city, boolean donationEligible, String eligibility) {}

    private final RestClient registry;

    public NgoFacts(InternalClients clients, @Value("${ngo.services.ngo-registry}") String registryUrl) {
        this.registry = clients.forBaseUrl(registryUrl);
    }

    public Facts get(UUID ngoId) {
        Map<String, Object> m;
        try {
            m = registry.get().uri("/internal/v1/ngos/{id}", ngoId).retrieve().body(new ParameterizedTypeReference<>() {});
        } catch (RuntimeException e) {
            throw ApiException.rule("REGISTRY_UNAVAILABLE", "Could not check the organization right now. Try again shortly.");
        }
        if (m == null) throw ApiException.notFound("Organization");
        return new Facts(str(m.get("displayName")), str(m.get("city")), Boolean.TRUE.equals(m.get("donationEligible")),
                str(m.get("donationEligibility")));
    }

    /** Legal name, address, PAN, registration and 80G details (printed on utilisation certificates). */
    public Map<String, Object> details(UUID ngoId) {
        try {
            Map<String, Object> m = registry.get().uri("/internal/v1/ngos/{id}", ngoId).retrieve().body(new ParameterizedTypeReference<>() {});
            if (m == null) throw ApiException.notFound("Organization");
            return m;
        } catch (ApiException e) {
            throw e;
        } catch (RuntimeException e) {
            throw ApiException.rule("REGISTRY_UNAVAILABLE", "Could not read the organization's details right now. Try again shortly.");
        }
    }

    private static String str(Object o) { return o == null ? null : o.toString(); }
}
