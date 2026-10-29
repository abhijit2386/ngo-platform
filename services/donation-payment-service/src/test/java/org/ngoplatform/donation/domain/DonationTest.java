package org.ngoplatform.donation.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.ngoplatform.commons.error.ApiException;

class DonationTest {
    private Donation online(String amount) {
        return new Donation(UUID.randomUUID(), null, UUID.randomUUID(), "Meera Iyer", "meera@ngo.test", null,
                UUID.randomUUID(), null, new BigDecimal(amount), null, Donation.Channel.ONLINE, false);
    }

    @Test
    void paidIsFinalForFailureEvents() {
        Donation d = online("500.00");
        d.markPaid(Instant.now());
        assertThat(d.getStatus()).isEqualTo(Donation.Status.PAID);
        assertThat(d.canMoveTo(Donation.Status.FAILED)).isFalse();      // late "failed" webhook is ignored
        assertThat(d.canMoveTo(Donation.Status.CANCELLED)).isFalse();
    }

    @Test
    void delayedSuccessAfterFailureIsAccepted() {
        Donation d = online("500.00");
        d.markFailed(Donation.Status.FAILED, "FAILED");
        assertThat(d.canMoveTo(Donation.Status.PAID)).isTrue();
        d.markPaid(Instant.now());
        assertThat(d.getFailureReason()).isNull();
    }

    @Test
    void refundsAddUpAndCannotExceedAmount() {
        Donation d = online("1000.00");
        d.markPaid(Instant.now());
        d.recordRefund(new BigDecimal("400.00"));
        assertThat(d.getStatus()).isEqualTo(Donation.Status.PARTIALLY_REFUNDED);
        assertThat(d.refundable()).isEqualByComparingTo("600.00");
        assertThatThrownBy(() -> d.recordRefund(new BigDecimal("600.01"))).isInstanceOf(ApiException.class);
        d.recordRefund(new BigDecimal("600.00"));
        assertThat(d.getStatus()).isEqualTo(Donation.Status.REFUNDED);
    }

    @Test
    void cannotRefundBeforePayment() {
        Donation d = online("1000.00");
        assertThatThrownBy(() -> d.recordRefund(new BigDecimal("10.00"))).isInstanceOf(ApiException.class);
    }

    @Test
    void checkoutSnapshotsAreKept() {
        Donation d = online("1000.00");
        d.details("12 MG Road, Pune 411001", "Asha Seva", "School books");
        assertThat(d.getNgoName()).isEqualTo("Asha Seva");
        assertThat(d.getCampaignTitle()).isEqualTo("School books");
        assertThat(d.getDonorAddress()).startsWith("12 MG Road");
        assertThat(d.getDonorId()).isNull(); // donor-service links it later
    }
}
