package com.kalo.admin.service;

import com.kalo.admin.dto.AdminPartnerDetailResponse;
import com.kalo.admin.dto.AdminPartnerResponse;
import com.kalo.admin.dto.PartnerDecisionResponse;
import com.kalo.admin.dto.RejectPartnerRequest;
import com.kalo.partner.enums.VerificationStatus;

import java.util.List;

public interface AdminPartnerService {

    List<AdminPartnerResponse> getPartners(
            VerificationStatus status
    );

    AdminPartnerDetailResponse getPartnerById(
            Long companyId
    );

    PartnerDecisionResponse approvePartner(
            Long companyId
    );

    PartnerDecisionResponse rejectPartner(
            Long companyId,
            RejectPartnerRequest request
    );
}