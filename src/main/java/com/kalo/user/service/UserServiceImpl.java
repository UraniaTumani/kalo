package com.kalo.user.service;

import com.kalo.auth.dto.UserResponse;
import com.kalo.common.exception.ConflictException;
import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.user.dto.UpdateProfileRequest;
import com.kalo.user.entity.User;
import com.kalo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserServiceImpl implements UserService {

    private final UserRepository userRepository;

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
