package org.ngoplatform.donor.domain;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;

public final class Repos {
    private Repos() {}

    public interface Donors extends JpaRepository<Donor, UUID>, JpaSpecificationExecutor<Donor> {
        Optional<Donor> findByIdAndNgoId(UUID id, UUID ngoId);

        Optional<Donor> findFirstByNgoIdAndStatusAndEmailIgnoreCase(UUID ngoId, Donor.Status status, String email);
        Optional<Donor> findFirstByNgoIdAndStatusAndPhone(UUID ngoId, Donor.Status status, String phone);
        Optional<Donor> findFirstByNgoIdAndStatusAndDonorUserId(UUID ngoId, Donor.Status status, UUID userId);

        default Optional<Donor> findActiveByEmail(UUID ngoId, String email) {
            return findFirstByNgoIdAndStatusAndEmailIgnoreCase(ngoId, Donor.Status.ACTIVE, email);
        }

        default Optional<Donor> findActiveByPhone(UUID ngoId, String phone) {
            return findFirstByNgoIdAndStatusAndPhone(ngoId, Donor.Status.ACTIVE, phone);
        }

        default Optional<Donor> findActiveByUser(UUID ngoId, UUID userId) {
            return findFirstByNgoIdAndStatusAndDonorUserId(ngoId, Donor.Status.ACTIVE, userId);
        }

        /** Possible duplicates: same normalised name, or same PAN. */
        @Query(value = "select * from donors where ngo_id = :ngoId and status = 'ACTIVE' and id <> :self and ("
                + "regexp_replace(lower(name), '[^a-z0-9]', '', 'g') = regexp_replace(lower(:name), '[^a-z0-9]', '', 'g') "
                + "or (pan is not null and pan = :pan))", nativeQuery = true)
        List<Donor> possibleDuplicates(UUID ngoId, UUID self, String name, String pan);

        List<Donor> findByDonorUserIdAndStatus(UUID userId, Donor.Status status);
        long countByNgoIdAndStatus(UUID ngoId, Donor.Status status);
    }

    public interface Facts extends JpaRepository<Records.DonationFact, UUID> {
        Page<Records.DonationFact> findByDonorIdOrderByPaidAtDesc(UUID donorId, Pageable p);
        List<Records.DonationFact> findByDonorId(UUID donorId);
    }

    public interface Profiles extends JpaRepository<Records.Profile, UUID> {}
    public interface Preferences extends JpaRepository<Records.Preferences, UUID> {}
    public interface Consents extends JpaRepository<Records.Consent, UUID> {}

    public interface Follows extends JpaRepository<Records.Follow, Records.FollowId> {
        List<Records.Follow> findByUserIdOrderByCreatedAtDesc(UUID userId);
        long countByNgoId(UUID ngoId);
    }

    public interface Saved extends JpaRepository<Records.SavedCampaign, Records.SavedId> {
        List<Records.SavedCampaign> findByUserId(UUID userId);
    }

    public interface Privacy extends JpaRepository<Records.PrivacyRequest, UUID> {
        List<Records.PrivacyRequest> findByUserIdOrderByCreatedAtDesc(UUID userId);
        List<Records.PrivacyRequest> findByNgoIdOrderByCreatedAtDesc(UUID ngoId);
    }

    public interface Segments extends JpaRepository<Records.Segment, UUID> {
        List<Records.Segment> findByNgoIdOrderByName(UUID ngoId);
        Optional<Records.Segment> findByIdAndNgoId(UUID id, UUID ngoId);
    }

    public interface Imports extends JpaRepository<Records.Import, UUID> {
        Optional<Records.Import> findByIdAndNgoId(UUID id, UUID ngoId);
    }
}
