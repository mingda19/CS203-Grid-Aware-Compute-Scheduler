package com.gacs.backend.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ScheduleHashUtilTest {

    @Test
    @DisplayName("computeSha256 produces deterministic 64-character hex string")
    void testComputeSha256_Deterministic() {
        String input = "{\"runId\":\"123\",\"jobs\":[{\"id\":1}]}";
        String hash1 = ScheduleHashUtil.computeSha256(input);
        String hash2 = ScheduleHashUtil.computeSha256(input);

        assertThat(hash1).isNotNull();
        assertThat(hash1).hasSize(64);
        assertThat(hash1).isEqualTo(hash2);
    }

    @Test
    @DisplayName("computeSha256 throws IllegalArgumentException on null input")
    void testComputeSha256_NullInput() {
        assertThatThrownBy(() -> ScheduleHashUtil.computeSha256(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
    }
}
