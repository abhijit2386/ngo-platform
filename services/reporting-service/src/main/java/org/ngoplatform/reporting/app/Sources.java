package org.ngoplatform.reporting.app;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;
import org.ngoplatform.commons.config.InternalClients;
import org.ngoplatform.commons.error.ApiException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Reads report figures from the services that own the data (no copies kept here, so reports are always current).
 * A source that is down gives 503 SOURCE_UNAVAILABLE naming it, instead of a report with silently missing parts.
 */
@Component
public class Sources {
    private final RestClient donation, receipt, campaign, identity, registry;

    public Sources(InternalClients clients, @Value("${ngo.services.donation}") String donation,
                   @Value("${ngo.services.receipt}") String receipt, @Value("${ngo.services.campaign}") String campaign,
                   @Value("${ngo.services.identity}") String identity, @Value("${ngo.services.ngo-registry}") String registry) {
        this.donation = clients.forBaseUrl(donation);
        this.receipt = clients.forBaseUrl(receipt);
        this.campaign = clients.forBaseUrl(campaign);
        this.identity = clients.forBaseUrl(identity);
        this.registry = clients.forBaseUrl(registry);
    }

    public Data.DonationSummary donations(UUID ngoId, Period p, UUID recordedBy) {
        return call("donations", () -> donation.get()
                .uri(u -> {
                    u.path("/internal/v1/ngos/{id}/reports/donations").queryParam("from", p.start()).queryParam("to", p.end());
                    if (recordedBy != null) u.queryParam("recordedBy", recordedBy);
                    return u.build(ngoId);
                })
                .retrieve().body(Data.DonationSummary.class));
    }

    public List<Data.TaxRow> taxRows(UUID ngoId, Period p) {
        return call("donations", () -> donation.get()
                .uri(u -> u.path("/internal/v1/ngos/{id}/reports/80g").queryParam("from", p.start()).queryParam("to", p.end()).build(ngoId))
                .retrieve().body(new ParameterizedTypeReference<List<Data.TaxRow>>() {}));
    }

    public Map<String, String> receiptNumbers(UUID ngoId, List<String> donationIds) {
        if (donationIds.isEmpty()) return Map.of();
        return call("receipts", () -> receipt.post().uri("/internal/v1/ngos/{id}/reports/receipt-numbers", ngoId)
                .body(donationIds).retrieve().body(new ParameterizedTypeReference<Map<String, String>>() {}));
    }

    public Data.Operations operations(UUID ngoId) {
        return call("campaigns", () -> campaign.get().uri("/internal/v1/ngos/{id}/reports/operations", ngoId)
                .retrieve().body(Data.Operations.class));
    }

    public Data.Mine member(UUID ngoId, UUID userId) {
        return call("campaigns", () -> campaign.get().uri("/internal/v1/ngos/{id}/reports/members/{u}", ngoId, userId)
                .retrieve().body(Data.Mine.class));
    }

    /** The NGO's display name for report headers (legal name, else "Your NGO" when the registry is down). */
    public String ngoName(UUID ngoId) {
        try {
            Map<String, Object> m = registry.get().uri("/internal/v1/ngos/{id}", ngoId).retrieve()
                    .body(new ParameterizedTypeReference<Map<String, Object>>() {});
            Object n = m == null ? null : (m.get("displayName") != null ? m.get("displayName") : m.get("legalName"));
            return n == null ? "Your NGO" : n.toString();
        } catch (RestClientException e) {
            return "Your NGO";
        }
    }

    /** Display names (else e-mail, else "Member 1a2b3c4d") for staff in the cash report. */
    public Map<String, String> names(Collection<String> userIds) {
        Map<String, String> out = new HashMap<>();
        for (String id : userIds.stream().filter(Objects::nonNull).distinct().toList()) {
            String n = null;
            try {
                Map<String, String> c = identity.get().uri("/internal/v1/users/{id}/contact", id).retrieve()
                        .body(new ParameterizedTypeReference<Map<String, String>>() {});
                if (c != null) n = c.get("name") != null && !c.get("name").isBlank() ? c.get("name") : c.get("email");
            } catch (RestClientException e) {
                n = null;
            }
            out.put(id, n == null ? "Member " + id.substring(0, Math.min(8, id.length())) : n);
        }
        return out;
    }

    private static <T> T call(String what, Supplier<T> s) {
        try {
            return s.get();
        } catch (RestClientException e) {
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "SOURCE_UNAVAILABLE",
                    "The " + what + " figures are not available right now. Try again in a minute.");
        }
    }
}
