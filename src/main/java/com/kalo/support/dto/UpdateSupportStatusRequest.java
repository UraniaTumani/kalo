package com.kalo.support.dto;

import com.kalo.support.enums.SupportStatus;
import jakarta.validation.constraints.NotNull;

public record UpdateSupportStatusRequest(

        @NotNull(
                message = "Status is required"
        )
        SupportStatus status

) {
}
