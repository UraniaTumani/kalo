package com.kalo.auth.dto;

import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;

public record UserResponse(
        Long id,
        String firstName,
        String lastName,
        String phone,
        String email,
        UserRole role,
        UserStatus status,
        boolean phoneVerified
) {
}