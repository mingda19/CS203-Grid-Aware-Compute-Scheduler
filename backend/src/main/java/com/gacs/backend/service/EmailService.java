package com.gacs.backend.service;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    @Autowired(required = false)
    private JavaMailSender mailSender;

    @Value("${spring.mail.username:noreply@gacs-platform.io}")
    private String fromEmail;

    public void sendOtpEmail(String toEmail, String otpCode) {
        logger.info("Preparing OTP verification email for {}", toEmail);

        if (mailSender == null) {
            logger.warn("JavaMailSender is not initialized. OTP code for {} is: {}", toEmail, otpCode);
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");

            helper.setFrom(fromEmail, "GACS Platform");
            helper.setReplyTo(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("Your GACS Verification Code: " + otpCode);

            String plainText = "Hello,\n\n"
                    + "Thank you for registering on the GACS Platform (Grid Aware Compute Scheduler).\n\n"
                    + "Your One-Time Password (OTP) verification code is: " + otpCode + "\n\n"
                    + "This code is valid for 10 minutes. If you did not request this registration, please disregard this message.\n\n"
                    + "Best regards,\n"
                    + "The GACS Platform Team";

            String htmlContent = "<div style=\"font-family: Arial, sans-serif; max-width: 520px; margin: 0 auto; padding: 24px; border: 1px solid #e2e8f0; border-radius: 12px; background-color: #ffffff;\">"
                    + "<div style=\"text-align: center; margin-bottom: 20px;\">"
                    + "<h2 style=\"color: #059669; margin: 0;\">GACS Platform</h2>"
                    + "<p style=\"color: #64748b; font-size: 13px; margin-top: 4px;\">Grid Aware Compute Scheduler</p>"
                    + "</div>"
                    + "<p style=\"font-size: 15px; color: #1e293b;\">Hello,</p>"
                    + "<p style=\"font-size: 14px; color: #334155; line-height: 1.5;\">Thank you for registering on the GACS Platform. Please use the following One-Time Password (OTP) to verify your account and complete registration:</p>"
                    + "<div style=\"text-align: center; margin: 28px 0;\">"
                    + "<span style=\"display: inline-block; font-size: 32px; font-weight: 700; letter-spacing: 6px; color: #0f172a; background-color: #f1f5f9; padding: 12px 24px; border-radius: 8px; border: 1px dashed #cbd5e1; font-family: monospace;\">"
                    + otpCode
                    + "</span>"
                    + "</div>"
                    + "<p style=\"font-size: 13px; color: #64748b;\">This code is valid for <strong>10 minutes</strong>. If you did not request this registration, please disregard this message.</p>"
                    + "<hr style=\"border: none; border-top: 1px solid #f1f5f9; margin: 24px 0;\" />"
                    + "<p style=\"font-size: 11px; color: #94a3b8; text-align: center; margin: 0;\">GACS Platform • ERCOT Dispatch Control • Automated System Email</p>"
                    + "</div>";

            // Set both plain text and HTML for maximum anti-spam deliverability
            helper.setText(plainText, htmlContent);
            message.setHeader("X-Mailer", "GACS-Platform");
            mailSender.send(message);
            logger.info("Successfully sent OTP email to {}", toEmail);

        } catch (MessagingException e) {
            logger.error("Failed to send OTP email to {}. Error: {}", toEmail, e.getMessage());
            // Fallback log for development/testing when SMTP credentials are being configured
            logger.warn("FALLBACK OTP for {}: {}", toEmail, otpCode);
            throw new RuntimeException("Failed to send OTP verification email. Please verify SMTP credentials or check server logs.");
        } catch (Exception e) {
            logger.error("Unexpected error sending email: {}", e.getMessage());
            logger.warn("FALLBACK OTP for {}: {}", toEmail, otpCode);
            throw new RuntimeException("Failed to send email: " + e.getMessage());
        }
    }
}
