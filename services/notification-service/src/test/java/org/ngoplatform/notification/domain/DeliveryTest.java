package org.ngoplatform.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeliveryTest {
    private static final Instant T0 = Instant.parse("2026-10-29T00:00:00Z");

    private static Delivery email() {
        return new Delivery(UUID.randomUUID(), UUID.randomUUID(), "EMAIL", "  Sunil.Staff@NGO.test ", "TEAM",
                "STAFF_INVITED", "en", "Subject", "Body", null, null, UUID.randomUUID());
    }

    @Test
    void newDeliveryIsPendingWithNormalisedRecipient() {
        Delivery d = email();
        assertThat(d.getStatus()).isEqualTo(Delivery.Status.PENDING);
        assertThat(d.getRecipient()).isEqualTo("sunil.staff@ngo.test");
        assertThat(d.getAttempts()).isZero();
    }

    @Test
    void successMarksSent() {
        Delivery d = email();
        d.sent(T0);
        assertThat(d.getStatus()).isEqualTo(Delivery.Status.SENT);
        assertThat(d.getSentAt()).isEqualTo(T0);
        assertThat(d.getAttempts()).isEqualTo(1);
        assertThat(d.getLastError()).isNull();
    }

    @Test
    void failuresBackOffThenDeadLetterAfterFiveAttempts() {
        Delivery d = email();
        Duration[] expected = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(30), Duration.ofHours(2)};
        for (int i = 0; i < 4; i++) {
            assertThat(d.failed("smtp down", T0)).isFalse();
            assertThat(d.getStatus()).isEqualTo(Delivery.Status.FAILED);
            assertThat(d.getNextAttemptAt()).isEqualTo(T0.plus(expected[i]));
        }
        assertThat(d.failed("smtp down", T0)).isTrue();
        assertThat(d.getStatus()).isEqualTo(Delivery.Status.DEAD);
        assertThat(d.getAttempts()).isEqualTo(Delivery.MAX_ATTEMPTS);
    }

    @Test
    void errorIsClippedAndDefaulted() {
        Delivery d = email();
        d.failed("e".repeat(2000), T0);
        assertThat(d.getLastError()).hasSize(500);
        Delivery d2 = email();
        d2.failed(null, T0);
        assertThat(d2.getLastError()).isEqualTo("unknown error");
    }

    @Test
    void successAfterFailureClearsError() {
        Delivery d = email();
        d.failed("timeout", T0);
        d.sent(T0.plusSeconds(90));
        assertThat(d.getStatus()).isEqualTo(Delivery.Status.SENT);
        assertThat(d.getLastError()).isNull();
        assertThat(d.getAttempts()).isEqualTo(2);
    }

    @Test
    void deadLetterCanBeRequeued() {
        Delivery d = email();
        for (int i = 0; i < 5; i++) d.failed("x", T0);
        d.requeue(T0.plusSeconds(10));
        assertThat(d.getStatus()).isEqualTo(Delivery.Status.PENDING);
        assertThat(d.getAttempts()).isZero();
        assertThat(d.getNextAttemptAt()).isEqualTo(T0.plusSeconds(10));
    }

    @Test
    void sentOrPendingCannotBeRequeued() {
        Delivery sent = email();
        sent.sent(T0);
        assertThatThrownBy(() -> sent.requeue(T0)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> email().requeue(T0)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void suppressionRecordsReason() {
        Delivery d = email();
        d.suppress("OPTED_OUT");
        assertThat(d.getStatus()).isEqualTo(Delivery.Status.SUPPRESSED);
        assertThat(d.getLastError()).isEqualTo("OPTED_OUT");
    }

    @Test
    void longSubjectIsClipped() {
        Delivery d = new Delivery(null, null, "EMAIL", "a@b.c", "TEAM", "X", "en", "s".repeat(500), "b", null, null, null);
        assertThat(d.getSubject()).hasSize(200);
    }

    @Test
    void notificationStartsUnreadAndMarksReadOnce() {
        Notification n = new Notification(UUID.randomUUID(), null, "TEAM", "STAFF_JOINED", "t", "b", "/x", null);
        assertThat(n.getReadAt()).isNull();
        n.markRead(T0);
        n.markRead(T0.plusSeconds(60));
        assertThat(n.getReadAt()).isEqualTo(T0);
    }
}
