package com.kalo.partner.dto;

public record ServiceAreaResponse(

        Long companyId,

        Double latitude,

        Double longitude,

        Double radiusKm,

        String timezone

) {
}