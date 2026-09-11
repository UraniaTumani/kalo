package com.kalo.user.service;

import com.kalo.auth.dto.UserResponse;
import com.kalo.user.dto.UpdateProfileRequest;

public interface UserService {

    UserResponse getCurrentUser();

    UserResponse updateCurrentUser(
            UpdateProfileRequest request
    );
}
