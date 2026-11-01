package org.ngoplatform.receipt.app;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.config.InternalClients;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Pulls the data a receipt needs from the owning services at generation time (no personal data in events).
 * The donor's name, PAN and address come from the donation's own snapshot: donation-service copies the name from the
 * donor's profile at checkout (owner decision 2026-11-01), staff type it for offline donations.
 */
@Component
public class Facts {
    private final RestClient registry;
    private final RestClient donation;
    private final RestClient notification;

    public Facts(InternalClients c, @Value("${ngo.services.ngo-registry}") String registry,
                 @Value("${ngo.services.donation}") String donation, @Value("${ngo.services.notification}") String notification) {
        this.registry = c.forBaseUrl(registry);
        this.donation = c.forBaseUrl(donation);
        this.notification = c.forBaseUrl(notification);
    }

    private static final ParameterizedTypeReference<Map<String, Object>> MAP = new ParameterizedTypeReference<>() {};
    private static final ParameterizedTypeReference<List<Map<String, Object>>> LIST = new ParameterizedTypeReference<>() {};

    public Map<String, Object> ngo(UUID ngoId) { return registry.get().uri("/internal/v1/ngos/{id}", ngoId).retrieve().body(MAP); }

    public Map<String, Object> donation(UUID id) { return donation.get().uri("/internal/v1/donations/{id}", id).retrieve().body(MAP); }

    /** Paid donations (oldest first) for the one-time backfill. */
    public List<Map<String, Object>> paidDonations(int page) {
        return donation.get().uri("/internal/v1/donations/paid?page={p}", page).retrieve().body(LIST);
    }

    /** Public logo (null if unavailable; receipts are still generated without it). */
    public byte[] logo(UUID ngoId) {
        try { return registry.get().uri("/api/v1/public/ngos/{id}/logo", ngoId).retrieve().body(byte[].class); }
        catch (Exception e) { return null; }
    }

    /** E-mail + in-app message through notification-service (idempotent per requestId). */
    public void notify(Map<String, Object> request) {
        notification.post().uri("/internal/v1/notifications").body(request).retrieve().toBodilessEntity();
    }
}
