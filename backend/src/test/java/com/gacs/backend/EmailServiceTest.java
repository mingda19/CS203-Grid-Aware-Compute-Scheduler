package com.gacs.backend;

import com.gacs.backend.service.EmailService;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import com.gacs.backend.service.GmailApiMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class EmailServiceTest {

    @Test
    void recoveryReportsAuthenticationFailureWithoutLeakingProviderMessage() {
        GmailApiMailSender sender = mock(GmailApiMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(sender.createMimeMessage()).thenReturn(message);
        org.mockito.Mockito.doThrow(new IllegalStateException("Gmail API authentication rejected"))
                .when(sender).send(message);
        EmailService service = new EmailService();
        ReflectionTestUtils.setField(service, "mailSender", sender);
        ReflectionTestUtils.setField(service, "fromEmail", "sender@example.com");
        var error = org.junit.jupiter.api.Assertions.assertThrows(IllegalStateException.class,
                () -> service.sendPasswordResetEmail("recipient@example.com", "secret-code"));
        org.junit.jupiter.api.Assertions.assertTrue(error.getMessage().contains("Gmail API authentication rejected"));
        org.junit.jupiter.api.Assertions.assertFalse(error.getMessage().contains("sensitive"));
        org.junit.jupiter.api.Assertions.assertFalse(error.getMessage().contains("secret-code"));
    }

    @Test
    void sendsLowConfidenceAlertToRecipientWithForecastDetails() throws Exception {
        GmailApiMailSender mailSender = mock(GmailApiMailSender.class);
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);

        EmailService emailService = new EmailService();
        ReflectionTestUtils.setField(emailService, "mailSender", mailSender);
        ReflectionTestUtils.setField(emailService, "fromEmail", "alerts@example.com");
        LocalDateTime forecastWindow = LocalDateTime.parse("2026-10-20T12:00:00");

        emailService.sendLowConfidenceForecastEmail(
                "operator@example.com", "LZ_NORTH", forecastWindow, 0.55, 0.7);

        verify(mailSender).send(message);
        assertEquals("Low-confidence electricity forecast for LZ_NORTH", message.getSubject());
        assertEquals("operator@example.com", message.getRecipients(Message.RecipientType.TO)[0].toString());
    }
}
