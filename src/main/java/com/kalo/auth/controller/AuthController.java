package com.kalo.auth.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.kalo.auth.dto.CustomerRegisterRequest;
import com.kalo.auth.dto.UserResponse;
import com.kalo.auth.service.AuthService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import com.kalo.auth.dto.LoginRequest;
import com.kalo.auth.dto.RefreshRequest;
import com.kalo.auth.dto.LoginResponse;import com.kalo.auth.dto.PartnerRegisterRequest;
import com.kalo.auth.dto.PartnerRegisterResponse;

@Tag(name = "Authentication")
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    @PostMapping("/register/customer")
    public ResponseEntity<UserResponse> registerCustomer(
            @Valid @RequestBody CustomerRegisterRequest request
    ) {

        UserResponse response =
                authService.registerCustomer(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PostMapping("/login")
    public ResponseEntity<LoginResponse> login(
            @Valid @RequestBody LoginRequest request
    ) {

        LoginResponse response =
                authService.login(request);

        return ResponseEntity.ok(response);
    }


    @PostMapping("/register/partner")
    public ResponseEntity<PartnerRegisterResponse> registerPartner(
            @Valid @RequestBody PartnerRegisterRequest request
    ) {

        PartnerRegisterResponse response =
                authService.registerPartner(request);

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(response);
    }

    @PostMapping("/refresh")
    public ResponseEntity<LoginResponse> refresh(
            @Valid @RequestBody RefreshRequest request
    ) {

        return ResponseEntity.ok(
                authService.refresh(request)
        );
    }

    /**
     * Answers 204 whether or not the token was still live: a caller signing
     * out does not need to know, and it saves the client handling a failure
     * on the way out the door.
     */
    @PostMapping("/logout")
    public ResponseEntity<Void> logout(
            @Valid @RequestBody RefreshRequest request
    ) {

        authService.logout(request);

        return ResponseEntity.noContent().build();
    }
}
