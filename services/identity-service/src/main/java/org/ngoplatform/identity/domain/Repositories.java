package org.ngoplatform.identity.domain;

import jakarta.persistence.LockModeType;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

public final class Repositories {
    private Repositories() {}

    public interface Users extends JpaRepository<UserAccount, UUID> {
        Optional<UserAccount> findByEmailIgnoreCase(String email);
    }

    public interface Tenants extends JpaRepository<Tenant, UUID> {
        /** Serialises all seat-changing operations of one NGO (prevents concurrent over-allocation). */
        @Lock(LockModeType.PESSIMISTIC_WRITE)
        @Query("select t from Tenant t where t.ngoId = :ngoId")
        Optional<Tenant> lock(UUID ngoId);
    }

    public interface Memberships extends JpaRepository<NgoMembership, UUID> {
        Optional<NgoMembership> findByNgoIdAndUserId(UUID ngoId, UUID userId);
        List<NgoMembership> findByUserIdAndStatusNot(UUID userId, NgoMembership.Status status);
        List<NgoMembership> findByNgoIdAndStatusNotOrderByCreatedAt(UUID ngoId, NgoMembership.Status status);

        long countByNgoIdAndStatusAndRoleNotIn(UUID ngoId, NgoMembership.Status status, java.util.Collection<String> roles);
        long countByNgoIdAndStatusAndRoleIn(UUID ngoId, NgoMembership.Status status, java.util.Collection<String> roles);

        default long countActiveStaff(UUID ngoId) {
            return countByNgoIdAndStatusAndRoleNotIn(ngoId, NgoMembership.Status.ACTIVE, NgoMembership.ADMIN_ROLES);
        }

        default long countActiveAdmins(UUID ngoId) {
            return countByNgoIdAndStatusAndRoleIn(ngoId, NgoMembership.Status.ACTIVE, NgoMembership.ADMIN_ROLES);
        }
    }

    public interface Invitations extends JpaRepository<StaffInvitation, UUID> {
        List<StaffInvitation> findByNgoIdAndStatusOrderByCreatedAtDesc(UUID ngoId, StaffInvitation.Status status);
        List<StaffInvitation> findByEmailIgnoreCaseAndStatus(String email, StaffInvitation.Status status);
        Optional<StaffInvitation> findByIdAndNgoId(UUID id, UUID ngoId);

        long countByNgoIdAndStatusAndExpiresAtAfterAndRoleNotIn(UUID ngoId, StaffInvitation.Status status, Instant now,
                                                                java.util.Collection<String> roles);
        long countByNgoIdAndStatusAndExpiresAtAfterAndRoleIn(UUID ngoId, StaffInvitation.Status status, Instant now,
                                                             java.util.Collection<String> roles);

        default long countPendingStaff(UUID ngoId, Instant now) {
            return countByNgoIdAndStatusAndExpiresAtAfterAndRoleNotIn(ngoId, StaffInvitation.Status.PENDING_ACCEPTANCE, now,
                    NgoMembership.ADMIN_ROLES);
        }

        default long countPendingAdmins(UUID ngoId, Instant now) {
            return countByNgoIdAndStatusAndExpiresAtAfterAndRoleIn(ngoId, StaffInvitation.Status.PENDING_ACCEPTANCE, now,
                    NgoMembership.ADMIN_ROLES);
        }

        List<StaffInvitation> findByStatusAndExpiresAtBefore(StaffInvitation.Status status, Instant now);
    }
}
