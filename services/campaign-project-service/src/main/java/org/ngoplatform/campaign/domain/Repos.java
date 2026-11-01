package org.ngoplatform.campaign.domain;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import jakarta.persistence.criteria.Predicate;
import java.util.ArrayList;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

public final class Repos {
    private Repos() {}

    public interface Campaigns extends JpaRepository<Campaign, UUID>, JpaSpecificationExecutor<Campaign> {
        Optional<Campaign> findByIdAndNgoId(UUID id, UUID ngoId);
        Page<Campaign> findByNgoIdAndStatusNotOrderByUpdatedAtDesc(UUID ngoId, Campaign.Status s, Pageable p);
        Page<Campaign> findByNgoIdAndStatusOrderByUpdatedAtDesc(UUID ngoId, Campaign.Status s, Pageable p);
        long countByNgoIdAndStatus(UUID ngoId, Campaign.Status s);
        boolean existsByNgoIdAndSlug(UUID ngoId, String slug);
        List<Campaign> findByStatusAndEndDateBefore(Campaign.Status s, java.time.LocalDate d);
    }

    /** Live campaigns for donors, optionally by NGO, cause and title/summary text. */
    public static Specification<Campaign> publicCampaigns(UUID ngoId, String cause, String q) {
        return (root, query, cb) -> {
            List<Predicate> p = new ArrayList<>();
            p.add(cb.equal(root.get("status"), Campaign.Status.PUBLISHED));
            if (ngoId != null) p.add(cb.equal(root.get("ngoId"), ngoId));
            if (cause != null) p.add(cb.equal(root.get("cause"), cause));
            if (q != null && !q.isBlank()) {
                String like = "%" + q.strip().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
                p.add(cb.or(cb.like(cb.lower(root.get("title")), like, '\\'), cb.like(cb.lower(root.get("summary")), like, '\\'),
                        cb.like(cb.lower(root.get("ngoName")), like, '\\')));
            }
            return cb.and(p.toArray(Predicate[]::new));
        };
    }

    public interface Expenses extends JpaRepository<Expense, UUID> {
        Optional<Expense> findByIdAndNgoId(UUID id, UUID ngoId);
        List<Expense> findByCampaignIdOrderByExpenseDateDescCreatedAtDesc(UUID campaignId);
        List<Expense> findByNgoIdAndStatusOrderBySubmittedAtAsc(UUID ngoId, org.ngoplatform.campaign.app.AccountRules.Status s);
        long countByCampaignIdAndStatus(UUID campaignId, org.ngoplatform.campaign.app.AccountRules.Status s);
        boolean existsByBudgetLineId(UUID budgetLineId);
        boolean existsByVendorId(UUID vendorId);
    }

    public interface Vendors extends JpaRepository<Vendor, UUID> {
        Optional<Vendor> findByIdAndNgoId(UUID id, UUID ngoId);
        List<Vendor> findByNgoIdOrderByNameAsc(UUID ngoId);
        Optional<Vendor> findByNgoIdAndNameIgnoreCase(UUID ngoId, String name);
    }

    public interface Projects extends JpaRepository<Project, UUID> {
        Optional<Project> findByIdAndNgoId(UUID id, UUID ngoId);
        Page<Project> findByNgoIdOrderByCreatedAtDesc(UUID ngoId, Pageable p);
        long countByNgoIdAndStatusIn(UUID ngoId, Collection<Project.Status> s);
    }
}
