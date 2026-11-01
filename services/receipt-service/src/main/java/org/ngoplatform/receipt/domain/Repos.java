package org.ngoplatform.receipt.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

public final class Repos {
    private Repos() {}

    public interface Receipts extends JpaRepository<Receipt, UUID> {
        Optional<Receipt> findByIdAndNgoId(UUID id, UUID ngoId);
        List<Receipt> findByDonationIdOrderByCreatedAtDesc(UUID donationId);
        Page<Receipt> findByNgoIdOrderByCreatedAtDesc(UUID ngoId, Pageable p);
        Page<Receipt> findByNgoIdAndStatusOrderByCreatedAtDesc(UUID ngoId, Receipt.Status s, Pageable p);
        Page<Receipt> findByDonorUserIdOrderByCreatedAtDesc(UUID userId, Pageable p);
        List<Receipt> findByStatusAndNextAttemptAtBefore(Receipt.Status s, Instant now);
        List<Receipt> findByStatusAndUpdatedAtBefore(Receipt.Status s, Instant before);
        List<Receipt> findByStatusAndNotifiedAtIsNullAndUpdatedAtBefore(Receipt.Status s, Instant before);
        Optional<Receipt> findByVerifyCode(String code);
        boolean existsByDonationId(UUID donationId);
    }

    public interface Headers extends JpaRepository<Template.Header, UUID> {
        List<Template.Header> findByNgoIdOrderByUpdatedAtDesc(UUID ngoId);
        Optional<Template.Header> findByIdAndNgoId(UUID id, UUID ngoId);
        Optional<Template.Header> findByNgoIdAndActiveTrue(UUID ngoId);
        long countByNgoIdAndStatusNot(UUID ngoId, Template.Header.Status s);
    }

    public interface Versions extends JpaRepository<Template.Version, UUID> {
        Optional<Template.Version> findByTemplateIdAndVersionNo(UUID templateId, int v);
        List<Template.Version> findByTemplateIdOrderByVersionNoDesc(UUID templateId);
    }

    public interface Approvals extends JpaRepository<Template.Approval, UUID> {
        List<Template.Approval> findByTemplateIdOrderByCreatedAtDesc(UUID templateId);
        Optional<Template.Approval> findFirstByTemplateIdAndDecisionIsNullOrderByCreatedAtDesc(UUID templateId);
    }
}
