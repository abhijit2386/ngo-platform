package org.ngoplatform.registry.domain;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public final class Repos {
    private Repos() {}

    public interface Ngos extends JpaRepository<Ngo, UUID> {
        List<Ngo> findByCreatedByOrderByCreatedAtDesc(UUID createdBy);
        Page<Ngo> findByVerificationStatusOrderBySubmittedAtAsc(Ngo.VerificationStatus status, Pageable p);

        @Query(value = "select * from ngos n where n.registration_status = 'SUBMITTED' and n.verification_status <> 'REJECTED' "
                + "and n.account_status <> 'CLOSED' and n.registration_state = :state and n.registration_number_norm = :norm "
                + "and n.id <> :self", nativeQuery = true)
        List<Ngo> findLiveByRegistration(String state, String norm, UUID self);

        @Query(value = "select * from ngos n where n.pan = :pan and n.id <> :self and n.registration_status = 'SUBMITTED'",
                nativeQuery = true)
        List<Ngo> findOthersByPan(String pan, UUID self);

        @Query(value = "select * from ngos n where lower(n.display_name) = lower(:name) and n.id <> :self "
                + "and n.registration_status = 'SUBMITTED'", nativeQuery = true)
        List<Ngo> findOthersByName(String name, UUID self);

        @Query(value = "select n.* from ngos n join ngo_addresses a on a.ngo_id = n.id "
                + "where n.public_profile_status = 'PUBLISHED' and n.account_status = 'ACTIVE' "
                + "and (cast(:q as text) is null or n.display_name ilike concat('%', cast(:q as text), '%') or n.description ilike concat('%', cast(:q as text), '%')) "
                + "and (cast(:city as text) is null or lower(a.city) = lower(cast(:city as text)) or lower(a.district) = lower(cast(:city as text))) "
                + "and (cast(:cause as text) is null or n.areas_of_activity @> cast(concat('[\"', cast(:cause as text), '\"]') as jsonb)) "
                + "order by n.display_name",
                countQuery = "select count(*) from ngos n join ngo_addresses a on a.ngo_id = n.id "
                + "where n.public_profile_status = 'PUBLISHED' and n.account_status = 'ACTIVE' "
                + "and (cast(:q as text) is null or n.display_name ilike concat('%', cast(:q as text), '%') or n.description ilike concat('%', cast(:q as text), '%')) "
                + "and (cast(:city as text) is null or lower(a.city) = lower(cast(:city as text)) or lower(a.district) = lower(cast(:city as text))) "
                + "and (cast(:cause as text) is null or n.areas_of_activity @> cast(concat('[\"', cast(:cause as text), '\"]') as jsonb))",
                nativeQuery = true)
        Page<Ngo> searchPublic(String q, String city, String cause, Pageable p);

        List<Ngo> findByVerificationStatusAndReverifyDueAtBefore(Ngo.VerificationStatus status, Instant before);
    }

    public interface Addresses extends JpaRepository<Parts.Address, UUID> {}

    public interface Contacts extends JpaRepository<Parts.Contact, UUID> {
        List<Parts.Contact> findByNgoId(UUID ngoId);
        Optional<Parts.Contact> findByNgoIdAndKind(UUID ngoId, String kind);
    }

    public interface Documents extends JpaRepository<Parts.Document, UUID> {
        List<Parts.Document> findByNgoIdAndStatusNotOrderByCreatedAtDesc(UUID ngoId, String status);
        List<Parts.Document> findByNgoIdAndTypeAndStatusNot(UUID ngoId, String type, String status);
        Optional<Parts.Document> findByIdAndNgoId(UUID id, UUID ngoId);
        List<Parts.Document> findByExpiresOnBeforeAndStatus(java.time.LocalDate date, String status);
        List<Parts.Document> findByStatusNot(String status);
    }

    public interface Verifications extends JpaRepository<Parts.VerificationEntry, UUID> {
        List<Parts.VerificationEntry> findByNgoIdOrderByCreatedAtDesc(UUID ngoId);
    }

    public interface ChangeRequests extends JpaRepository<Parts.ChangeRequest, UUID> {
        List<Parts.ChangeRequest> findByNgoIdOrderByCreatedAtDesc(UUID ngoId);
        List<Parts.ChangeRequest> findByStatusOrderByCreatedAtAsc(String status);
    }
}
