package com.kalo.user.service;

import com.kalo.auth.dto.UserResponse;
import com.kalo.common.exception.ConflictException;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.auth.dto.LoginResponse;
import com.kalo.auth.security.CustomUserDetailsService;
import com.kalo.auth.security.JwtService;
import com.kalo.auth.service.RefreshTokenService;
import com.kalo.common.exception.InvalidOperationException;
import com.kalo.common.exception.UnauthorizedException;
import com.kalo.user.dto.ChangePasswordRequest;
import com.kalo.user.dto.UpdateProfileRequest;
import com.kalo.user.entity.User;
import com.kalo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final RefreshTokenService refreshTokenService;
    private final JwtService jwtService;
    private final CustomUserDetailsService customUserDetailsService;

    @Override
    @Transactional(readOnly = true)
    public UserResponse getCurrentUser() {

        return mapToResponse(
                getAuthenticatedUser()
        );
    }

    @Override
    @Transactional
    public UserResponse updateCurrentUser(
            UpdateProfileRequest request
    ) {

        User user = getAuthenticatedUser();

        String email =
                request.email() == null || request.email().isBlank()
                        ? null
                        : request.email().trim().toLowerCase();

        if (email != null
                && !email.equalsIgnoreCase(user.getEmail())
                && userRepository.existsByEmail(email)) {

            throw new ConflictException(
                    "Email is already registered"
            );
        }

        user.setFirstName(request.firstName().trim());
        user.setLastName(request.lastName().trim());
        user.setEmail(email);

        User saved = userRepository.save(user);

        log.info(
                "Profile updated: userId={}",
                saved.getId()
        );

        return mapToResponse(saved);
    }

    @Override
    @Transactional
    public LoginResponse changePassword(
            ChangePasswordRequest request
    ) {

        User user = getAuthenticatedUser();

        if (!passwordEncoder.matches(request.currentPassword(), user.getPasswordHash())) {

            /*
             * Named plainly, unlike the recovery flow's deliberately vague
             * refusal. Nothing is being protected by vagueness here: the
             * caller has already proved they hold a session for this account,
             * so "that is not your current password" tells them nothing they
             * could not confirm by signing in again — and telling somebody
             * their password is wrong is the whole job of this message.
             */
            log.warn(
                    "Password change refused, wrong current password: userId={}",
                    user.getId()
            );

            throw new UnauthorizedException("Current password is not correct");
        }

        if (passwordEncoder.matches(request.newPassword(), user.getPasswordHash())) {
            throw new InvalidOperationException(
                    "The new password must be different from the current one"
            );
        }

        user.setPasswordHash(passwordEncoder.encode(request.newPassword()));
        User saved = userRepository.save(user);

        /*
         * Everything, including the session making this request.
         *
         * Changing a password is how somebody removes an intruder, so a
         * refresh token left alive for thirty days would defeat the point.
         * That would sign the caller out a moment later too, which is why a
         * new pair is issued below rather than leaving them to discover it.
         */
        int revoked = refreshTokenService.revokeAllForUser(saved.getId());

        String accessToken = jwtService.generateToken(
                customUserDetailsService.loadUserByUsername(saved.getPhone())
        );

        log.info(
                "Password changed: userId={} sessionsRevoked={}",
                saved.getId(),
                revoked
        );

        return new LoginResponse(
                accessToken,
                refreshTokenService.issue(saved),
                "Bearer",
                jwtService.getExpirationSeconds()
        );
    }

    private User getAuthenticatedUser() {

        String phone =
                SecurityContextHolder
                        .getContext()
                        .getAuthentication()
                        .getName();

        return userRepository
                .findByPhone(phone)
                .orElseThrow(() ->
                        new ResourceNotFoundException(
                                "User not found"
                        )
                );
    }

    private UserResponse mapToResponse(
            User user
    ) {

        return new UserResponse(
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getPhone(),
                user.getEmail(),
                user.getRole(),
                user.getStatus(),
                user.isPhoneVerified()
        );
    }
}
