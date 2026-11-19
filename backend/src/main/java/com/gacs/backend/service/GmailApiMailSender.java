package com.gacs.backend.service;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.Properties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

/** Sends MIME messages over HTTPS; no SMTP connection or app password is used. */
@Component
public class GmailApiMailSender {
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String SEND_URL = "https://gmail.googleapis.com/gmail/v1/users/me/messages/send";
    private final RestClient http;
    private final String clientId;
    private final String clientSecret;
    private final String refreshToken;
    private String accessToken;
    private Instant tokenExpiresAt = Instant.EPOCH;

    @Autowired
    public GmailApiMailSender(
            @Value("${gacs.gmail.client-id:}") String clientId,
            @Value("${gacs.gmail.client-secret:}") String clientSecret,
            @Value("${gacs.gmail.refresh-token:}") String refreshToken) {
        this(httpBuilder(), clientId, clientSecret, refreshToken);
    }

    public GmailApiMailSender(RestClient.Builder builder, String clientId, String clientSecret, String refreshToken) {
        this.http = builder.build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.refreshToken = refreshToken;
    }

    private static RestClient.Builder httpBuilder() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(Duration.ofSeconds(5));
        factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory);
    }

    public MimeMessage createMimeMessage() {
        return new MimeMessage(Session.getInstance(new Properties()));
    }

    public void send(MimeMessage message) {
        if (clientId.isBlank() || clientSecret.isBlank() || refreshToken.isBlank()) {
            throw new IllegalStateException("Gmail API is not configured; set GMAIL_CLIENT_ID, GMAIL_CLIENT_SECRET and GMAIL_REFRESH_TOKEN");
        }
        try {
            message.saveChanges();
            var bytes = new ByteArrayOutputStream();
            message.writeTo(bytes);
            var payload = Map.of("raw", Base64.getUrlEncoder().withoutPadding().encodeToString(bytes.toByteArray()));
            String token = token(false);
            try {
                deliver(payload, token);
            } catch (RestClientResponseException failure) {
                if (failure.getStatusCode().value() != 401) throw failure;
                // Only retry an explicit authentication rejection; retrying an ambiguous
                // timeout/5xx could deliver duplicate verification codes or alerts.
                deliver(payload, token(true));
            }
        } catch (RestClientResponseException failure) {
            int status = failure.getStatusCode().value();
            String reason = switch (status) {
                case 400, 401 -> "Gmail API authentication/request rejected; check OAuth credentials and reauthorize the sender";
                case 403 -> "Gmail API access denied; check API enablement and gmail.send permission";
                case 429 -> "Gmail API sending or rate limit reached; try again later";
                default -> "Gmail API delivery failed; HTTP " + status;
            };
            // Provider bodies may contain credentials, recipient details, or message data.
            throw new IllegalStateException(reason);
        } catch (IllegalStateException failure) {
            throw failure;
        } catch (Exception failure) {
            throw new IllegalStateException("Gmail API delivery failed; check HTTPS connectivity and sender configuration");
        }
    }

    private void deliver(Map<String, String> payload, String token) {
        http.post().uri(SEND_URL).headers(headers -> headers.setBearerAuth(token))
                .contentType(MediaType.APPLICATION_JSON).body(payload).retrieve().toBodilessEntity();
    }

    private synchronized String token(boolean forceRefresh) {
        if (!forceRefresh && accessToken != null && Instant.now().isBefore(tokenExpiresAt)) return accessToken;
        var form = new LinkedMultiValueMap<String, String>();
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);
        form.add("refresh_token", refreshToken);
        form.add("grant_type", "refresh_token");
        var response = http.post().uri(TOKEN_URL).contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form).retrieve().body(TokenResponse.class);
        if (response == null || response.access_token() == null || response.access_token().isBlank()
                || response.expires_in() <= 0) {
            throw new IllegalStateException("Gmail API returned an invalid OAuth token response");
        }
        accessToken = response.access_token();
        tokenExpiresAt = Instant.now().plusSeconds(Math.max(0, response.expires_in() - 60));
        return accessToken;
    }

    private record TokenResponse(String access_token, long expires_in) {}
}
