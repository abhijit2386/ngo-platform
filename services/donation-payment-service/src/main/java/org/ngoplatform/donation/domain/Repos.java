package org.ngoplatform.donation.domain;

import jakarta.persistence.LockModeType;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public final class Repos {
    private Repos() {}

    public interface Donations extends JpaRepository<Donation, UUID>, JpaSpecificationExecutor<Donation> {
        Optional<Donation> findByIdAndNgoId(UUID id, UUID ngoId);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select d from Donation d where d.id = :id")
        Optional<Donation> lock(UUID id);

        Page<Donation> findByDonorUserIdOrderByCreatedAtDesc(UUID userId, Pageable p);

        List<Donation> findByStatusInAndCreatedAtBefore(List<Donation.Status> statuses, Instant before);

        @Query(value = "select coalesce(sum(amount - refunded_amount), 0) from donations where ngo_id = :ngoId and voided = false "
                + "and status in ('PAID','PARTIALLY_REFUNDED')", nativeQuery = true)
        BigDecimal netReceived(UUID ngoId);
    }

    public interface Payments extends JpaRepository<Records.Payment, UUID> {
        Optional<Records.Payment> findByProviderOrderId(String orderId);
        Optional<Records.Payment> findByProviderPaymentId(String paymentId);
        List<Records.Payment> findByDonationIdOrderByCreatedAtDesc(UUID donationId);
        List<Records.Payment> findByNgoIdAndStatus(UUID ngoId, String status);
    }

    public interface Events extends JpaRepository<Records.PaymentEvent, UUID> {
        boolean existsByProviderAndProviderEventId(String provider, String eventId);
        List<Records.PaymentEvent> findByDonationIdOrderByReceivedAtAsc(UUID donationId);
    }

    public interface Settings extends JpaRepository<Records.PaymentSettings, UUID> {}

    public interface Refunds extends JpaRepository<Records.Refund, UUID> {
        List<Records.Refund> findByNgoIdOrderByCreatedAtDesc(UUID ngoId);
        List<Records.Refund> findByDonationIdOrderByCreatedAtDesc(UUID donationId);
        Optional<Records.Refund> findByIdAndNgoId(UUID id, UUID ngoId);
        List<Records.Refund> findByStatus(Records.Refund.Status status);

        @Query(value = "select coalesce(sum(amount), 0) from refunds where donation_id = :donationId "
                + "and status in ('REQUESTED','APPROVED','PROCESSING')", nativeQuery = true)
        BigDecimal openRefundTotal(UUID donationId);
    }

    public interface Batches extends JpaRepository<Records.SettlementBatch, UUID> {
        boolean existsByProviderAndProviderSettlementId(String provider, String id);
        List<Records.SettlementBatch> findByNgoIdOrderBySettledOnDesc(UUID ngoId);
        Optional<Records.SettlementBatch> findByIdAndNgoId(UUID id, UUID ngoId);
    }

    public interface Lines extends JpaRepository<Records.SettlementLine, UUID> {
        List<Records.SettlementLine> findByBatchId(UUID batchId);
        boolean existsByProviderPaymentId(String paymentId);
    }

    public interface Runs extends JpaRepository<Records.ReconRun, UUID> {}

    public interface Exceptions extends JpaRepository<Records.ReconException, UUID> {
        List<Records.ReconException> findByNgoIdAndStatusOrderByCreatedAtDesc(UUID ngoId, String status);
        Optional<Records.ReconException> findByIdAndNgoId(UUID id, UUID ngoId);
        List<Records.ReconException> findByStatusOrderByCreatedAtAsc(String status);
    }
}
