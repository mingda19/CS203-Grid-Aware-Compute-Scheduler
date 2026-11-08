package com.gacs.backend.service.constraintparse;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whether the classifier is actually wired into the application.
 *
 * <p>This exists because it was not, and nothing failed. Every class in this package was built and
 * unit-tested while no bean injected {@link ConstraintClassifier}, so the feature was complete,
 * green and entirely unreachable - a review caught it, not the suite. These tests assert the
 * connection itself, which is the part no amount of testing the components can cover.
 */
class ConstraintParseConfigurationTest {

    // No autoconfiguration: the configuration builds its own RestClient, so the only thing under
    // test is the configuration itself and the condition on the key.
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ConstraintParseConfiguration.class);

    /**
     * Holds whatever the container decided to inject, which is the thing actually under test.
     *
     * <p>Asserted through an {@code Optional} injection point rather than
     * {@code assertThat(context).doesNotHaveBean(...)}, because those two do not agree here. A
     * {@code @Bean} method that returns null still leaves a bean definition matching its declared
     * return type, so a type lookup finds it; Spring substitutes a {@code NullBean} and dependency
     * resolution treats it as absent. {@link ConstraintParseService} takes an {@code Optional}, so
     * what matters is what that Optional contains - which is what this checks.
     */
    static class Consumer {
        final java.util.Optional<ConstraintClassifier> classifier;

        Consumer(java.util.Optional<ConstraintClassifier> classifier) {
            this.classifier = classifier;
        }
    }

    @Test
    void withAKeyConfigured_TheClassifierIsInjected() {
        runner.withBean(Consumer.class).withPropertyValues("gacs.constraintparse.jev.api-key=test-key")
                .run(context -> {
                    assertThat(context.getBean(Consumer.class).classifier)
                            .containsInstanceOf(JevConstraintClassifier.class);
                    assertThat(context).hasSingleBean(ConstraintGuardrail.class);
                });
    }

    @Test
    void withNoKey_NoClassifierIsInjected() {
        // Absent rather than present-and-throwing, so nothing can accidentally make a billed call
        // from a machine that was never configured for one.
        runner.withBean(Consumer.class)
                .run(context -> assertThat(context.getBean(Consumer.class).classifier).isEmpty());
    }

    @Test
    void withABlankKey_NoClassifierIsInjectedEither() {
        // What an unset TYPESAFE_API_KEY actually produces, since application.properties resolves
        // it to the empty string rather than leaving the property missing.
        runner.withBean(Consumer.class).withPropertyValues("gacs.constraintparse.jev.api-key=")
                .run(context -> assertThat(context.getBean(Consumer.class).classifier).isEmpty());
    }

    @Test
    void theGuardrailExistsEvenWithNoClassifier() {
        // The service treats a classifier without a guardrail as a misconfiguration and fails
        // closed; this makes sure the real configuration never lands in that state.
        runner.run(context -> assertThat(context).hasSingleBean(ConstraintGuardrail.class));
    }

    @Test
    void theModelIsPinnedByDefault() {
        // A silent model upgrade would move every probability under the tuned thresholds.
        runner.withPropertyValues("gacs.constraintparse.jev.api-key=test-key")
                .run(context -> assertThat(context.getBean(JevProperties.class).model())
                        .isEqualTo("jev-1.14.0"));
    }

    @Test
    void theReviewBandDefaultsToTheRangeTheOperatorAsksAbout() {
        runner.withPropertyValues("gacs.constraintparse.jev.api-key=test-key")
                .run(context -> {
                    JevProperties properties = context.getBean(JevProperties.class);
                    assertThat(properties.reviewAbove()).isEqualTo(0.3);
                    assertThat(properties.applyAbove()).isEqualTo(0.7);
                });
    }
}
