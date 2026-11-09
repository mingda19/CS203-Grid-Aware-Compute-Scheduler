package com.gacs.backend.service.constraintparse;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.time.Duration;

/**
 * Settings for the TypeSafe classifier, bound from {@code gacs.constraintparse.jev.*}.
 *
 * @param apiKey    TypeSafe account key, from {@code TYPESAFE_API_KEY}. Blank disables the
 *                  classifier entirely and nothing is sent anywhere; the rule-based parser is used.
 * @param model     the model id to request. Pinned rather than {@code jev-latest}: question wording
 *                  is tuned against a particular model's behaviour, so a silent upgrade could shift
 *                  every probability under the thresholds without anyone changing a line.
 * @param endpoint  the System One endpoint
 * @param timeout   per-call budget. Short on purpose - the measured round trip is a few hundred
 *                  milliseconds, this runs while an operator waits on a form, and the rule-based
 *                  parser is a usable answer if it expires.
 * @param applyAbove at or above this probability a proposal is applied without asking the operator
 * @param reviewAbove at or above this, but below {@code applyAbove}, the operator is asked to
 *                  confirm the constraint before the job is saved. Below it the proposal is
 *                  dropped entirely and never shown. The default 0.3-0.7 band is the range the
 *                  operator chose to have clarified; it is configuration because the right band
 *                  depends on the model's calibration, which is not ours to assume permanently.
 */
@ConfigurationProperties(prefix = "gacs.constraintparse.jev")
public record JevProperties(
        @DefaultValue("") String apiKey,
        @DefaultValue("jev-1.14.0") String model,
        @DefaultValue("https://api.typesafe.ai/v1/systemone") String endpoint,
        @DefaultValue("5s") Duration timeout,
        @DefaultValue("0.7") double applyAbove,
        @DefaultValue("0.3") double reviewAbove) {

    public boolean configured() {
        return apiKey != null && !apiKey.isBlank();
    }
}
