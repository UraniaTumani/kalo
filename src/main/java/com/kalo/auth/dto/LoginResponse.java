package com.kalo.auth.dto;

public record LoginResponse(
        String accessToken,
        String tokenType
) {
}