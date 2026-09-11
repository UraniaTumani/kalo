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
}