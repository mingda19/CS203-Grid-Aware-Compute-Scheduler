package com.gacs.backend.service;

import jakarta.mail.internet.MimeMessage;
import java.time.LocalDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

@Service
public class EmailService {

    private static final Logger logger = LoggerFactory.getLogger(EmailService.class);

    @Autowired
    private GmailApiMailSender mailSender;

    @Value("${gacs.gmail.sender:}")
    private String fromEmail;

    public void sendPasswordResetEmail(String toEmail, String token) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail, "GACS");
            helper.setTo(toEmail);
            helper.setSubject("Reset your GACS password");
            String plainText = "Reset your password\n\n"
                    + "Copy the recovery code below and paste it into the GACS password reset form.\n\n"
                    + token + "\n\nThis code expires in 10 minutes and can only be used once."
                    + "\nRequesting a new code replaces this one."
                    + "\n\nIf you did not request this, ignore this email. Your password has not changed.";
            String safeToken = org.springframework.web.util.HtmlUtils.htmlEscape(token);
            String html = """
                    <!doctype html>
                    <html lang="en"><head><meta name="viewport" content="width=device-width, initial-scale=1"></head>
                    <body style="margin:0;padding:0;background:#f4f6f5;color:#172b24;font-family:Arial,Helvetica,sans-serif;">
                      <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="background:#f4f6f5;">
                        <tr><td align="center" style="padding:32px 16px;">
                          <table role="presentation" width="100%%" cellspacing="0" cellpadding="0" style="max-width:520px;background:#ffffff;border:1px solid #dce4df;border-radius:12px;">
                            <tr><td style="padding:28px 28px 20px;border-bottom:1px solid #e5ebe7;">
                              <p style="margin:0;font-size:20px;font-weight:bold;color:#166449;">GACS</p>
                              <p style="margin:6px 0 0;font-size:12px;color:#52645b;">Grid Aware Compute Scheduler</p>
                            </td></tr>
                            <tr><td style="padding:28px;">
                              <h1 style="margin:0 0 12px;font-size:26px;line-height:1.25;letter-spacing:-0.5px;">Reset your password</h1>
                              <p style="margin:0 0 24px;font-size:15px;line-height:1.6;color:#42554b;">Copy this recovery code and paste it into the password reset form in GACS.</p>
                              <p style="margin:0 0 8px;font-size:12px;font-weight:bold;color:#42554b;">Your recovery code</p>
                              <div style="padding:18px;background:#f0f5f2;border:1px solid #d6e3db;border-radius:6px;font-family:Consolas,monospace;font-size:16px;line-height:1.7;overflow-wrap:anywhere;word-break:break-all;">%s</div>
                              <p style="margin:12px 0 24px;font-size:13px;line-height:1.6;color:#52645b;">Expires in <strong>10 minutes</strong>. Use it once.<br>Requesting a new code replaces this one.</p>
                              <p style="margin:0;padding-top:20px;border-top:1px solid #e5ebe7;font-size:13px;line-height:1.6;color:#52645b;">Didn't request a reset? You can ignore this email. Your password has not changed.</p>
                            </td></tr>
                          </table>
                        </td></tr>
                      </table>
                    </body></html>
                    """.formatted(safeToken);
            helper.setText(plainText, html);
            mailSender.send(message);
        } catch (Exception ex) {
            throw deliveryFailure(ex);
        }
    }

    // Never include provider response bodies or verification codes in errors/logs.
    private IllegalStateException deliveryFailure(Exception failure) {
        if (failure instanceof IllegalStateException state) return state;
        return new IllegalStateException("Gmail API message preparation failed; check sender and recipient configuration");
    }

    public void sendOtpEmail(String toEmail, String otpCode) {
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

        } catch (Exception failure) {
            throw deliveryFailure(failure);
        }
    }

    public void sendLowConfidenceForecastEmail(
            String toEmail, String location, LocalDateTime intervalStartUtc,
            double confidenceScore, double threshold) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(fromEmail, "GACS Platform");
            helper.setReplyTo(fromEmail);
            helper.setTo(toEmail);
            helper.setSubject("Low-confidence electricity forecast for " + location);

            // Both already 0-100 percentages (predicted_price.confidence_score) - no *100 here.
            String confidence = String.format(java.util.Locale.ROOT, "%.0f%%", confidenceScore);
            String alertThreshold = String.format(java.util.Locale.ROOT, "%.0f%%", threshold);
            String plainText = "The forecast horizon includes one or more low-confidence intervals.\n\n"
                    + "Location: " + location + "\n"
                    + "First low-confidence window (UTC): " + intervalStartUtc + "\n"
                    + "Confidence: " + confidence + "\n"
                    + "Alert threshold: " + alertThreshold + "\n\n"
                    + "Review the forecast in GACS before relying on this window.\n";
            helper.setText(plainText, "<p>The forecast horizon includes one or more low-confidence intervals.</p>"
                    + "<ul><li>Location: " + escapeHtml(location) + "</li>"
                    + "<li>First low-confidence window (UTC): " + intervalStartUtc + "</li>"
                    + "<li>Confidence: " + confidence + "</li>"
                    + "<li>Alert threshold: " + alertThreshold + "</li></ul>"
                    + "<p>Review the forecast in GACS before relying on this window.</p>");
            message.setHeader("X-Mailer", "GACS-Platform");
            mailSender.send(message);
            logger.info("Sent low-confidence forecast alert to {} for {} at {}", toEmail, location, intervalStartUtc);
        } catch (Exception exception) {
            // A mail outage must not make forecast retrieval fail.
            logger.error("Failed to send low-confidence forecast alert: {}", deliveryFailure(exception).getMessage());
        }
    }

    private String escapeHtml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
    }
}
