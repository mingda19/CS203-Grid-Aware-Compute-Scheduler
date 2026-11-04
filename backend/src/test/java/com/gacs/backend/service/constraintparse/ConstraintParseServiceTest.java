package com.gacs.backend.service.constraintparse;

import com.gacs.backend.dto.ConstraintParseRequestDto;
import com.gacs.backend.dto.ConstraintParseResponseDto;
import com.gacs.backend.repository.JobRepository;
import com.gacs.backend.service.JobService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class ConstraintParseServiceTest {

    /** Monday 2026-11-02 00:00 in ERCOT market time (CST, UTC-6). */
    private static final Instant NOW = Instant.parse("2026-11-02T06:00:00Z");

    private final JobRepository repository = mock(JobRepository.class);
    private final ConstraintParseService service = new ConstraintParseService(new RuleBasedConstraintParser(),
            new JobService(repository, org.mockito.Mockito.mock(com.gacs.backend.service.ScheduleInputs.class)), Clock.fixed(NOW, ZoneOffset.UTC), "America/Chicago");

    @Test
    @DisplayName("Times are read in ERCOT market time by default and returned in UTC")
    void defaultZoneIsMarketLocal() {
        ConstraintParseResponseDto r = service.parse(request("finish by 6pm", null));
        assertThat(r.timeZone()).isEqualTo("America/Chicago");
        // 18:00 on 02 Nov in CST (UTC-6) is 00:00 on 03 Nov in UTC
        assertThat(r.fields().deadline()).isEqualTo(LocalDateTime.of(2026, 11, 3, 0, 0));
    }

    @Test
    @DisplayName("A requested zone changes how clock times are read")
    void requestedZoneIsUsed() {
        ConstraintParseResponseDto r = service.parse(request("finish by 6pm", "UTC"));
        assertThat(r.timeZone()).isEqualTo("UTC");
        assertThat(r.fields().deadline()).isEqualTo(LocalDateTime.of(2026, 11, 2, 18, 0));
    }

    @Test
    @DisplayName("An unknown zone is a bad request")
    void unknownZoneIsRejected() {
        assertThatThrownBy(() -> service.parse(request("finish by 6pm", "Mars/Olympus")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unknown timeZone");
    }

    @Test
    @DisplayName("parsedAt comes from the clock, in UTC")
    void parsedAtIsTheClockTime() {
        assertThat(service.parse(request("urgent", null)).parsedAt()).isEqualTo(LocalDateTime.of(2026, 11, 2, 6, 0));
    }

    @Test
    @DisplayName("Evidence points at the phrase in the text as typed")
    void evidencePointsAtOriginalText() {
        String text = "Ｃritical, finish by 6pm";
        ConstraintParseResponseDto r = service.parse(request(text, null));
        assertThat(r.evidence()).extracting(ParsedConstraints.Evidence::phrase)
                .containsExactlyInAnyOrder("Ｃritical", "finish by 6pm");
        r.evidence().forEach(e -> assertThat(text.substring(e.start(), e.end())).isEqualTo(e.phrase()));
    }

    @Test
    @DisplayName("Text with nothing supported needs clarification and reports what it couldn't use")
    void nothingFound() {
        ConstraintParseResponseDto r = service.parse(request("needs 16 GPUs", null));
        assertThat(r.status()).isEqualTo(ConstraintParseResponseDto.NEEDS_CLARIFICATION);
        assertThat(r.unsupported()).extracting(ParsedConstraints.Unsupported::phrase).containsExactly("needs 16 GPUs");
    }

    @Test
    @DisplayName("Parsing never touches the job repository")
    void nothingIsSaved() {
        service.parse(request("finish by 6pm, can wait 4 hours, urgent", null));
        verifyNoInteractions(repository);
    }

    private static ConstraintParseRequestDto request(String text, String zone) {
        return new ConstraintParseRequestDto(text, null, zone);
    }
}
