package org.ngoplatform.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.ngoplatform.notification.app.NotificationPlanner.Recipient;
import org.ngoplatform.notification.app.NotificationPlanner.Request;
import org.ngoplatform.notification.domain.Delivery;
import org.ngoplatform.notification.domain.Notification;
import org.ngoplatform.notification.domain.Repos;
import org.ngoplatform.notification.domain.Template;

class NotificationPlannerTest {
    private final Repos.Templates templates = mock(Repos.Templates.class);
    private final Repos.Notifications notifications = mock(Repos.Notifications.class);
    private final Repos.Deliveries deliveries = mock(Repos.Deliveries.class);
    private final PreferenceService prefs = mock(PreferenceService.class);
    private final NotificationPlanner planner = new NotificationPlanner(templates, notifications, deliveries, prefs);
    private final List<Notification> savedInApp = new ArrayList<>();
    private final List<Delivery> savedMail = new ArrayList<>();
    private final UUID ngo = UUID.randomUUID();
    private final UUID event = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        tpl("STAFF_INVITED", "TEAM", "en", "Invitation from {{ngoName}}", "Join as {{role}} until {{expiresOn}}");
        tpl("STAFF_INVITED", "TEAM", "hi", "{{ngoName}} से निमंत्रण", "{{role}} के रूप में");
        tpl("NGO_APPROVED", "ACCOUNT", "en", "{{ngoName}} is verified", "Trial started");
        when(prefs.allows(any(), anyString(), any())).thenReturn(true);
        when(notifications.save(any())).thenAnswer(i -> { savedInApp.add(i.getArgument(0)); return i.getArgument(0); });
        when(deliveries.save(any())).thenAnswer(i -> { savedMail.add(i.getArgument(0)); return i.getArgument(0); });
    }

    private void tpl(String code, String category, String lang, String subject, String body) {
        for (String ch : List.of("EMAIL", "IN_APP")) {
            when(templates.findByCodeAndChannelAndLanguage(code, ch, lang))
                    .thenReturn(Optional.of(new Template(code, ch, lang, category, subject, body)));
        }
    }

    private Request invite(Recipient... to) {
        return new Request("STAFF_INVITED", ngo, List.of(to),
                Map.of("ngoName", "Asha Seva", "roleCode", "STAFF", "expiresAt", "2026-11-04T10:00:00Z"),
                "/ngo/start", "INVITATION", "inv-1", event, true, true);
    }

    @Test
    void registeredUserGetsInAppAndEmailInEnglish() {
        UUID user = UUID.randomUUID();
        var res = planner.plan(invite(new Recipient(user, "Sunil@ngo.test", "Sunil", "en")));
        assertThat(res.inApp()).isEqualTo(1);
        assertThat(res.emailsQueued()).isEqualTo(1);
        assertThat(savedInApp.get(0).getTitle()).isEqualTo("Invitation from Asha Seva");
        assertThat(savedInApp.get(0).getBody()).isEqualTo("Join as Staff until 4 Nov 2026");
        assertThat(savedInApp.get(0).getLink()).isEqualTo("/ngo/start");
        Delivery d = savedMail.get(0);
        assertThat(d.getRecipient()).isEqualTo("sunil@ngo.test");
        assertThat(d.getStatus()).isEqualTo(Delivery.Status.PENDING);
        assertThat(d.getCategory()).isEqualTo("TEAM");
        assertThat(d.getResourceType()).isEqualTo("INVITATION");
    }

    @Test
    void personWithoutAccountGetsEmailOnly() {
        var res = planner.plan(invite(new Recipient(null, "new@ngo.test", null, null)));
        assertThat(res.inApp()).isZero();
        assertThat(res.emailsQueued()).isEqualTo(1);
        assertThat(savedMail.get(0).getLanguage()).isEqualTo("en");
    }

    @Test
    void usesRecipientLanguageAndLocalizesRole() {
        planner.plan(invite(new Recipient(UUID.randomUUID(), "a@ngo.test", "A", "hi")));
        assertThat(savedInApp.get(0).getTitle()).isEqualTo("Asha Seva से निमंत्रण");
        assertThat(savedInApp.get(0).getBody()).isEqualTo("कर्मचारी के रूप में");
        assertThat(savedMail.get(0).getLanguage()).isEqualTo("hi");
    }

    @Test
    void fallsBackToEnglishWhenLanguageTemplateMissing() {
        planner.plan(invite(new Recipient(UUID.randomUUID(), "a@ngo.test", "A", "mr")));
        assertThat(savedInApp.get(0).getTitle()).isEqualTo("Invitation from Asha Seva");
        assertThat(savedMail.get(0).getLanguage()).isEqualTo("en");
    }

    @Test
    void unsupportedLanguageIsTreatedAsEnglish() {
        planner.plan(invite(new Recipient(UUID.randomUUID(), "a@ngo.test", "A", "fr")));
        assertThat(savedMail.get(0).getLanguage()).isEqualTo("en");
    }

    @Test
    void optedOutOfEmailIsRecordedAsSuppressed() {
        UUID user = UUID.randomUUID();
        when(prefs.allows(user, "TEAM", PreferenceService.Channel.EMAIL)).thenReturn(false);
        var res = planner.plan(invite(new Recipient(user, "a@ngo.test", "A", "en")));
        assertThat(res.suppressed()).isEqualTo(1);
        assertThat(res.emailsQueued()).isZero();
        assertThat(savedMail.get(0).getStatus()).isEqualTo(Delivery.Status.SUPPRESSED);
        assertThat(res.inApp()).isEqualTo(1);
    }

    @Test
    void globalEmailOptOutSuppresses() {
        when(prefs.emailOptedOut("a@ngo.test")).thenReturn(true);
        var res = planner.plan(invite(new Recipient(null, "a@ngo.test", null, "en")));
        assertThat(res.suppressed()).isEqualTo(1);
    }

    @Test
    void mandatoryCategoryIgnoresOptOut() {
        when(prefs.emailOptedOut(anyString())).thenReturn(true);
        when(prefs.allows(any(), eq("ACCOUNT"), eq(PreferenceService.Channel.EMAIL))).thenReturn(false);
        var res = planner.plan(new Request("NGO_APPROVED", ngo, List.of(new Recipient(UUID.randomUUID(), "a@ngo.test", "A", "en")),
                Map.of("ngoName", "Asha Seva"), "/ngo/home", "NGO", ngo.toString(), event, true, true));
        assertThat(res.emailsQueued()).isEqualTo(1);
        assertThat(res.suppressed()).isZero();
    }

    @Test
    void inAppOptOutSkipsInbox() {
        UUID user = UUID.randomUUID();
        when(prefs.allows(user, "TEAM", PreferenceService.Channel.IN_APP)).thenReturn(false);
        var res = planner.plan(invite(new Recipient(user, "a@ngo.test", "A", "en")));
        assertThat(res.inApp()).isZero();
        assertThat(savedInApp).isEmpty();
    }

    @Test
    void replayOfSameEventCreatesNothing() {
        UUID user = UUID.randomUUID();
        when(notifications.existsBySourceEventIdAndUserIdAndTemplateCode(event, user, "STAFF_INVITED")).thenReturn(true);
        when(deliveries.existsBySourceEventIdAndChannelAndRecipientAndTemplateCode(event, "EMAIL", "a@ngo.test", "STAFF_INVITED"))
                .thenReturn(true);
        var res = planner.plan(invite(new Recipient(user, "a@ngo.test", "A", "en")));
        assertThat(res).isEqualTo(new NotificationPlanner.Result(0, 0, 0));
        verify(deliveries, never()).save(any());
    }

    @Test
    void duplicateRecipientsAreNotifiedOnce() {
        UUID user = UUID.randomUUID();
        var res = planner.plan(invite(new Recipient(user, "a@ngo.test", "A", "en"), new Recipient(user, "a@ngo.test", "A", "en")));
        assertThat(res.inApp()).isEqualTo(1);
        assertThat(res.emailsQueued()).isEqualTo(1);
    }

    @Test
    void sameEmailWithoutUserIsDeduplicatedCaseInsensitively() {
        var res = planner.plan(invite(new Recipient(null, "A@ngo.test", null, "en"), new Recipient(null, "a@NGO.test", null, "en")));
        assertThat(res.emailsQueued()).isEqualTo(1);
    }

    @Test
    void recipientWithoutEmailGetsInAppOnly() {
        var res = planner.plan(invite(new Recipient(UUID.randomUUID(), null, "A", "en")));
        assertThat(res.inApp()).isEqualTo(1);
        assertThat(res.emailsQueued()).isZero();
    }

    @Test
    void unknownTemplateSendsNothing() {
        var res = planner.plan(new Request("NOPE", ngo, List.of(new Recipient(UUID.randomUUID(), "a@ngo.test", "A", "en")),
                Map.of(), null, null, null, event, true, true));
        assertThat(res).isEqualTo(new NotificationPlanner.Result(0, 0, 0));
    }

    @Test
    void inactiveTemplateIsSkipped() {
        Template off = new Template("OFF", "EMAIL", "en", "TEAM", "s", "b");
        off.update("s", "b", false, null);
        when(templates.findByCodeAndChannelAndLanguage("OFF", "EMAIL", "en")).thenReturn(Optional.of(off));
        var res = planner.plan(new Request("OFF", ngo, List.of(new Recipient(null, "a@ngo.test", null, "en")),
                Map.of(), null, null, null, event, false, true));
        assertThat(res.emailsQueued()).isZero();
    }

    @Test
    void emailOnlyRequestSkipsInbox() {
        var res = planner.plan(new Request("STAFF_INVITED", ngo, List.of(new Recipient(UUID.randomUUID(), "a@ngo.test", "A", "en")),
                Map.of(), null, null, null, event, false, true));
        assertThat(res.inApp()).isZero();
        assertThat(res.emailsQueued()).isEqualTo(1);
    }

    @Test
    void localizeFormatsInstantsInIndianTime() {
        // 20:00 UTC on 3 Nov is 01:30 IST on 4 Nov.
        var out = NotificationPlanner.localize(Map.of("periodEndAt", "2026-11-03T20:00:00Z"), "en");
        assertThat(out.get("periodEndOn")).isEqualTo("4 Nov 2026");
    }

    @Test
    void localizeLeavesNonInstantValuesAlone() {
        var out = NotificationPlanner.localize(Map.of("lastAt", "not-a-date"), "en");
        assertThat(out).doesNotContainKey("lastOn");
    }

    @Test
    void localizePlanAndRole() {
        var out = NotificationPlanner.localize(Map.of("planCode", "PROFESSIONAL", "roleCode", "FINANCE"), "mr");
        assertThat(out.get("plan")).isEqualTo("Professional");
        assertThat(out.get("role")).isEqualTo("वित्त");
    }

    @Test
    void unknownRoleCodeIsShownAsIs() {
        assertThat(Labels.role("AUDITOR", "hi")).isEqualTo("AUDITOR");
    }
}
