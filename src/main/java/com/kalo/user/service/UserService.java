package com.kalo.user.service;

import com.kalo.auth.dto.UserResponse;
import com.kalo.auth.dto.LoginResponse;
import com.kalo.user.dto.ChangePasswordRequest;
import com.kalo.user.dto.UpdateProfileRequest;

public interface UserService {

    UserResponse getCurrentUser();

    UserResponse updateCurrentUser(
            UpdateProfileRequest request
    );

    /**
     * Replaces the signed-in user's password and returns a fresh session.
     *
     * Every other session is dropped, including the one that asked — so the
     * caller is handed new tokens rather than being quietly signed out a
     * moment later. Changing a password is how somebody removes an intruder,
     * and leaving the intruder's refresh token alive for thirty days would
     * defeat the point of doing it.
     */
    LoginResponse changePassword(
            ChangePasswordRequest request
    );
}
