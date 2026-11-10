package com.gacs.backend.service.allocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The run summary groups unscheduled jobs by category, and the category is derived from the prose
 * {@link AllocationResult} writes. That coupling is only safe if it is pinned: these cases fail if
 * a reason is reworded without updating the prefix it is matched on.
 */
class UnscheduledReasonTest {

    /**
     * One sample of each message {@code AllocationResult.noRoomReason} can produce, copied from its
     * actual wording. If one of these stops classifying, the summary has started reporting that
     * job under "they could not be placed" instead of saying why.
     */
    @Test
    @DisplayName("every reason AllocationResult can emit classifies to something other than OTHER")
    void everyEmittedReasonClassifies() {
        assertThat(UnscheduledReason.of(
                "Deadline 2026-11-05 16:00 UTC has passed: this job's whole window is earlier than ..."))
                .isEqualTo(UnscheduledReason.DEADLINE_PASSED);
        assertThat(UnscheduledReason.of(
                "Window elapsed: every hour this job could start in is earlier than 2026-11-05 13:00 UTC ..."))
                .isEqualTo(UnscheduledReason.WINDOW_ELAPSED);
        assertThat(UnscheduledReason.of(
                "Beyond the price forecast: no hour this job could start in has a published price forecast yet ..."))
                .isEqualTo(UnscheduledReason.BEYOND_FORECAST);
        assertThat(UnscheduledReason.of(
                "Not enough time left: only the hours from 2026-11-05 13:00 UTC remain before its deadline ..."))
                .isEqualTo(UnscheduledReason.NOT_ENOUGH_TIME);
        assertThat(UnscheduledReason.of(
                "Cluster full between 2026-11-05 13:00 and 2026-11-05 18:00 UTC: job id 4 uses the capacity ..."))
                .isEqualTo(UnscheduledReason.CAPACITY_CONTENTION);
    }

    @Test
    @DisplayName("an unrecognised, absent or blank reason degrades to OTHER rather than throwing")
    void unknownReasonsDegrade() {
        assertThat(UnscheduledReason.of("something nobody has written yet")).isEqualTo(UnscheduledReason.OTHER);
        assertThat(UnscheduledReason.of(null)).isEqualTo(UnscheduledReason.OTHER);
        assertThat(UnscheduledReason.of("   ")).isEqualTo(UnscheduledReason.OTHER);
    }

    @Test
    @DisplayName("matching ignores case and surrounding whitespace")
    void matchingIsForgivingOfFormatting() {
        assertThat(UnscheduledReason.of("  cluster full between x and y: ..."))
                .isEqualTo(UnscheduledReason.CAPACITY_CONTENTION);
    }

    @ParameterizedTest
    @EnumSource(UnscheduledReason.class)
    @DisplayName("every category can finish the summary line it appears on")
    void everyCategoryHasPluralPhrasing(UnscheduledReason reason) {
        // The summary writes "N jobs could not be scheduled: " + plural(), so a blank or
        // sentence-cased value would read wrong rather than merely look wrong.
        assertThat(reason.plural()).isNotBlank();
        assertThat(reason.plural()).doesNotEndWith(".");
        assertThat(reason.plural().charAt(0)).isLowerCase();
    }
}
