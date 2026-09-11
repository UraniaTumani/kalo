package com.kalo.user.service;

import com.kalo.auth.dto.UserResponse;

public interface UserService {

    UserResponse getCurrentUser();
}