package com.kalo.auth.dto;

/**
 * @param accessToken  short-lived; sent on every request
 * @param refreshToken long-lived; exchanged for a new access token and rotated
 *                     each time it is used
 * @param expiresIn    access token lifetime in seconds, so the client can
 *                     refresh before a request fails rather than after
 */
public record LoginResponse(
        String accessToken,
        String refreshToken,
        String tokenType,
        long expiresIn
) {
}
