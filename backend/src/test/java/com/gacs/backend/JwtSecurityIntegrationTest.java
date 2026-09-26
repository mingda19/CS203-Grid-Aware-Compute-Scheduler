package com.gacs.backend;

import com.gacs.backend.model.RefreshToken;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.RefreshTokenRepository;
import com.gacs.backend.repository.UserRepository;
import com.gacs.backend.security.JwtUtils;
import com.gacs.backend.service.RefreshTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class JwtSecurityIntegrationTest {

    @Autowired
    private JwtUtils jwtUtils;

    @Autowired
    private RefreshTokenService refreshTokenService;

    @Autowired
    private RefreshTokenRepository refreshTokenRepository;

    @Autowired
    private UserRepository userRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        refreshTokenRepository.deleteAll();
        testUser = userRepository.findByEmail("jwt.tester@datacenter.io")
                .orElseGet(() -> {
                    User u = new User("jwt.tester@datacenter.io", "hashedPass", "JWT Tester", "ROLE_USER");
                    u.setVerified(true);
                    return userRepository.save(u);
                });
    }

    @Test
    void jwtUtils_ShouldGenerateAndValidateTokenSuccessfully() {
        String token = jwtUtils.generateAccessToken(testUser);

        assertNotNull(token);
        assertTrue(jwtUtils.validateToken(token));
        assertEquals("jwt.tester@datacenter.io", jwtUtils.getEmailFromToken(token));
        assertEquals("ROLE_USER", jwtUtils.getRoleFromToken(token));
        assertEquals(testUser.getId(), jwtUtils.getUserIdFromToken(token));
    }

    @Test
    void jwtUtils_ShouldRejectTamperedToken() {
        String token = jwtUtils.generateAccessToken(testUser);
        String tamperedToken = token.substring(0, token.length() - 5) + "abcde";

        assertFalse(jwtUtils.validateToken(tamperedToken));
    }

    @Test
    void refreshToken_ShouldPersistWithCorrectDuration_WhenRememberMeTrue() {
        RefreshToken token = refreshTokenService.createRefreshToken(testUser, true);

        assertNotNull(token.getId());
        assertTrue(token.isRememberMe());
        assertFalse(token.isRevoked());
        assertFalse(token.isExpired());

        // 14 days should be approximately 1209600 seconds from now
        long secondsUntilExpiry = token.getExpiryDate().getEpochSecond() - Instant.now().getEpochSecond();
        assertTrue(secondsUntilExpiry > 1200000 && secondsUntilExpiry <= 1209600);
    }

    @Test
    void refreshToken_ShouldPersistWithCorrectDuration_WhenRememberMeFalse() {
        RefreshToken token = refreshTokenService.createRefreshToken(testUser, false);

        assertNotNull(token.getId());
        assertFalse(token.isRememberMe());

        // 24 hours should be approximately 86400 seconds from now
        long secondsUntilExpiry = token.getExpiryDate().getEpochSecond() - Instant.now().getEpochSecond();
        assertTrue(secondsUntilExpiry > 80000 && secondsUntilExpiry <= 86400);
    }

    @Test
    void refreshToken_Rotation_ShouldInvalidateOldTokenAndIssueNew() {
        RefreshToken initialToken = refreshTokenService.createRefreshToken(testUser, true);
        String initialTokenVal = initialToken.getToken();

        RefreshToken rotatedToken = refreshTokenService.rotateRefreshToken(initialTokenVal);

        assertNotNull(rotatedToken);
        assertNotEquals(initialTokenVal, rotatedToken.getToken());
        assertTrue(rotatedToken.isRememberMe());

        // Verify old token is marked revoked and points to new token
        RefreshToken oldFromDb = refreshTokenRepository.findByToken(initialTokenVal).orElseThrow();
        assertTrue(oldFromDb.isRevoked());
        assertEquals(rotatedToken.getToken(), oldFromDb.getReplacedByToken());
    }

    @Test
    void refreshToken_TheftDetection_ShouldRevokeAllUserTokens_WhenRevokedTokenReplayed() {
        RefreshToken initialToken = refreshTokenService.createRefreshToken(testUser, true);
        String initialTokenVal = initialToken.getToken();

        // Legitimate rotation
        RefreshToken rotatedToken = refreshTokenService.rotateRefreshToken(initialTokenVal);
        assertFalse(rotatedToken.isRevoked());

        // Create another active token (e.g. from second device)
        RefreshToken secondDeviceToken = refreshTokenService.createRefreshToken(testUser, false);
        assertFalse(secondDeviceToken.isRevoked());

        // Attacker replays initialTokenVal (which is already revoked)
        assertThrows(BadCredentialsException.class, () -> refreshTokenService.rotateRefreshToken(initialTokenVal));

        // Automatic theft detection should have revoked ALL tokens for testUser
        List<RefreshToken> activeTokens = refreshTokenRepository.findAllByUserAndRevokedFalse(testUser);
        assertTrue(activeTokens.isEmpty(), "All tokens should be revoked upon replay theft detection!");
    }
}
