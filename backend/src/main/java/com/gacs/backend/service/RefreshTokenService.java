package com.gacs.backend.service;

import com.gacs.backend.model.RefreshToken;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.RefreshTokenRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class RefreshTokenService {

    private static final Logger logger = LoggerFactory.getLogger(RefreshTokenService.class);

    @Value("${security.jwt.refresh-token-expiration-ms:86400000}")
    private long standardExpirationMs; // 24 hours

    @Value("${security.jwt.remember-me-expiration-ms:1209600000}")
    private long rememberMeExpirationMs; // 14 days

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    /**
     * Creates a new Refresh Token in the database.
     * Uses 14-day validity if rememberMe is true, else 24-hour validity.
     */
    @Transactional
    public RefreshToken createRefreshToken(User user, boolean rememberMe) {
        long durationMs = rememberMe ? rememberMeExpirationMs : standardExpirationMs;
        Instant expiryDate = Instant.now().plusMillis(durationMs);

        String tokenValue = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");

        RefreshToken refreshToken = new RefreshToken(tokenValue, user, expiryDate, rememberMe);
        return refreshTokenRepository.save(refreshToken);
    }

    /**
     * Rotates an existing refresh token (Refresh Token Rotation - RTR).
     * If the token was previously revoked, triggers automatic theft detection
     * and invalidates all tokens for this user family.
     */
    @Transactional
    public RefreshToken rotateRefreshToken(String tokenStr) {
        if (tokenStr == null || tokenStr.trim().isEmpty()) {
            throw new BadCredentialsException("Refresh token is required.");
        }

        RefreshToken oldToken = refreshTokenRepository.findByToken(tokenStr.trim())
                .orElseThrow(() -> new BadCredentialsException("Invalid refresh token."));

        User user = oldToken.getUser();

        // Automatic Theft Detection (Token Replay Mitigation)
        if (oldToken.isRevoked()) {
            logger.warn("SECURITY ALERT: Revoked refresh token reused for user '{}'. Invalidating all active tokens.", user.getEmail());
            revokeAllUserTokens(user);
            throw new BadCredentialsException("Invalid session state detected. Please log in again.");
        }

        // Expiration check
        if (oldToken.isExpired()) {
            oldToken.setRevoked(true);
            refreshTokenRepository.save(oldToken);
            throw new BadCredentialsException("Refresh token has expired. Please log in again.");
        }

        // Token is valid: rotate to a new token
        oldToken.setRevoked(true);
        RefreshToken newToken = createRefreshToken(user, oldToken.isRememberMe());
        oldToken.setReplacedByToken(newToken.getToken());
        refreshTokenRepository.save(oldToken);

        logger.info("Rotated refresh token for user '{}'. Remember-me: {}", user.getEmail(), oldToken.isRememberMe());
        return newToken;
    }

    /**
     * Revokes a specific refresh token (used during user logout).
     */
    @Transactional
    public void revokeRefreshToken(String tokenStr) {
        if (tokenStr == null || tokenStr.trim().isEmpty()) {
            return;
        }
        Optional<RefreshToken> tokenOpt = refreshTokenRepository.findByToken(tokenStr.trim());
        if (tokenOpt.isPresent()) {
            RefreshToken token = tokenOpt.get();
            token.setRevoked(true);
            refreshTokenRepository.save(token);
        }
    }

    /**
     * Revokes all active refresh tokens for a user (used on theft detection or password change).
     */
    @Transactional
    public void revokeAllUserTokens(User user) {
        List<RefreshToken> activeTokens = refreshTokenRepository.findAllByUserAndRevokedFalse(user);
        for (RefreshToken token : activeTokens) {
            token.setRevoked(true);
        }
        refreshTokenRepository.saveAll(activeTokens);
    }

    public long getExpirationSeconds(boolean rememberMe) {
        return (rememberMe ? rememberMeExpirationMs : standardExpirationMs) / 1000;
    }
}
