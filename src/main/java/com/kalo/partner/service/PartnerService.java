package com.kalo.partner.service;

import com.kalo.partner.dto.PartnerProfileResponse;
import com.kalo.partner.dto.PartnerVerificationResponse;
import com.kalo.partner.dto.UpdatePartnerProfileRequest;

public interface PartnerService {

    PartnerProfileResponse getCurrentPartnerProfile();

    PartnerProfileResponse updateCurrentPartnerProfile(
            UpdatePartnerProfileRequest request
    );

    PartnerVerificationResponse submitForVerification();
}