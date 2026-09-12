package com.kalo.admin.service;

import com.kalo.admin.dto.AdminUserResponse;
import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.user.entity.User;
import com.kalo.user.enums.UserRole;
import com.kalo.user.enums.UserStatus;
import com.kalo.auth.service.RefreshTokenService;
import com.kalo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AdminUserServiceImpl
        implements AdminUserService {

    private final UserRepository userRepository;
    private final RefreshTokenService refreshTokenService;

    @Override
    @Transactional(readOnly = true)
    public Page<AdminUserResponse> getUsers(
            UserRole role,
            UserStatus status,
            Pageable pageable
    ) {

        return userRepository
                .findAllByOptionalRoleAndStatus(
                        role,
                        status,
                        pageable
                )
                .map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public AdminUserResponse getUserById(
            Long userId
    ) {

        return mapToResponse(
                getUser(userId)
        );
    }

    /**
     * Suspension is reversible and keeps every historical ride, rating and
     * company relationship intact. A suspended user can no longer authenticate
     * and both halves of an existing session are dropped: access tokens fail on
     * the next request, and every refresh token is revoked.
     */
    @Override
    @Transactional
    public AdminUserResponse suspendUser(
            Long userId
    ) {

        User user = getUser(userId);

        if (user.getPhone().equals(currentUserPhone())) {

            throw new InvalidOperationException(
                    "You cannot suspend your own account"
            );
        }

        if (user.getStatus() == UserStatus.SUSPENDED) {

            throw new InvalidOperationException(
                    "User is already suspended"
            );
        }

        user.setStatus(UserStatus.SUSPENDED);

        User saved = userRepository.save(user);

        /*
         * Access tokens die on the next request because the filter reloads the
         * user, but a refresh token would otherwise keep minting new ones for
         * thirty days. Suspension has to reach both halves of the session.
         */
        int revoked = refreshTokenService.revokeAllForUser(saved.getId());

        log.info(
                "User suspended by admin: userId={} role={} sessionsRevoked={}",
                saved.getId(),
                saved.getRole(),
                revoked
        );

        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public AdminUserResponse reactivateUser(
            Long userId
    ) {

        User user = getUser(userId);

        if (user.getStatus() == UserStatus.ACTIVE) {

            throw new InvalidOperationException(
                    "User is already active"
            );
        }

        user.setStatus(UserStatus.ACTIVE);

        User saved = userRepository.save(user);

        log.info(
                "User reactivated by admin: userId={} role={}",
                saved.getId(),
                saved.getRole()
        );

        return mapToResponse(saved);
    }

    private User getUser(
            Long userId
    ) {

        return userRepository
                .findById(userId)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "User not found"
                        )
                );
    }

    private String currentUserPhone() {

        return SecurityContextHolder
                .getContext()
                .getAuthentication()
                .getName();
    }

    private AdminUserResponse mapToResponse(
            User user
    ) {

        return new AdminUserResponse(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getPhone(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.isPhoneVerified(),
                user.getCreatedAt()
        );
    }
}
