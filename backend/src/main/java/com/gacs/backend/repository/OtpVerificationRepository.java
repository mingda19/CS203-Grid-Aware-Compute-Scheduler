package com.gacs.backend.repository;

import com.gacs.backend.model.OtpVerification;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface OtpVerificationRepository extends JpaRepository<OtpVerification, Long> {
    Optional<OtpVerification> findTopByEmailAndOtpCodeAndIsUsedFalseOrderByCreatedAtDesc(String email, String otpCode);
    Optional<OtpVerification> findTopByEmailAndIsUsedFalseOrderByCreatedAtDesc(String email);
    List<OtpVerification> findAllByEmailAndIsUsedFalse(String email);
}
