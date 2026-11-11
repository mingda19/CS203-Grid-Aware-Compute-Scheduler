package com.gacs.backend;

import com.gacs.backend.service.GmailApiMailSender;
import com.gacs.backend.service.EmailService;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Properties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.ObjectMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;

class GmailApiMailSenderTest {
    private static final String TOKEN = "https://oauth2.googleapis.com/token";
    private static final String SEND = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send";
    private MockRestServiceServer server;
    private GmailApiMailSender sender;

    @BeforeEach
    void setUp() {
        var builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        sender = new GmailApiMailSender(builder, "client", "secret", "refresh");
    }

    private MimeMessage message() throws Exception {
        var message = sender.createMimeMessage();
        var helper = new MimeMessageHelper(message, true, "UTF-8");
        helper.setFrom("sender@gmail.com", "GACS");
        helper.setTo("recipient@example.com");
        helper.setSubject("Verification");
        helper.setText("Code: 123456", "<p>Code: 123456</p>");
        return message;
    }

    private void expectToken(String token, long expires) {
        server.expect(requestTo(TOKEN)).andExpect(method(HttpMethod.POST))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_FORM_URLENCODED))
                .andExpect(content().string("client_id=client&client_secret=secret&refresh_token=refresh&grant_type=refresh_token"))
                .andRespond(withSuccess("{\"access_token\":\"" + token + "\",\"expires_in\":" + expires
                        + ",\"token_type\":\"Bearer\",\"scope\":\"https://www.googleapis.com/auth/gmail.send\"}", MediaType.APPLICATION_JSON));
    }

    @Test
    void refreshesTokenSendsBase64UrlMimeAndReusesToken() throws Exception {
        expectToken("access", 3600);
        server.expect(requestTo(SEND)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer access"))
                .andExpect(request -> assertDoesNotThrow(() -> {
                    String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request)
                            .getBodyAsString(StandardCharsets.UTF_8);
                    String raw = new ObjectMapper().readTree(body).get("raw").asText();
                    assertTrue(raw.matches("[A-Za-z0-9_-]+"));
                    var decoded = new MimeMessage(Session.getInstance(new Properties()),
                            new ByteArrayInputStream(Base64.getUrlDecoder().decode(raw)));
                    assertEquals("Verification", decoded.getSubject());
                    assertEquals("recipient@example.com", decoded.getAllRecipients()[0].toString());
                    assertTrue(decoded.isMimeType("multipart/*"));
                    String mime = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
                    assertTrue(mime.contains("text/plain"));
                    assertTrue(mime.contains("text/html"));
                    assertTrue(mime.contains("123456"));
                })).andRespond(withSuccess("{\"id\":\"sent\"}", MediaType.APPLICATION_JSON));
        server.expect(requestTo(SEND)).andExpect(header("Authorization", "Bearer access"))
                .andRespond(withSuccess());
        sender.send(message());
        sender.send(message());
        server.verify();
    }

    @Test
    void passwordRecoveryUsesGmailApiWithTheRecoveryCode() {
        expectToken("access", 3600);
        server.expect(requestTo(SEND)).andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer access"))
                .andExpect(request -> assertDoesNotThrow(() -> {
                    String body = ((org.springframework.mock.http.client.MockClientHttpRequest) request)
                            .getBodyAsString(StandardCharsets.UTF_8);
                    String raw = new ObjectMapper().readTree(body).get("raw").asText();
                    byte[] bytes = Base64.getUrlDecoder().decode(raw);
                    var decoded = new MimeMessage(Session.getInstance(new Properties()),
                            new ByteArrayInputStream(bytes));
                    assertEquals("Reset your GACS password", decoded.getSubject());
                    assertEquals("recipient@example.com", decoded.getAllRecipients()[0].toString());
                    assertTrue(new String(bytes, StandardCharsets.UTF_8).contains("recovery-test-code"));
                })).andRespond(withSuccess("{\"id\":\"sent\"}", MediaType.APPLICATION_JSON));
        var service = new EmailService();
        ReflectionTestUtils.setField(service, "mailSender", sender);
        ReflectionTestUtils.setField(service, "fromEmail", "sender@gmail.com");

        service.sendPasswordResetEmail("recipient@example.com", "recovery-test-code");

        server.verify();
    }

    @Test
    void refreshesAndRetriesOnceAfterExplicitUnauthorizedResponse() throws Exception {
        expectToken("old", 3600);
        server.expect(requestTo(SEND)).andRespond(withStatus(HttpStatus.UNAUTHORIZED));
        expectToken("new", 3600);
        server.expect(requestTo(SEND)).andExpect(header("Authorization", "Bearer new"))
                .andRespond(withSuccess());
        sender.send(message());
        server.verify();
    }

    @Test
    void refreshesTokenBeforeExpiry() throws Exception {
        expectToken("old", 1);
        server.expect(requestTo(SEND)).andRespond(withSuccess());
        expectToken("new", 3600);
        server.expect(requestTo(SEND)).andExpect(header("Authorization", "Bearer new"))
                .andRespond(withSuccess());
        sender.send(message());
        sender.send(message());
        server.verify();
    }

    @Test
    void rejectsMissingConfigurationWithoutNetwork() throws Exception {
        var missing = new GmailApiMailSender("", "", "");
        var error = assertThrows(IllegalStateException.class, () -> missing.send(message()));
        assertTrue(error.getMessage().contains("not configured"));
        server.verify();
    }

    @Test
    void sanitizesOAuthRejection() throws Exception {
        server.expect(requestTo(TOKEN)).andRespond(withBadRequest()
                .body("invalid_grant sensitive refresh-token"));
        var error = assertThrows(IllegalStateException.class, () -> sender.send(message()));
        assertTrue(error.getMessage().contains("reauthorize"));
        assertFalse(error.getMessage().contains("sensitive"));
        assertNull(error.getCause());
        server.verify();
    }

    @Test
    void doesNotRetryRateLimitOrLeakResponse() throws Exception {
        expectToken("access", 3600);
        server.expect(requestTo(SEND)).andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                .body("sensitive message 123456"));
        var error = assertThrows(IllegalStateException.class, () -> sender.send(message()));
        assertTrue(error.getMessage().contains("limit"));
        assertFalse(error.getMessage().contains("123456"));
        assertNull(error.getCause());
        server.verify();
    }
}
