package com.kalo.support.service;

import com.kalo.common.exception.ResourceNotFoundException;
import com.kalo.support.dto.AdminSupportRequestResponse;
import com.kalo.support.dto.CreateSupportRequestRequest;
import com.kalo.support.dto.SupportRequestResponse;
import com.kalo.support.entity.SupportRequest;
import com.kalo.support.enums.SupportStatus;
import com.kalo.support.repository.SupportRequestRepository;
import com.kalo.user.entity.User;
import com.kalo.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SupportRequestServiceImpl implements SupportRequestService {

    private final SupportRequestRepository supportRequestRepository;
    private final UserRepository userRepository;

    @Override
    @Transactional
    public SupportRequestResponse createForCurrentUser(
            CreateSupportRequestRequest request
    ) {

        User user = currentUser();

        SupportRequest supportRequest = new SupportRequest();

        supportRequest.setUser(user);

        /*
         * Snapshotted, not derived on read. If this account later becomes a
         * partner, the ticket they raised as a customer should still read as
         * one.
         */
        supportRequest.setRole(user.getRole());

        supportRequest.setCategory(request.category());
        supportRequest.setSubject(request.subject().trim());
        supportRequest.setMessage(request.message().trim());
        supportRequest.setStatus(SupportStatus.OPEN);

        return mapToResponse(
                supportRequestRepository.save(supportRequest)
        );
    }

    @Override
    @Transactional(readOnly = true)
    public Page<SupportRequestResponse> getMyRequests(
            Pageable pageable
    ) {

        /*
         * Keyed by the resolved caller, never by anything off the request. The
         * endpoint takes no user id at all, so there is no parameter to tamper
         * with and no id to enumerate.
         */
        return supportRequestRepository
                .findByUserId(
                        currentUser().getId(),
                        pageable
                )
                .map(this::mapToResponse);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<AdminSupportRequestResponse> getAllRequests(
            SupportStatus status,
            Pageable pageable
    ) {

        return supportRequestRepository
                .findAllByOptionalStatus(status, pageable)
                .map(this::mapToAdminResponse);
    }

    @Override
    @Transactional
    public AdminSupportRequestResponse updateStatus(
            Long requestId,
            SupportStatus status
    ) {

        SupportRequest supportRequest =
                supportRequestRepository
                        .findById(requestId)
                        .orElseThrow(() ->
                                new ResourceNotFoundException(
                                        "Support request not found"
                                )
                        );

        supportRequest.setStatus(status);

        return mapToAdminResponse(
                supportRequestRepository.save(supportRequest)
        );
    }

    private User currentUser() {

        String phone = SecurityContextHolder
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

    private SupportRequestResponse mapToResponse(
            SupportRequest supportRequest
    ) {

        return new SupportRequestResponse(
                supportRequest.getId(),
                supportRequest.getCategory(),
                supportRequest.getSubject(),
                supportRequest.getMessage(),
                supportRequest.getStatus(),
                supportRequest.getCreatedAt(),
                supportRequest.getUpdatedAt()
        );
    }

    private AdminSupportRequestResponse mapToAdminResponse(
            SupportRequest supportRequest
    ) {

        User user = supportRequest.getUser();

        return new AdminSupportRequestResponse(
                supportRequest.getId(),
                user.getId(),
                user.getFirstName(),
                user.getLastName(),
                user.getPhone(),
                user.getEmail(),
                supportRequest.getRole(),
                supportRequest.getCategory(),
                supportRequest.getSubject(),
                supportRequest.getMessage(),
                supportRequest.getStatus(),
                supportRequest.getCreatedAt(),
                supportRequest.getUpdatedAt()
        );
    }
}
