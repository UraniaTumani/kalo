package com.kalo.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record RejectFleetDocumentRequest(@NotBlank @Size(max = 1000) String reason) {}
