package com.kalo.support.controller;

import com.kalo.support.dto.AdminSupportRequestResponse;
import com.kalo.support.dto.UpdateSupportStatusRequest;
import com.kalo.support.enums.SupportStatus;
import com.kalo.support.service.SupportRequestService;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * The triage queue. Under /api/v1/admin/**, which SecurityConfig already
 * restricts to ADMIN, so this is the only place a support request is seen
 * alongside who sent it.
 */
@Tag(name = "Admin")
@RestController
@RequestMapping("/api/v1/admin/support/requests")
@RequiredArgsConstructor
public class AdminSupportController {

    private final SupportRequestService supportRequestService;

    @GetMapping
    public ResponseEntity<Page<AdminSupportRequestResponse>>
    getRequests(
            @RequestParam(required = false)
            SupportStatus status,

            @PageableDefault(
                    size = 20,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC
            )
            Pageable pageable
    ) {

        return ResponseEntity.ok(
                supportRequestService.getAllRequests(
                        status,
                        pageable
                )
        );
    }

    @PatchMapping("/{requestId}/status")
    public ResponseEntity<AdminSupportRequestResponse>
    updateStatus(
            @PathVariable Long requestId,

            @Valid
            @RequestBody
            UpdateSupportStatusRequest request
    ) {

        return ResponseEntity.ok(
                supportRequestService.updateStatus(
                        requestId,
                        request.status()
                )
        );
    }
}
