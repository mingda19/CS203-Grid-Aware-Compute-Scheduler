package com.gacs.backend.service.allocation;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers {@link ExplanationProperties} and, only when an API key is actually configured, the
 * real {@link LlmExplanationClient} bean. A {@code @Bean} method returning {@code null} registers
 * no bean at all - exactly the mechanism {@code EmailService}'s optional {@code JavaMailSender}
 * relies on, so {@link ExplanationGenerator}'s {@code @Autowired(required = false)} field simply
 * stays {@code null} when no key is present, with no conditional annotation needed here.
 */
@Configuration
@EnableConfigurationProperties(ExplanationProperties.class)
public class ExplanationConfiguration {

    @Bean
    public LlmExplanationClient llmExplanationClient(
            @Value("${ANTHROPIC_API_KEY:}") String apiKey, ExplanationProperties properties) {
        if (apiKey == null || apiKey.isBlank()) {
            return null;
        }
        return new AnthropicLlmExplanationClient(apiKey, properties.modelName());
    }
}
