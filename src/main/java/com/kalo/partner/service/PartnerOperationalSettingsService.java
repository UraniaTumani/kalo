package com.kalo.partner.service;

import com.kalo.partner.dto.OperationalSettingsResponse;
import com.kalo.partner.dto.UpdateOperationalSettingsRequest;

public interface PartnerOperationalSettingsService {

    OperationalSettingsResponse getSettings();

    OperationalSettingsResponse updateSettings(
            UpdateOperationalSettingsRequest request
    );
}