package com.kalo.partner.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record UpdateOperatingHoursRequest(

        @NotEmpty(
                message = "Operating hours are required"
        )
        List<@Valid OperatingHoursRequest> hours

) {
}