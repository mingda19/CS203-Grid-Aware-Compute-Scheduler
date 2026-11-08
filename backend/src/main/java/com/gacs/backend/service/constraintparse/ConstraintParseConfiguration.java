package com.gacs.backend.service.constraintparse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * Registers the TypeSafe classifier, but only when a key is configured.
 *
 * <p>With no key there is no bean at all, rather than a bean that throws on first use. Constraint
 * parsing then runs entirely on {@link ConstraintParser}'s deterministic readers, which is the
 * supported configuration for local development and for every test: nothing in the suite reaches
 * the network, and a missing key can never turn into a billed call or a failed save.
 */
@Configuration
@EnableConfigurationProperties(JevProperties.class)
public class ConstraintParseConfiguration {

    private static final Logger logger = LoggerFactory.getLogger(ConstraintParseConfiguration.class);

    /**
     * The reader's floor is the guardrail's {@code reviewAbove}: a proposal the guardrail would
     * drop unseen is not worth building in the first place, and two separate cut-offs would drift.
     */
    @Bean
    public JevAnswerReader jevAnswerReader(JevProperties properties) {
        return new JevAnswerReader(properties.reviewAbove());
    }

    @Bean
    public ConstraintGuardrail constraintGuardrail(JevProperties properties) {
        return new ConstraintGuardrail(properties.applyAbove(), properties.reviewAbove());
    }

    /**
     * Null when no key is set - Spring skips a {@code @Bean} method returning null, so the
     * classifier is simply absent. Injection points must therefore be {@code Optional} or
     * {@code @Autowired(required = false)}.
     *
     * <p>The timeout is set on the request factory rather than left to the JDK default, which is
     * no timeout at all: a hung connection would otherwise hold an operator's save request open
     * indefinitely.
     */
    @Bean
    public ConstraintClassifier constraintClassifier(JevProperties properties, JevAnswerReader reader) {
        if (!properties.configured()) {
            logger.info("TYPESAFE_API_KEY is not set; constraint parsing will use the rule-based parser only");
            return null;
        }
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory();
        requestFactory.setReadTimeout(properties.timeout());
        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory);
        logger.info("TypeSafe classifier enabled, model {} at {}", properties.model(), properties.endpoint());
        return new JevConstraintClassifier(builder, properties, reader);
    }
}
