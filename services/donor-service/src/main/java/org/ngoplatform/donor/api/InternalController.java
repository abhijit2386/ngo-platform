package org.ngoplatform.donor.api;

import jakarta.validation.Valid;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.donor.app.DonorService;
import org.ngoplatform.donor.domain.Donor;
import org.ngoplatform.donor.domain.Repos;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/internal/v1/ngos/{ngoId}")
public class InternalController {
    private final DonorService svc;
    private final Repos.Donors donors;

    public InternalController(DonorService svc, Repos.Donors donors) { this.svc = svc; this.donors = donors; }

    /** Checkout-time donor resolution (find or create) for donation-payment-service. */
    @PostMapping("/donors/resolve")
    public Map<String, Object> resolve(@PathVariable UUID ngoId, @Valid @RequestBody Dtos.ResolveRequest r) {
        Donor d = svc.resolve(ngoId, r.userId(), r.name(), r.email(), r.phone(), r.pan(), r.guest());
        return Map.of("donorId", d.getId(), "name", d.getName());
    }

    @GetMapping("/donors/{donorId}/receipt-details")
    public Map<String, Object> receiptDetails(@PathVariable UUID ngoId, @PathVariable UUID donorId) {
        Donor d = svc.load(ngoId, donorId);
        Map<String, Object> m = new java.util.HashMap<>();
        m.put("name", d.getName());
        if (d.getEmail() != null) m.put("email", d.getEmail());
        if (d.getPan() != null) m.put("pan", d.getPan());
        String addr = String.join(", ", java.util.stream.Stream.of(d.getAddressLine(), d.getCity(), d.getState(), d.getPinCode())
                .filter(s -> s != null && !s.isBlank()).toList());
        if (!addr.isBlank()) m.put("address", addr);
        return m;
    }

    @GetMapping("/donor-stats")
    public Map<String, Long> stats(@PathVariable UUID ngoId) {
        return Map.of("donorCount", donors.countByNgoIdAndStatus(ngoId, Donor.Status.ACTIVE));
    }
}
