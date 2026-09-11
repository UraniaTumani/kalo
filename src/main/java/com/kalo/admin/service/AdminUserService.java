package com.kalo.admin.service;

import com.kalo.admin.dto.AdminUserResponse;
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
}
