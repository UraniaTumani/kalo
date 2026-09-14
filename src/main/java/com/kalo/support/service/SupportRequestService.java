package com.kalo.support.service;

import com.kalo.support.dto.AdminSupportRequestResponse;
import com.kalo.support.dto.CreateSupportRequestRequest;
import com.kalo.support.dto.SupportRequestResponse;
import com.kalo.support.enums.SupportStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface SupportRequestService {

    /**
     * Files a request as the authenticated caller. There is no overload that
     * takes a user id: the submitter is always whoever holds the token.
     */
    SupportRequestResponse createForCurrentUser(
            CreateSupportRequestRequest request
    );

    /** The caller's own requests, and only ever those. */
    Page<SupportRequestResponse> getMyRequests(
            Pageable pageable
    );

    Page<AdminSupportRequestResponse> getAllRequests(
            SupportStatus status,
            Pageable pageable
    );

    AdminSupportRequestResponse updateStatus(
            Long requestId,
            SupportStatus status
    );
}
