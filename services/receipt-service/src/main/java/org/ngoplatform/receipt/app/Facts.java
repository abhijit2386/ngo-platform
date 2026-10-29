package org.ngoplatform.receipt.app;

import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.config.InternalClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Pulls the data a receipt needs from owning services at generation time (no personal data in events). */
@Component
public class Facts {
    private final RestClient registry;
    private final RestClient donation;
    private final RestClient donor;

    public Facts(InternalClients c, @Value("${ngo.services.ngo-registry}") String registry,
                 @Value("${ngo.services.donation}") String donation, @Value("${ngo.services.donor}") String donor) {
        this.registry = c.forBaseUrl(registry);
        this.donation = c.forBaseUrl(donation);
        this.donor = c.forBaseUrl(donor);
    }

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};

    public Map<String, Object> ngo(UUID ngoId) { return registry.get().uri("/internal/v1/ngos/{id}", ngoId).retrieve().body(MAP); }
    public Map<String, Object> donation(UUID id) { return donation.get().uri("/internal/v1/donations/{id}", id).retrieve().body(MAP); }
    /** Public logo (null if unavailable; receipts are still generated without it). */
    public byte[] logo(UUID ngoId) {
        try { return registry.get().uri("/api/v1/public/ngos/{id}/logo", ngoId).retrieve().body(byte[].class); }
        catch (Exception e) { return null; }
    }

    public Map<String, Object> donor(UUID ngoId, UUID donorId) {
        return donor.get().uri("/internal/v1/ngos/{n}/donors/{d}/receipt-details", ngoId, donorId).retrieve().body(MAP);
    }
}
