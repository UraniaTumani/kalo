package com.kalo.user.repository;

import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByPhone(String phone);

    Optional<User> findByEmail(String email);

    boolean existsByPhone(String phone);

    /** Whether anybody holds a role at all — used to decide if bootstrap is due. */
    boolean existsByRole(UserRole role);

    boolean existsByEmail(String email);

    /**
     * Admin user listing. Both filters are optional; a null value matches
     * everything, which keeps the admin endpoint to a single query method.
     */
    @Query("""
            SELECT u
            FROM User u
            WHERE (:role IS NULL OR u.role = :role)
              AND (:status IS NULL OR u.status = :status)
            """)
    Page<User> findAllByOptionalRoleAndStatus(
            @Param("role") UserRole role,
            @Param("status") UserStatus status,
            Pageable pageable
    );
}