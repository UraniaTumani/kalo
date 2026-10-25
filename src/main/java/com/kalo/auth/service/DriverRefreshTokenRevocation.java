package com.kalo.auth.service;
import com.kalo.auth.repository.RefreshTokenRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import java.time.Instant;
/** Commit revocation independently of the refresh request's later 401/rollback. */
@Service
@RequiredArgsConstructor
public class DriverRefreshTokenRevocation {
    private final RefreshTokenRepository refreshTokens;
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void revoke(Long userId) { refreshTokens.revokeAllForUser(userId,Instant.now()); }
}
