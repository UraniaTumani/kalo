package com.kalo.admin.service;

import com.kalo.admin.dto.AdminUserResponse;
import com.kalo.admin.dto.CreateAdminRequest;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminUserService {

    Page<AdminUserResponse> getUsers(
            UserRole role,
            UserStatus status,
            Pageable pageable
    );

    AdminUserResponse getUserById(
            Long userId
    );

    AdminUserResponse suspendUser(
            Long userId
    );

    AdminUserResponse reactivateUser(
            Long userId
    );

    /**
     * Creates another administrator.
     *
     * Safe as an endpoint precisely because it is not the first one: it sits
     * behind hasRole("ADMIN") like everything else under /api/v1/admin, so it
     * can only be reached by somebody who already has the power it grants.
     * The first administrator comes from AdminBootstrap instead, which reads
     * configuration rather than exposing a route nobody could authenticate to.
     */
    AdminUserResponse createAdmin(
            CreateAdminRequest request
    );
}
