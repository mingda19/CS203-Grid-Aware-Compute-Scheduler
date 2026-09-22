package com.gacs.backend.service;

import com.gacs.backend.dto.*;
import com.gacs.backend.model.OtpVerification;
import com.gacs.backend.model.User;
import com.gacs.backend.repository.OtpVerificationRepository;
import com.gacs.backend.repository.UserRepository;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;

@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom secureRandom = new SecureRandom();
    private static final int OTP_EXPIRY_MINUTES = 10;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OtpVerificationRepository otpRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private EmailService emailService;

    /**
     * Registers a new user or refreshes registration for an unverified account.
     * Encrypts the password using BCrypt before storing in the database.
     * Generates a 6-digit OTP and sends it to the user's email via SMTP.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.getEmail().trim().toLowerCase();

        Optional<User> existingUserOpt = userRepository.findByEmail(email);
        if (existingUserOpt.isPresent()) {
            User existingUser = existingUserOpt.get();
            if (existingUser.isVerified()) {
                throw new IllegalArgumentException("An account with this email already exists. Please log in.");
            }
            // User exists but was not yet verified: update password (encrypted) and details
            existingUser.setPassword(passwordEncoder.encode(request.getPassword()));
            if (request.getFullName() != null && !request.getFullName().trim().isEmpty()) {
                existingUser.setFullName(request.getFullName().trim());
            }
            existingUser.setRole("ROLE_USER");
            userRepository.save(existingUser);
        } else {
            // New user registration - encrypt password with BCrypt, always assign ROLE_USER
            String encryptedPassword = passwordEncoder.encode(request.getPassword());
            User newUser = new User(email, encryptedPassword, request.getFullName(), "ROLE_USER");
            newUser.setVerified(false);
            userRepository.save(newUser);
        }

        // Generate and send OTP
        String otpCode = generateAndSaveOtp(email);
        try {
            emailService.sendOtpEmail(email, otpCode);
        } catch (Exception e) {
            logger.warn("Could not deliver OTP email: {}. OTP code for development is: {}", e.getMessage(), otpCode);
        }

        return AuthResponse.otpRequired("Registration initiated. A 6-digit OTP has been sent to " + email + ". Please verify to complete registration.", email);
    }

    /**
     * Verifies the submitted OTP code and marks the user's account as verified.
     */
    @Transactional
    public AuthResponse verifyOtp(VerifyOtpRequest request) {
        String email = request.getEmail().trim().toLowerCase();
        String code = request.getOtp().trim();

        OtpVerification otp = otpRepository.findTopByEmailAndOtpCodeAndIsUsedFalseOrderByCreatedAtDesc(email, code)
                .orElseThrow(() -> new IllegalArgumentException("Invalid OTP verification code."));

        if (otp.isExpired()) {
            throw new IllegalArgumentException("OTP verification code has expired. Please request a new OTP.");
        }

        // Mark OTP as used
        otp.setUsed(true);
        otpRepository.save(otp);

        // Mark user as verified
        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("User account not found."));
        user.setVerified(true);
        User savedUser = userRepository.save(user);

        return AuthResponse.success("Account successfully verified! You may now log in.", new UserDto(savedUser));
    }

    /**
     * Resends a new OTP to the specified email.
     */
    @Transactional
    public ApiResponse<String> resendOtp(ResendOtpRequest request) {
        String email = request.getEmail().trim().toLowerCase();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new IllegalArgumentException("No account found for this email."));

        if (user.isVerified()) {
            return ApiResponse.ok("Account is already verified. Please proceed to login.");
        }

        String otpCode = generateAndSaveOtp(email);
        try {
            emailService.sendOtpEmail(email, otpCode);
        } catch (Exception e) {
            logger.warn("Could not deliver OTP email: {}. OTP code for development is: {}", e.getMessage(), otpCode);
        }

        return ApiResponse.ok("A new OTP code has been sent to " + email + ".");
    }

    /**
     * Authenticates the user with BCrypt password verification and checks OTP status.
     */
    public AuthResponse login(LoginRequest request, HttpServletRequest httpRequest) {
        String email = request.getEmail().trim().toLowerCase();

        User user = userRepository.findByEmail(email)
                .orElseThrow(() -> new BadCredentialsException("Invalid email or password."));

        // Verify password against BCrypt hash
        if (!passwordEncoder.matches(request.getPassword(), user.getPassword())) {
            throw new BadCredentialsException("Invalid email or password.");
        }

        // Check if account has completed OTP verification
        if (!user.isVerified()) {
            // Generate and send a fresh OTP to help them verify
            String otpCode = generateAndSaveOtp(email);
            try {
                emailService.sendOtpEmail(email, otpCode);
            } catch (Exception e) {
                logger.warn("Could not deliver OTP email: {}. OTP code is: {}", e.getMessage(), otpCode);
            }
            return AuthResponse.otpRequired("Account is not yet verified. A fresh OTP has been sent to your email.", email);
        }

        // Set authenticated user in Spring Security Context
        String rawRole = user.getRole();
        String authorityName;
        if (rawRole != null && rawRole.startsWith("ROLE_")) {
            authorityName = rawRole;
        } else if (rawRole != null && !rawRole.trim().isEmpty()) {
            authorityName = "ROLE_" + rawRole.toUpperCase().replace(" ", "_");
        } else {
            authorityName = "ROLE_USER";
        }

        UsernamePasswordAuthenticationToken authToken = new UsernamePasswordAuthenticationToken(
                user.getEmail(),
                null,
                Collections.singletonList(new SimpleGrantedAuthority(authorityName))
        );
        SecurityContext context = SecurityContextHolder.createEmptyContext();
        context.setAuthentication(authToken);
        SecurityContextHolder.setContext(context);

        if (httpRequest != null) {
            HttpSession session = httpRequest.getSession(true);
            session.setAttribute(HttpSessionSecurityContextRepository.SPRING_SECURITY_CONTEXT_KEY, context);
        }

        return AuthResponse.success("Login successful.", new UserDto(user));
    }

    /**
     * Logs out the user by clearing SecurityContext and invalidating HTTP session.
     */
    public ApiResponse<Void> logout(HttpServletRequest httpRequest) {
        SecurityContextHolder.clearContext();
        if (httpRequest != null) {
            HttpSession session = httpRequest.getSession(false);
            if (session != null) {
                session.invalidate();
            }
        }
        return ApiResponse.ok("Logged out successfully.");
    }

    /**
     * Helper to invalidate existing unused OTPs and generate a fresh 6-digit numeric OTP.
     */
    private String generateAndSaveOtp(String email) {
        // Invalidate prior unused OTPs
        List<OtpVerification> existingOtps = otpRepository.findAllByEmailAndIsUsedFalse(email);
        for (OtpVerification prior : existingOtps) {
            prior.setUsed(true);
        }
        otpRepository.saveAll(existingOtps);

        // Generate 6-digit number string
        int randomPin = 100_000 + secureRandom.nextInt(900_000);
        String otpCode = String.valueOf(randomPin);

        OtpVerification otp = new OtpVerification(
                email,
                otpCode,
                LocalDateTime.now().plusMinutes(OTP_EXPIRY_MINUTES)
        );
        otpRepository.save(otp);
        return otpCode;
    }
}
