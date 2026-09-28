package com.kalo.auth.controller;

import com.kalo.auth.dto.AdminFallbackResetRequest;
import com.kalo.auth.dto.FallbackResetAuditItem;
import com.kalo.auth.dto.IssuedResetCodeResponse;
import com.kalo.auth.service.PasswordResetService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The narrow way back in for somebody who has lost the number itself.
 *
 * This used to be the whole recovery mechanism: a queue of requests, and an
 * administrator telephoning each one. Now it is the exception. SMS recovery
 * handles everybody whose phone still works, and this handles the one case it
 * cannot — a number that no longer reaches anybody, where the alternative is
 * permanently losing the account.
 *
 * Under /api/v1/admin, so SecurityConfig's existing ADMIN rule covers it. That
 * placement is the point: the guarantee that only an administrator can mint a
 * code should come from the same place as every other admin rule rather than
 * from an annotation somebody could forget to copy onto a new method.
 *
 * There is no queue endpoint any more, and deliberately so. A queue invites
 * working through it; this endpoint has to be reached for on purpose, one
 * account at a time, with a written reason.
 */
@Tag(name = "Admin password resets")
@RestController
@RequestMapping("/api/v1/admin/password-resets")
@RequiredArgsConstructor
public class AdminPasswordResetController {

    private final PasswordResetService passwordResetService;

    /**
     * Issues a code by hand, after the administrator has established who they
     * are dealing with by some means other than the phone.
     *
     * The response carries the code exactly once and it is never recoverable
     * afterwards — only a bcrypt hash is kept. Losing it means issuing another.
     *
     * The note is validated, not just stored: fewer than twenty characters is a
     * 400. That is the one piece of friction this endpoint should have.
     */
    @PostMapping("/fallback")
    public ResponseEntity<IssuedResetCodeResponse> issueFallback(
            @Valid @RequestBody AdminFallbackResetRequest request
    ) {

        return ResponseEntity.ok(passwordResetService.issueFallbackCode(request));
    }

    /**
     * The audit trail, newest first.
     *
     * Exposed as an endpoint rather than left in the log files because a record
     * nobody can read is not accountability. Every hand-issued code appears
     * here with who allowed it, when, on what grounds, and what became of it.
     */
    @GetMapping("/fallback-log")
    public ResponseEntity<Page<FallbackResetAuditItem>> fallbackLog(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {

        return ResponseEntity.ok(
                passwordResetService.fallbackAudit(PageRequest.of(page, size))
        );
    }
}
