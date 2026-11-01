package org.ngoplatform.document.app;

import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.config.InternalClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** NGO display name from ngo-registry-service (printed on the minutes). */
@Component
public class NgoNames {
    private final RestClient registry;

    public NgoNames(InternalClients clients, @Value("${ngo.services.ngo-registry}") String url) { this.registry = clients.forBaseUrl(url); }

    public String name(UUID ngoId) {
        try {
            Map<String, Object> m = registry.get().uri("/internal/v1/ngos/{id}", ngoId).retrieve().body(new ParameterizedTypeReference<>() {});
            Object n = m == null ? null : m.get("displayName");
            return n == null ? "" : n.toString();
        } catch (RuntimeException e) {
            return "";
        }
    }
}
