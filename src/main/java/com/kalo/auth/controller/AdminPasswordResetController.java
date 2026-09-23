package com.kalo.auth.controller;

import com.kalo.auth.dto.IssuedResetCodeResponse;
import com.kalo.auth.dto.PasswordResetQueueItem;
import com.kalo.auth.service.PasswordResetService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The queue where a password recovery is actually verified.
 *
 * Under /api/v1/admin, so SecurityConfig's existing ADMIN rule covers it — the
 * whole flow hinges on only an administrator being able to mint a code, and
 * that guarantee should come from the same place as every other admin rule
 * rather than from an annotation somebody could forget to copy.
 */
@Tag(name = "Admin password resets")
@RestController
@RequestMapping("/api/v1/admin/password-resets")
@RequiredArgsConstructor
public class AdminPasswordResetController {

    private final PasswordResetService passwordResetService;

    @GetMapping
    public ResponseEntity<Page<PasswordResetQueueItem>> pending(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {

        return ResponseEntity.ok(
                passwordResetService.pendingRequests(PageRequest.of(page, size))
        );
    }

    /**
     * Mints the code, after the administrator has rung the number on the
     * account and satisfied themselves who they are talking to.
     *
     * The response carries the code exactly once and it is never recoverable
     * afterwards — only a bcrypt hash is kept. Losing it means issuing another.
     */
    @PostMapping("/{requestId}/issue")
    public ResponseEntity<IssuedResetCodeResponse> issue(
            @PathVariable Long requestId
    ) {

        return ResponseEntity.ok(passwordResetService.issueCode(requestId));
    }

    /** For a call that did not check out. */
    @PostMapping("/{requestId}/reject")
    public ResponseEntity<Void> reject(@PathVariable Long requestId) {

        passwordResetService.rejectRequest(requestId);

        return ResponseEntity.noContent().build();
    }
}
