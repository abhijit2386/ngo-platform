package org.ngoplatform.donation.api;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.donation.domain.Donation;
import org.ngoplatform.donation.domain.Repos;
import org.springframework.web.bind.annotation.*;

/** Donation snapshot for receipt generation and notifications (keeps personal data out of events). */
@RestController
@RequestMapping("/internal/v1/donations")
public class InternalController {
    private final Repos.Donations donations;
    private final Repos.Payments payments;

    public InternalController(Repos.Donations donations, Repos.Payments payments) {
        this.donations = donations; this.payments = payments;
    }

    @GetMapping("/{id}")
    public Map<String, Object> snapshot(@PathVariable UUID id) {
        Donation d = donations.findById(id).orElseThrow(() -> ApiException.notFound("Donation"));
        Map<String, Object> m = new HashMap<>();
        m.put("donationId", d.getId().toString());
        m.put("ngoId", d.getNgoId().toString());
        if (d.getDonorId() != null) m.put("donorId", d.getDonorId().toString());
        m.put("donorName", d.getDonorName());
        if (d.getDonorEmail() != null) m.put("donorEmail", d.getDonorEmail());
        if (d.getDonorPan() != null) m.put("donorPan", d.getDonorPan());
        if (d.getDonorUserId() != null) m.put("donorUserId", d.getDonorUserId().toString());
        m.put("amount", d.getAmount().toPlainString());
        m.put("refunded", d.getRefundedAmount().toPlainString());
        m.put("channel", d.getChannel().name());
        m.put("status", d.getStatus().name());
        m.put("wants80g", d.isWants80g());
        if (d.getPurpose() != null) m.put("purpose", d.getPurpose());
        if (d.getCampaignId() != null) m.put("campaignId", d.getCampaignId().toString());
        if (d.getPaidAt() != null) m.put("paidAt", d.getPaidAt().toString());
        String ref = d.getOfflineReference();
        if (ref == null) {
            ref = payments.findByDonationIdOrderByCreatedAtDesc(id).stream().map(p -> p.getProviderPaymentId())
                    .filter(x -> x != null).findFirst().orElse(null);
        }
        if (ref != null) m.put("paymentReference", ref);
        return m;
    }
}
