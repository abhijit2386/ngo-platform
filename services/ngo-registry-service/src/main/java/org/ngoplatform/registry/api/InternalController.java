package org.ngoplatform.registry.api;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.registry.domain.Ngo;
import org.ngoplatform.registry.domain.Parts;
import org.ngoplatform.registry.domain.Repos;
import org.springframework.web.bind.annotation.*;

/** Facts other services need (donation eligibility, receipt header data). */
@RestController
@RequestMapping("/internal/v1/ngos")
public class InternalController {
    private final Repos.Ngos ngos;
    private final Repos.Addresses addresses;
    private final Repos.Contacts contacts;

    public InternalController(Repos.Ngos ngos, Repos.Addresses addresses, Repos.Contacts contacts) {
        this.ngos = ngos; this.addresses = addresses; this.contacts = contacts;
    }

    @GetMapping("/{ngoId}")
    public Map<String, Object> facts(@PathVariable UUID ngoId) {
        Ngo n = ngos.findById(ngoId).orElseThrow(() -> ApiException.notFound("Organization"));
        Parts.Address a = addresses.findById(ngoId).orElse(null);
        Map<String, Object> m = new HashMap<>();
        m.put("ngoId", n.getId().toString());
        m.put("legalName", n.getLegalName());
        m.put("displayName", n.getDisplayName());
        m.put("accountStatus", n.getAccountStatus().name());
        m.put("verificationStatus", n.getVerificationStatus().name());
        m.put("donationEligible", n.donationEligible());
        m.put("donationEligibility", n.donationEligibilityStatus());
        m.put("registrationNumber", n.getRegistrationNumber());
        if (n.getPan() != null) m.put("pan", n.getPan());
        m.put("tax80gVerified", n.isTax80gVerified());
        if (n.getTax80gNumber() != null) m.put("tax80gNumber", n.getTax80gNumber());
        if (n.getTax80gValidUntil() != null) m.put("tax80gValidUntil", n.getTax80gValidUntil().toString());
        if (n.getLogoKey() != null) m.put("logoKey", n.getLogoKey());
        if (n.getOfficialEmail() != null) m.put("officialEmail", n.getOfficialEmail());
        if (a != null) {
            m.put("address", String.join(", ", java.util.stream.Stream.of(a.getLine1(), a.getLine2(), a.getCity(),
                    a.getDistrict(), a.getState() + " " + a.getPinCode()).filter(s -> s != null && !s.isBlank()).toList()));
            m.put("city", a.getCity());
        }
        contacts.findByNgoIdAndKind(ngoId, "SIGNATORY").ifPresent(c -> {
            m.put("signatoryName", c.getName());
            if (c.getDesignation() != null) m.put("signatoryDesignation", c.getDesignation());
        });
        return m;
    }

    @PutMapping("/{ngoId}/payment-onboarding/{status}")
    public void paymentOnboarding(@PathVariable UUID ngoId, @PathVariable Ngo.PaymentOnboarding status) {
        Ngo n = ngos.findById(ngoId).orElseThrow(() -> ApiException.notFound("Organization"));
        n.setPaymentOnboarding(status);
        ngos.save(n);
    }
}
