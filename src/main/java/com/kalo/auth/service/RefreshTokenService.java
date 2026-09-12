package com.kalo.auth.service;

import com.kalo.auth.entity.RefreshToken;
import com.kalo.auth.repository.RefreshTokenRepository;
import com.kalo.user.entity.User;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Issues and rotates the long-lived half of a session.
 *
 * The access token stays short-lived, which is what keeps a stolen one from
 * being useful for long. The refresh token is what stops that shortness from
 * being felt: a driver signs in at the start of a shift and stays signed in
 * through it, while the credential that actually rides on every request is
 * still only minutes old.
 *
 * Every use rotates: the presented token is revoked and a new one returned.
 * That way a token replayed after the real client has already used it is
 * recognised, because it arrives already revoked.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RefreshTokenService {

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 256 bits, which is not guessable and not worth shortening. */
    private static final int TOKEN_BYTES = 32;

    private final RefreshTokenRepository refreshTokenRepository;

    @Value("${app.jwt.refresh-expiration}")
    private long refreshExpirationMillis;

    /**
     * Returns the raw token, which the caller must hand straight to the client:
     * it is not recoverable afterwards, only verifiable.
     */
    @Transactional
    public String issue(User user) {

        byte[] raw = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(raw);

        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        RefreshToken entity = new RefreshToken();
        entity.setUser(user);
        entity.setTokenHash(hash(token));
        entity.setExpiresAt(Instant.now().plus(Duration.ofMillis(refreshExpirationMillis)));

        refreshTokenRepository.save(entity);

        return token;
    }

    /**
     * Consumes a token and issues its replacement, or returns empty when the
     * token is unknown, expired or already used.
     *
     * Deliberately gives the caller no way to tell those cases apart — the
     * difference is only useful to someone probing.
     */
    @Transactional
    public Optional<Rotation> rotate(String presentedToken) {

        if (presentedToken == null || presentedToken.isBlank()) {
            return Optional.empty();
        }

        Instant now = Instant.now();

        return refreshTokenRepository
                .findByTokenHash(hash(presentedToken))
                .filter(existing -> {

                    if (existing.isUsable(now)) {
                        return true;
                    }

                    /*
                     * A revoked token arriving again is either a replay or a
                     * client that raced itself. Worth a line in the log, since
                     * the first is an attack and the second is a bug.
                     */
                    if (existing.getRevokedAt() != null) {
                        log.warn(
                                "Refresh token reuse after revocation: userId={}",
                                existing.getUser().getId()
                        );
                    }

                    return false;
                })
                .map(existing -> {

                    existing.setRevokedAt(now);
                    refreshTokenRepository.save(existing);

                    User user = existing.getUser();

                    return new Rotation(user, issue(user));
                });
    }

    /** Signing out revokes only the session that signed out. */
    @Transactional
    public void revoke(String presentedToken) {

        if (presentedToken == null || presentedToken.isBlank()) {
            return;
        }

        refreshTokenRepository
                .findByTokenHash(hash(presentedToken))
                .filter(token -> token.getRevokedAt() == null)
                .ifPresent(token -> {
                    token.setRevokedAt(Instant.now());
                    refreshTokenRepository.save(token);
                });
    }

    /**
     * Drops every session a user holds. An access token is checked against the
     * user's status on each request, so suspension already stops those within
     * the hour; this closes the longer-lived half.
     */
    @Transactional
    public int revokeAllForUser(Long userId) {
        return refreshTokenRepository.revokeAllForUser(userId, Instant.now());
    }

    /**
     * SHA-256 rather than bcrypt: the input is 256 bits of machine-generated
     * randomness, so there is nothing to brute-force and nothing gained from a
     * slow hash — while this one is checked on every refresh.
     */
    private String hash(String token) {

        try {

            MessageDigest digest = MessageDigest.getInstance("SHA-256");

            return HexFormat.of().formatHex(
                    digest.digest(token.getBytes(StandardCharsets.UTF_8))
            );

        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required but unavailable", exception);
        }
    }

    /** The user the token belonged to, and the replacement token. */
    public record Rotation(User user, String refreshToken) {
    }
}
