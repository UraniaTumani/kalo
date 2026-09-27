package com.kalo.auth.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.kalo.auth.dto.CustomerRegisterRequest;
import com.kalo.auth.dto.ForgotPasswordRequest;
import com.kalo.auth.dto.ResetPasswordRequest;
import com.kalo.auth.dto.UserResponse;
import com.kalo.auth.service.AuthService;
import com.kalo.auth.service.PasswordResetService;
import com.kalo.common.util.ConstantTime;
import jakarta.validation.Valid;

import java.time.Duration;
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

    /**
     * How long the recovery request always takes.
     *
     * Comfortably above the real work -- a handful of simple statements,
     * tens of milliseconds -- so the floor is what a caller measures rather
     * than the queries. High enough to bury the difference, low enough that
     * a person asking for help does not notice it.
     */
    private static final Duration FORGOT_PASSWORD_FLOOR = Duration.ofMillis(250);

    private final AuthService authService;
    private final PasswordResetService passwordResetService;

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


    /**
     * Opens a password recovery.
     *
     * Always 202, always the same empty body, whether or not the number
     * belongs to an account — and whether or not that account is suspended or
     * has asked too often lately. Every one of those distinctions is a fact
     * about somebody else's account, and an endpoint anyone may call without
     * signing in must not be a way of learning them.
     */
    @PostMapping("/password/forgot")
    public ResponseEntity<Void> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest request
    ) {

        /*
         * Held to a fixed duration, because the body is only half of what a
         * caller can see. An unknown number costs one SELECT; a registered one
         * costs a lookup, a count, an update and an insert. Identical answers
         * arriving at reliably different times still say which numbers are
         * registered, given enough samples.
         *
         * In the finally block so a refusal takes as long as an acceptance: a
         * validation failure returning sooner would be the same leak wearing a
         * different coat.
         *
         * Here rather than in the service because the guarantee is about the
         * HTTP response, and because the service method is transactional —
         * waiting inside it would hold a pooled connection for the whole floor.
         */
        long startedAt = System.nanoTime();

        try {
            passwordResetService.requestReset(request);
        } finally {
            ConstantTime.holdUntil(startedAt, FORGOT_PASSWORD_FLOOR);
        }

        return ResponseEntity.accepted().build();
    }

    /** Redeems a code an administrator read out, and sets the new password. */
    @PostMapping("/password/reset")
    public ResponseEntity<Void> resetPassword(
            @Valid @RequestBody ResetPasswordRequest request
    ) {

        passwordResetService.resetPassword(request);

        return ResponseEntity.noContent().build();
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
