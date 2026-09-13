package com.kalo.auth.service;

import com.kalo.auth.dto.*;

public interface AuthService {

    UserResponse registerCustomer(
            CustomerRegisterRequest request
    );

    PartnerRegisterResponse registerPartner(
            PartnerRegisterRequest request
    );
    LoginResponse login(
            LoginRequest request
    );

    /**
     * Exchanges a refresh token for a fresh pair, rotating the refresh
     * token in the process.
     */
    LoginResponse refresh(
            RefreshRequest request
    );

    /** Revokes the presented refresh token. Silent when it is already gone. */
    void logout(
            RefreshRequest request
    );
}