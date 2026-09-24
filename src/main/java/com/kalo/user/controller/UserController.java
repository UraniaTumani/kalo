package com.kalo.user.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.kalo.auth.dto.LoginResponse;
import com.kalo.auth.dto.UserResponse;
import com.kalo.user.dto.ChangePasswordRequest;
import com.kalo.user.dto.UpdateProfileRequest;
import com.kalo.user.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "Authentication")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @GetMapping("/me")
    public ResponseEntity<UserResponse> getCurrentUser() {

        return ResponseEntity.ok(
                userService.getCurrentUser()
        );
    }

    /**
     * Any signed-in user edits their own details here. Phone is not editable:
     * it is the login identifier, so changing it needs a verification flow.
     */
    @PutMapping("/me")
    public ResponseEntity<UserResponse> updateCurrentUser(
            @Valid @RequestBody UpdateProfileRequest request
    ) {

        return ResponseEntity.ok(
                userService.updateCurrentUser(request)
        );
    }

    /**
     * Changes the signed-in user's own password.
     *
     * Returns a new session, because the change drops every existing one —
     * including this caller's. The client should replace both stored tokens
     * with these; anything still holding the old pair is now signed out, which
     * is the point.
     */
    @PostMapping("/me/password")
    public ResponseEntity<LoginResponse> changePassword(
            @Valid @RequestBody ChangePasswordRequest request
    ) {

        return ResponseEntity.ok(
                userService.changePassword(request)
        );
    }
}