package com.kalo.admin.controller;

import io.swagger.v3.oas.annotations.tags.Tag;
import com.kalo.admin.dto.AdminUserResponse;
import com.kalo.admin.service.AdminUserService;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Admin")
@RestController
@RequestMapping("/api/v1/admin/users")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    @GetMapping
    public ResponseEntity<Page<AdminUserResponse>>
    getUsers(
            @RequestParam(required = false)
            UserRole role,

            @RequestParam(required = false)
            UserStatus status,

            @PageableDefault(
                    size = 20,
                    sort = "createdAt",
                    direction = Sort.Direction.DESC
            )
            Pageable pageable
    ) {

        return ResponseEntity.ok(
                adminUserService.getUsers(
                        role,
                        status,
                        pageable
                )
        );
    }

    @GetMapping("/{userId}")
    public ResponseEntity<AdminUserResponse>
    getUserById(
            @PathVariable Long userId
    ) {

        return ResponseEntity.ok(
                adminUserService.getUserById(userId)
        );
    }

    @PostMapping("/{userId}/suspend")
    public ResponseEntity<AdminUserResponse>
    suspendUser(
            @PathVariable Long userId
    ) {

        return ResponseEntity.ok(
                adminUserService.suspendUser(userId)
        );
    }

    @PostMapping("/{userId}/reactivate")
    public ResponseEntity<AdminUserResponse>
    reactivateUser(
            @PathVariable Long userId
    ) {

        return ResponseEntity.ok(
                adminUserService.reactivateUser(userId)
        );
    }
}
