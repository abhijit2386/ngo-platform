package org.ngoplatform.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ngoplatform.commons.events.EventEnvelope;
import org.ngoplatform.commons.events.EventTypes;
import org.ngoplatform.commons.events.IdempotentConsumer;

class EventsListenerTest {
    private final Directory directory = mock(Directory.class);
    private final EventsListener listener = new EventsListener(mock(IdempotentConsumer.class), mock(NotificationPlanner.class), directory);
    private final UUID ngo = UUID.randomUUID();
    private final UUID asha = UUID.randomUUID();

    private EventEnvelope event(String type, Map<String, Object> payload) {
        return new EventEnvelope(UUID.randomUUID(), type, 1, Instant.now(), "c", ngo, "r-1", payload);
    }

    private void admins() {
        when(directory.admins(ngo)).thenReturn(List.of(new Directory.Person(asha, "asha@ngo.test", "Asha", "mr")));
    }

    @Test
    void approvalGoesToAdminsWithNameFromEvent() {
        admins();
        var r = listener.toRequest(event(EventTypes.NGO_VERIFIED, Map.of("displayName", "Asha Seva", "message", "Welcome")));
        assertThat(r.templateCode()).isEqualTo("NGO_APPROVED");
        assertThat(r.recipients()).extracting(NotificationPlanner.Recipient::userId).containsExactly(asha);
        assertThat(r.vars()).containsEntry("ngoName", "Asha Seva").containsEntry("message", "Welcome");
        assertThat(r.link()).isEqualTo("/ngo/home");
        verify(directory, never()).ngoName(any());
    }

    @Test
    void approvalWithoutNameAsksRegistry() {
        admins();
        when(directory.ngoName(ngo)).thenReturn("From Registry");
        var r = listener.toRequest(event(EventTypes.NGO_VERIFIED, Map.of()));
        assertThat(r.vars()).containsEntry("ngoName", "From Registry").containsEntry("message", "");
    }

    @Test
    void rejectionLinksBackToTheWizard() {
        admins();
        var r = listener.toRequest(event(EventTypes.NGO_REJECTED, Map.of("displayName", "X", "reason", "Blurred PAN")));
        assertThat(r.templateCode()).isEqualTo("NGO_REJECTED");
        assertThat(r.vars()).containsEntry("reason", "Blurred PAN");
        assertThat(r.link()).isEqualTo("/ngo/register/" + ngo);
    }

    @Test
    void infoRequestCarriesMessage() {
        admins();
        var r = listener.toRequest(event(EventTypes.NGO_INFO_REQUESTED, Map.of("displayName", "X", "message", "Upload 12A")));
        assertThat(r.templateCode()).isEqualTo("NGO_INFO_REQUESTED");
        assertThat(r.vars()).containsEntry("message", "Upload 12A");
    }

    @Test
    void invitationToUnknownPersonIsEmailInEnglish() {
        when(directory.ngoName(ngo)).thenReturn("Asha Seva");
        when(directory.userByEmail("new@ngo.test")).thenReturn(Optional.empty());
        var r = listener.toRequest(event(EventTypes.STAFF_INVITED,
                Map.of("email", "new@ngo.test", "role", "STAFF", "expiresAt", "2026-11-04T10:00:00Z")));
        assertThat(r.templateCode()).isEqualTo("STAFF_INVITED");
        var to = r.recipients().get(0);
        assertThat(to.userId()).isNull();
        assertThat(to.email()).isEqualTo("new@ngo.test");
        assertThat(to.language()).isEqualTo("en");
        assertThat(r.vars()).containsEntry("roleCode", "STAFF").containsEntry("expiresAt", "2026-11-04T10:00:00Z");
        assertThat(r.resourceType()).isEqualTo("INVITATION");
    }

    @Test
    void invitationToExistingUserUsesTheirLanguageAndInbox() {
        UUID sunil = UUID.randomUUID();
        when(directory.ngoName(ngo)).thenReturn("Asha Seva");
        when(directory.userByEmail("sunil@ngo.test")).thenReturn(Optional.of(new Directory.Person(sunil, "sunil@ngo.test", "Sunil", "hi")));
        var r = listener.toRequest(event(EventTypes.STAFF_INVITED, Map.of("email", "sunil@ngo.test", "role", "STAFF", "expiresAt", "2026-11-04T10:00:00Z")));
        assertThat(r.recipients().get(0).userId()).isEqualTo(sunil);
        assertThat(r.recipients().get(0).language()).isEqualTo("hi");
        assertThat(r.inApp()).isTrue();
    }

    @Test
    void acceptedInvitationNotifiesOtherAdminsNotTheNewMember() {
        UUID sunil = UUID.randomUUID();
        when(directory.ngoName(ngo)).thenReturn("Asha Seva");
        when(directory.user(sunil)).thenReturn(Optional.of(new Directory.Person(sunil, "sunil@ngo.test", "Sunil More", "en")));
        when(directory.admins(ngo)).thenReturn(List.of(new Directory.Person(asha, "asha@ngo.test", "Asha", "en"),
                new Directory.Person(sunil, "sunil@ngo.test", "Sunil More", "en")));
        var r = listener.toRequest(event(EventTypes.STAFF_ACCEPTED, Map.of("userId", sunil.toString(), "role", "SECONDARY_ADMIN")));
        assertThat(r.templateCode()).isEqualTo("STAFF_JOINED");
        assertThat(r.recipients()).extracting(NotificationPlanner.Recipient::userId).containsExactly(asha);
        assertThat(r.vars()).containsEntry("memberName", "Sunil More");
    }

    @Test
    void memberWithoutNameIsShownByEmail() {
        UUID m = UUID.randomUUID();
        admins();
        when(directory.ngoName(ngo)).thenReturn("X");
        when(directory.user(m)).thenReturn(Optional.of(new Directory.Person(m, "m@ngo.test", "", "en")));
        var r = listener.toRequest(event(EventTypes.STAFF_ACCEPTED, Map.of("userId", m.toString(), "role", "STAFF")));
        assertThat(r.vars()).containsEntry("memberName", "m@ngo.test");
    }

    @Test
    void renewalRemindersMapByKind() {
        admins();
        when(directory.ngoName(ngo)).thenReturn("X");
        for (String kind : List.of("D15", "D7", "D1")) {
            var r = listener.toRequest(event(EventTypes.SUBSCRIPTION_EXPIRING, Map.of("kind", kind, "plan", "GROWTH",
                    "daysLeft", 7, "periodEnd", "2026-11-27T12:23:41Z")));
            assertThat(r.templateCode()).isEqualTo("SUBSCRIPTION_RENEWAL_DUE");
            assertThat(r.vars()).containsEntry("planCode", "GROWTH").containsEntry("periodEndAt", "2026-11-27T12:23:41Z");
        }
        assertThat(listener.toRequest(event(EventTypes.SUBSCRIPTION_EXPIRING, Map.of("kind", "GRACE_ENDING"))).templateCode())
                .isEqualTo("SUBSCRIPTION_EXPIRED");
        assertThat(listener.toRequest(event(EventTypes.SUBSCRIPTION_EXPIRING, Map.of("kind", "SUSPENDED"))).templateCode())
                .isEqualTo("SUBSCRIPTION_SUSPENDED");
    }

    @Test
    void unknownReminderKindIsIgnored() {
        assertThat(listener.toRequest(event(EventTypes.SUBSCRIPTION_EXPIRING, Map.of("kind", "D99")))).isNull();
        verifyNoInteractions(directory);
    }

    @Test
    void paymentFailureCarriesAmount() {
        admins();
        when(directory.ngoName(ngo)).thenReturn("X");
        var r = listener.toRequest(event(EventTypes.SUBSCRIPTION_PAYMENT_FAILED, Map.of("amount", "1497.00")));
        assertThat(r.templateCode()).isEqualTo("SUBSCRIPTION_PAYMENT_FAILED");
        assertThat(r.vars()).containsEntry("amount", "1497.00");
    }

    @Test
    void unrelatedEventsAreIgnored() {
        assertThat(listener.toRequest(event(EventTypes.DONATION_PAID, Map.of()))).isNull();
    }

    @Test
    void subscribesToExactlyTheHandledTypes() {
        assertThat(listener.eventTypes()).contains(EventTypes.STAFF_INVITED, EventTypes.NGO_VERIFIED,
                EventTypes.SUBSCRIPTION_EXPIRING).doesNotContain(EventTypes.DONATION_PAID);
        assertThat(listener.consumerName()).isEqualTo("notification.events");
    }

    @Test
    void campaignSubmissionGoesToAdminsNotTheSubmitter() {
        UUID sunil = UUID.randomUUID();
        when(directory.user(sunil)).thenReturn(Optional.of(new Directory.Person(sunil, "sunil@ngo.test", "Sunil More", "en")));
        admins();
        var r = listener.toRequest(event(EventTypes.CAMPAIGN_SUBMITTED, Map.of("title", "Books for 200 children", "submittedBy", sunil.toString())));
        assertThat(r.templateCode()).isEqualTo("CAMPAIGN_SUBMITTED");
        assertThat(r.recipients()).extracting(NotificationPlanner.Recipient::userId).containsExactly(asha);
        assertThat(r.vars()).containsEntry("memberName", "Sunil More").containsEntry("campaignTitle", "Books for 200 children");
        assertThat(r.link()).isEqualTo("/ngo/campaigns/r-1");
    }

    @Test
    void approvalTellsTheRequester() {
        UUID sunil = UUID.randomUUID();
        when(directory.user(sunil)).thenReturn(Optional.of(new Directory.Person(sunil, "sunil@ngo.test", "Sunil More", "hi")));
        var r = listener.toRequest(event(EventTypes.CAMPAIGN_PUBLISHED, Map.of("title", "T", "requestedBy", sunil.toString(),
                "approvedBy", asha.toString())));
        assertThat(r.templateCode()).isEqualTo("CAMPAIGN_APPROVED");
        assertThat(r.recipients()).extracting(NotificationPlanner.Recipient::userId).containsExactly(sunil);
    }

    @Test
    void adminPublishingOwnDraftNotifiesNobody() {
        assertThat(listener.toRequest(event(EventTypes.CAMPAIGN_PUBLISHED, Map.of("title", "T", "requestedBy", asha.toString(),
                "approvedBy", asha.toString())))).isNull();
        verifyNoInteractions(directory);
    }

    @Test
    void rejectionCarriesReasonToRequester() {
        UUID sunil = UUID.randomUUID();
        when(directory.user(sunil)).thenReturn(Optional.of(new Directory.Person(sunil, "sunil@ngo.test", "Sunil More", "en")));
        var r = listener.toRequest(event(EventTypes.CAMPAIGN_REJECTED, Map.of("title", "T", "requestedBy", sunil.toString(),
                "reason", "Add a cover photo")));
        assertThat(r.templateCode()).isEqualTo("CAMPAIGN_REJECTED");
        assertThat(r.vars()).containsEntry("reason", "Add a cover photo");
        assertThat(r.recipients()).extracting(NotificationPlanner.Recipient::userId).containsExactly(sunil);
    }
}
