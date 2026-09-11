package com.kalo.admin.service;

import com.kalo.admin.dto.AdminPartnerDetailResponse;
import com.kalo.admin.dto.AdminPartnerResponse;
import com.kalo.admin.dto.PartnerDecisionResponse;
import com.kalo.admin.dto.RejectPartnerRequest;
import com.kalo.partner.enums.CompanyStatus;
import com.kalo.partner.enums.VerificationStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AdminPartnerService {

    Page<AdminPartnerResponse> getPartners(
            VerificationStatus status,
            CompanyStatus companyStatus,
            Pageable pageable
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

    PartnerDecisionResponse suspendPartner(
            Long companyId
    );

    PartnerDecisionResponse reactivatePartner(
            Long companyId
    );
}