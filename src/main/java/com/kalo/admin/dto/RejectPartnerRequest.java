package com.kalo.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectPartnerRequest(

        @NotBlank(message = "Rejection reason is required")
        @Size(
                max = 1000,
                message = "Rejection reason cannot exceed 1000 characters"
        )
        String reason

) {
}