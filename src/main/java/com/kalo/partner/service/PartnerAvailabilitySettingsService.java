package com.kalo.partner.service;

import com.kalo.partner.dto.*;

import java.util.List;

public interface PartnerAvailabilitySettingsService {

    ServiceAreaResponse updateServiceArea(
            UpdateServiceAreaRequest request
    );

    ServiceAreaResponse getServiceArea();

    List<OperatingHoursResponse>
    updateOperatingHours(
            UpdateOperatingHoursRequest request
    );

    List<OperatingHoursResponse>
    getOperatingHours();
}