package org.ngoplatform.registry.api;

import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.web.PageResponse;
import org.ngoplatform.registry.app.RegistryService;
import org.ngoplatform.registry.domain.Ngo;
import org.ngoplatform.registry.domain.Repos;
import org.springframework.data.domain.PageRequest;
import org.springframework.web.bind.annotation.*;

/** ADMIN-002..005: platform verification queue & decisions. */
@RestController
@RequestMapping("/api/v1/admin")
public class AdminController {
    private final RegistryService svc;
    private final AccessGuard guard;
    private final Repos.Ngos ngos;
    private final Repos.Verifications verifications;
    private final Repos.ChangeRequests changes;
    private final NgoController ngoController;

    public AdminController(RegistryService svc, AccessGuard guard, Repos.Ngos ngos, Repos.Verifications verifications,
                           Repos.ChangeRequests changes, NgoController ngoController) {
        this.svc = svc; this.guard = guard; this.ngos = ngos; this.verifications = verifications;
        this.changes = changes; this.ngoController = ngoController;
    }

    @GetMapping("/verifications")
    public PageResponse<Dtos.QueueItem> queue(@RequestParam(defaultValue = "PENDING") Ngo.VerificationStatus status,
                                              @RequestParam(defaultValue = "0") int page) {
        guard.requirePlatformSupport();
        return PageResponse.of(ngos.findByVerificationStatusOrderBySubmittedAtAsc(status, PageRequest.of(page, 20)),
                n -> new Dtos.QueueItem(n.getId(), n.getDisplayName(), n.getLegalName(), n.getRegistrationState(),
                        n.getVerificationStatus().name(), n.getRiskFlags(), n.getSubmittedAt()));
    }

    @GetMapping("/ngos/{ngoId}")
    public Dtos.NgoView detail(@PathVariable UUID ngoId) {
        guard.requirePlatformSupport();
        return ngoController.view(svc.load(ngoId));
    }

    @GetMapping("/ngos/{ngoId}/verification-history")
    public List<Dtos.AdminVerificationHistory> history(@PathVariable UUID ngoId) {
        guard.requirePlatformSupport();
        return verifications.findByNgoIdOrderByCreatedAtDesc(ngoId).stream()
                .map(v -> new Dtos.AdminVerificationHistory(v.getAction(), v.getActorId(), v.getInternalNote(),
                        v.getMessageToNgo(), v.getCreatedAt())).toList();
    }

    @PostMapping("/ngos/{ngoId}/verification")
    public Dtos.NgoView decide(@PathVariable UUID ngoId, @Valid @RequestBody Dtos.VerificationDecision d) {
        return ngoController.view(svc.decide(ngoId, d));
    }

    @PostMapping("/ngos/{ngoId}/suspension")
    public Dtos.NgoView suspension(@PathVariable UUID ngoId, @Valid @RequestBody Dtos.Suspension s) {
        return ngoController.view(svc.setSuspension(ngoId, s.suspend(), s.reason()));
    }

    @GetMapping("/ngos/change-requests")
    public List<Dtos.ChangeView> pendingChanges() {
        guard.requirePlatformSupport();
        return changes.findByStatusOrderByCreatedAtAsc("PENDING").stream().map(Dtos.ChangeView::of).toList();
    }

    @PostMapping("/ngos/change-requests/{id}/{decision}")
    public Dtos.ChangeView decideChange(@PathVariable UUID id, @PathVariable String decision) {
        return Dtos.ChangeView.of(svc.decideChange(id, "approve".equalsIgnoreCase(decision)));
    }
}
