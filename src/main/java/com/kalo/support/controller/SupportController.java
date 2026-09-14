package com.kalo.support.controller;

import com.kalo.support.dto.CreateSupportRequestRequest;
import com.kalo.support.dto.SupportRequestResponse;
import com.kalo.support.service.SupportRequestService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Support for whoever is signed in.
 *
 * SecurityConfig maps no role to this prefix, so it falls through to
 * `anyRequest().authenticated()` — customers and partners both reach it, and
 * an anonymous caller does not. Neither endpoint takes a user id: the caller
 * is read from the token, so one user cannot address another user's tickets.
 */
@Tag(name = "Support")
@RestController
@RequestMapping("/api/v1/support/requests")
@RequiredArgsConstructor
public class SupportController {

    private final SupportRequestService supportRequestService;

    @PostMapping
    public ResponseEntity<SupportRequestResponse>
    createRequest(
            @Valid
            @RequestBody
            CreateSupportRequestRequest request
    ) {

        return ResponseEntity
                .status(HttpStatus.CREATED)
                .body(
                        supportRequestService
                                .createForCurrentUser(request)
                );
    }

    @GetMapping
    public ResponseEntity<Page<SupportRequestResponse>>
    getMyRequests(
            @PageableDefault(
                    size = 20,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC
            )
            Pageable pageable
    ) {

        return ResponseEntity.ok(
                supportRequestService.getMyRequests(pageable)
        );
    }
}
