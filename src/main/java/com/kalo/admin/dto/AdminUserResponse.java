package com.kalo.admin.dto;

import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;

import java.time.Instant;

public record AdminUserResponse(

        Long userId,

        String firstName,

        String lastName,

        String phone,

        String email,

        UserRole role,

        UserStatus status,

        boolean phoneVerified,

        Instant createdAt

) {
}
