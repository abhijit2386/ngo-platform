package org.ngoplatform.notification.api;

import jakarta.validation.Valid;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.ngoplatform.commons.error.ApiException;
import org.ngoplatform.commons.security.AccessGuard;
import org.ngoplatform.commons.web.PageResponse;
import org.ngoplatform.notification.app.PreferenceService;
import org.ngoplatform.notification.domain.Repos;
import org.springframework.data.domain.PageRequest;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

/** The signed-in user's inbox, history and preferences (NOTIF-001..007). */
@RestController
@RequestMapping("/api/v1/notifications")
public class NotificationController {
    private final AccessGuard guard;
    private final Repos.Notifications notifications;
    private final Repos.Deliveries deliveries;
    private final PreferenceService preferences;
    private final Clock clock;

    public NotificationController(AccessGuard guard, Repos.Notifications notifications, Repos.Deliveries deliveries,
                                  PreferenceService preferences, Clock clock) {
        this.guard = guard; this.notifications = notifications; this.deliveries = deliveries;
        this.preferences = preferences; this.clock = clock;
    }

    @GetMapping
    public PageResponse<Dtos.NotificationView> inbox(@RequestParam(defaultValue = "0") int page,
                                                     @RequestParam(defaultValue = "20") int size) {
        UUID me = guard.user().userId();
        return PageResponse.of(notifications.findByUserIdOrderByCreatedAtDesc(me, PageRequest.of(Math.max(page, 0),
                Math.min(Math.max(size, 1), 50))), Dtos.NotificationView::of);
    }

    @GetMapping("/unread-count")
    public Dtos.UnreadCount unread() {
        return new Dtos.UnreadCount(notifications.countByUserIdAndReadAtIsNull(guard.user().userId()));
    }

    @GetMapping("/{id}")
    public Dtos.NotificationView detail(@PathVariable UUID id) {
        return Dtos.NotificationView.of(notifications.findByIdAndUserId(id, guard.user().userId())
                .orElseThrow(() -> ApiException.notFound("Notification")));
    }

    @PostMapping("/{id}/read")
    @Transactional
    public Dtos.NotificationView markRead(@PathVariable UUID id) {
        var n = notifications.findByIdAndUserId(id, guard.user().userId()).orElseThrow(() -> ApiException.notFound("Notification"));
        n.markRead(clock.instant());
        return Dtos.NotificationView.of(notifications.save(n));
    }

    @PostMapping("/read-all")
    @Transactional
    public Dtos.UnreadCount markAllRead() {
        UUID me = guard.user().userId();
        notifications.markAllRead(me, clock.instant());
        return new Dtos.UnreadCount(0);
    }

    @GetMapping("/history")
    public PageResponse<Dtos.DeliveryView> history(@RequestParam(defaultValue = "0") int page) {
        return PageResponse.of(deliveries.findByUserIdOrderByCreatedAtDesc(guard.user().userId(),
                PageRequest.of(Math.max(page, 0), 20)), Dtos.DeliveryView::of);
    }

    @GetMapping("/preferences")
    public List<Dtos.PreferenceView> preferences() {
        return view(preferences.settings(guard.user().userId()));
    }

    @PutMapping("/preferences")
    public List<Dtos.PreferenceView> updatePreferences(@Valid @RequestBody Dtos.PreferencesUpdate body) {
        return view(preferences.update(guard.user().userId(), body.preferences().stream()
                .map(c -> new PreferenceService.Setting(c.category(), c.email(), c.inApp(), c.push(), false)).toList()));
    }

    private static List<Dtos.PreferenceView> view(List<PreferenceService.Setting> s) {
        return s.stream().map(x -> new Dtos.PreferenceView(x.category(), x.email(), x.inApp(), x.push(), x.mandatory())).toList();
    }
}
