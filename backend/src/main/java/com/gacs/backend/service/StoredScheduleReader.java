package com.gacs.backend.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.gacs.backend.dto.OptimizeResponseDto;
import com.gacs.backend.dto.OptimizeResponseDto.ScheduleItem;
import org.springframework.stereotype.Component;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.Collectors;

/**
 * Reads the optimiser result stored with a plan ({@code OptimizationPlan.resultJson}, contract section 6.5),
 * with the same mapper setup {@link ScheduleReviewService} uses to write it.
 */
@Component
public class StoredScheduleReader {

    // Tolerant of fields added to the result later.
    private final ObjectMapper mapper = new ObjectMapper().findAndRegisterModules()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** The stored result, or empty when the text is not a readable result. */
    public Optional<OptimizeResponseDto> read(String resultJson) {
        if (resultJson == null) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(mapper.readValue(resultJson, OptimizeResponseDto.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    /** IDs of every job the result lists, scheduled or not, in ascending order. */
    public static Set<Long> jobIds(OptimizeResponseDto schedule) {
        if (schedule.items() == null) {
            return Set.of();
        }
        return schedule.items().stream()
                .map(ScheduleItem::jobId)
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));
    }
}
