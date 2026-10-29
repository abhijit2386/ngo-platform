package org.ngoplatform.subscription.app;

import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.config.InternalClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** Reads usage owned by other services (staff, campaigns, templates) for downgrade impact. Null = unknown. */
@Component
public class UsageClient {
    private final RestClient identity;
    private final RestClient campaign;
    private final RestClient receipt;

    public UsageClient(InternalClients clients, @Value("${ngo.services.identity}") String identity,
                       @Value("${ngo.services.campaign}") String campaign, @Value("${ngo.services.receipt}") String receipt) {
        this.identity = clients.forBaseUrl(identity);
        this.campaign = clients.forBaseUrl(campaign);
        this.receipt = clients.forBaseUrl(receipt);
    }

    public Long activeStaff(UUID ngoId) {
        try {
            Map<String, Long> m = identity.get().uri("/internal/v1/tenants/{id}/usage", ngoId).retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Long>>() {});
            return m == null ? null : m.get("activeStaff");
        } catch (RestClientException e) {
            return null;
        }
    }

    public Map<String, Long> operationsUsage(UUID ngoId) {
        try {
            return campaign.get().uri("/internal/v1/ngos/{id}/usage", ngoId).retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Long>>() {});
        } catch (RestClientException e) {
            return null;
        }
    }

    public Long customTemplates(UUID ngoId) {
        try {
            Map<String, Long> m = receipt.get().uri("/internal/v1/ngos/{id}/template-usage", ngoId).retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Long>>() {});
            return m == null ? null : m.get("customTemplates");
        } catch (RestClientException e) {
            return null;
        }
    }
}
