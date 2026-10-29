package org.ngoplatform.subscription.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public final class Repos {
    private Repos() {}

    public interface Plans extends JpaRepository<Plan, String> {
        List<Plan> findByActiveTrueOrderByRank();
    }

    public interface Subscriptions extends JpaRepository<Subscription, UUID> {
        Optional<Subscription> findByNgoId(UUID ngoId);

        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select s from Subscription s where s.ngoId = :ngoId")
        Optional<Subscription> lockByNgoId(UUID ngoId);

        List<Subscription> findByStatusInAndPeriodEndBefore(List<Subscription.Status> statuses, Instant before);
        List<Subscription> findByStatusAndGraceEndsAtBefore(Subscription.Status status, Instant before);
        List<Subscription> findByStatusInAndPeriodEndBetween(List<Subscription.Status> statuses, Instant from, Instant to);
        List<Subscription> findByStatusInAndUpdatedAtBefore(List<Subscription.Status> statuses, Instant before);
        long countByStatus(Subscription.Status status);
    }

    public interface Quotes extends JpaRepository<Records.Quote, UUID> {
        List<Records.Quote> findByNgoIdAndStatus(UUID ngoId, Records.Quote.Status status);
        List<Records.Quote> findByStatusAndExpiresAtBefore(Records.Quote.Status status, Instant now);
    }

    public interface Payments extends JpaRepository<Records.Payment, UUID> {
        Optional<Records.Payment> findByProviderOrderId(String orderId);
        List<Records.Payment> findByNgoIdOrderByCreatedAtDesc(UUID ngoId);
        List<Records.Payment> findByStatusOrderByCreatedAtAsc(Records.Payment.Status status);
        List<Records.Payment> findByStatusInAndCreatedAtBefore(List<Records.Payment.Status> statuses, Instant before);
    }

    public interface Invoices extends JpaRepository<Records.Invoice, UUID> {
        List<Records.Invoice> findByNgoIdOrderByIssuedAtDesc(UUID ngoId);
        Optional<Records.Invoice> findByIdAndNgoId(UUID id, UUID ngoId);
        boolean existsByPaymentId(UUID paymentId);
        @Query(value = "select nextval('subscription_invoice_seq')", nativeQuery = true)
        long nextNumber();
    }

    public interface Changes extends JpaRepository<Records.Change, UUID> {
        List<Records.Change> findByNgoIdOrderByCreatedAtDesc(UUID ngoId);
    }

    public interface Credits extends JpaRepository<Records.Credit, UUID> {
        List<Records.Credit> findByNgoIdOrderByCreatedAtDesc(UUID ngoId);
        List<Records.Credit> findByStatusOrderByCreatedAtAsc(String status);
    }

    public interface Disputes extends JpaRepository<Records.Dispute, UUID> {
        List<Records.Dispute> findByNgoIdOrderByCreatedAtDesc(UUID ngoId);
        List<Records.Dispute> findByStatusOrderByCreatedAtAsc(String status);
    }
}
