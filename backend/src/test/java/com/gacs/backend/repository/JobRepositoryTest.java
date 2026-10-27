package com.gacs.backend.repository;

import com.gacs.backend.model.Job;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Transactional
class JobRepositoryTest {

    @Autowired
    private JobRepository jobRepository;

    @Test
    @DisplayName("Persist and retrieve Job with all attributes and raw constraints")
    void testSaveAndRetrieveJob() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 20, 10, 0);
        LocalDateTime deadline = LocalDateTime.of(2026, 10, 21, 18, 0);

        Job job = Job.builder()
                .ownerEmail("operator@gridaware.com")
                .name("LLM Fine-Tuning Run #42")
                .cpuHours(64.0)
                .gpuHours(16.0)
                .originalStartTime(start)
                .deadline(deadline)
                .flexibilityWindowHours(12)
                .isExempt(false)
                .rawConstraints("Can wait up to 12 hours for low price valley, finish by tomorrow evening")
                .status("PENDING")
                .build();

        Job saved = jobRepository.save(job);

        assertThat(saved.getId()).isNotNull();
        assertThat(saved.getCreatedAt()).isNotNull();
        assertThat(saved.getUpdatedAt()).isNotNull();

        Optional<Job> retrievedOpt = jobRepository.findById(saved.getId());
        assertThat(retrievedOpt).isPresent();

        Job retrieved = retrievedOpt.get();
        assertThat(retrieved.getOwnerEmail()).isEqualTo("operator@gridaware.com");
        assertThat(retrieved.getName()).isEqualTo("LLM Fine-Tuning Run #42");
        assertThat(retrieved.getCpuHours()).isEqualTo(64.0);
        assertThat(retrieved.getGpuHours()).isEqualTo(16.0);
        assertThat(retrieved.getOriginalStartTime()).isEqualTo(start);
        assertThat(retrieved.getDeadline()).isEqualTo(deadline);
        assertThat(retrieved.getFlexibilityWindowHours()).isEqualTo(12);
        assertThat(retrieved.isExempt()).isFalse();
        assertThat(retrieved.getRawConstraints()).isEqualTo("Can wait up to 12 hours for low price valley, finish by tomorrow evening");
        assertThat(retrieved.getStatus()).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("Verify default values when optional/defaulted attributes are omitted")
    void testDefaultValues() {
        LocalDateTime start = LocalDateTime.of(2026, 10, 20, 14, 0);
        LocalDateTime deadline = LocalDateTime.of(2026, 10, 21, 14, 0);

        Job job = new Job();
        job.setOwnerEmail("operator@gridaware.com");
        job.setName("Batch Analytics Job");
        job.setOriginalStartTime(start);
        job.setDeadline(deadline);

        Job saved = jobRepository.save(job);

        assertThat(saved.isExempt()).isFalse();
        assertThat(saved.getFlexibilityWindowHours()).isEqualTo(0);
        assertThat(saved.getCpuHours()).isEqualTo(0.0);
        assertThat(saved.getGpuHours()).isEqualTo(0.0);
        assertThat(saved.getStatus()).isEqualTo("PENDING");
        assertThat(saved.getRawConstraints()).isNull();
    }

    @Test
    @DisplayName("Find jobs by owner ordered chronologically by original start time")
    void testFindByOwnerEmailOrderByOriginalStartTimeAsc() {
        String owner = "operator1@gridaware.com";
        String otherOwner = "operator2@gridaware.com";

        LocalDateTime t1 = LocalDateTime.of(2026, 10, 20, 8, 0);
        LocalDateTime t2 = LocalDateTime.of(2026, 10, 20, 12, 0);
        LocalDateTime t3 = LocalDateTime.of(2026, 10, 20, 16, 0);

        Job j2 = Job.builder()
                .ownerEmail(owner)
                .name("Job 2")
                .originalStartTime(t2)
                .deadline(t2.plusHours(24))
                .build();

        Job j1 = Job.builder()
                .ownerEmail(owner)
                .name("Job 1")
                .originalStartTime(t1)
                .deadline(t1.plusHours(24))
                .build();

        Job j3Other = Job.builder()
                .ownerEmail(otherOwner)
                .name("Other Owner Job")
                .originalStartTime(t3)
                .deadline(t3.plusHours(24))
                .build();

        jobRepository.saveAll(List.of(j2, j1, j3Other));

        List<Job> ownerJobs = jobRepository.findByOwnerEmailOrderByOriginalStartTimeAsc(owner);
        assertThat(ownerJobs).hasSize(2);
        assertThat(ownerJobs.get(0).getName()).isEqualTo("Job 1");
        assertThat(ownerJobs.get(1).getName()).isEqualTo("Job 2");
    }

    @Test
    @DisplayName("Find job by ID and owner ensures tenant isolation")
    void testFindByIdAndOwnerEmail() {
        String owner1 = "owner1@gridaware.com";
        String owner2 = "owner2@gridaware.com";

        Job job = Job.builder()
                .ownerEmail(owner1)
                .name("Sensitive Workload")
                .originalStartTime(LocalDateTime.of(2026, 10, 20, 10, 0))
                .deadline(LocalDateTime.of(2026, 10, 21, 10, 0))
                .build();

        Job saved = jobRepository.save(job);

        assertThat(jobRepository.findByIdAndOwnerEmail(saved.getId(), owner1)).isPresent();
        assertThat(jobRepository.findByIdAndOwnerEmail(saved.getId(), owner2)).isEmpty();
        assertThat(jobRepository.existsByIdAndOwnerEmail(saved.getId(), owner1)).isTrue();
        assertThat(jobRepository.existsByIdAndOwnerEmail(saved.getId(), owner2)).isFalse();
    }

    @Test
    @DisplayName("Filter jobs by owner and exemption status (GRID-86 / GRID-24)")
    void testFindByOwnerEmailAndIsExempt() {
        String owner = "data-center@gridaware.com";

        Job regularJob = Job.builder()
                .ownerEmail(owner)
                .name("Flexible Job")
                .originalStartTime(LocalDateTime.of(2026, 10, 20, 10, 0))
                .deadline(LocalDateTime.of(2026, 10, 21, 10, 0))
                .isExempt(false)
                .build();

        Job exemptJob = Job.builder()
                .ownerEmail(owner)
                .name("Exempt Non-Negotiable Job")
                .originalStartTime(LocalDateTime.of(2026, 10, 20, 10, 0))
                .deadline(LocalDateTime.of(2026, 10, 21, 10, 0))
                .isExempt(true)
                .build();

        jobRepository.saveAll(List.of(regularJob, exemptJob));

        List<Job> exemptJobs = jobRepository.findByOwnerEmailAndIsExempt(owner, true);
        assertThat(exemptJobs).hasSize(1);
        assertThat(exemptJobs.get(0).getName()).isEqualTo("Exempt Non-Negotiable Job");
        assertThat(exemptJobs.get(0).isExempt()).isTrue();

        List<Job> nonExemptJobs = jobRepository.findByOwnerEmailAndIsExempt(owner, false);
        assertThat(nonExemptJobs).hasSize(1);
        assertThat(nonExemptJobs.get(0).getName()).isEqualTo("Flexible Job");
        assertThat(nonExemptJobs.get(0).isExempt()).isFalse();
    }

    @Test
    @DisplayName("Update Job fields including flexibility window and exempt flag")
    void testUpdateJobFields() {
        Job job = Job.builder()
                .ownerEmail("operator@gridaware.com")
                .name("Initial Job")
                .cpuHours(10.0)
                .gpuHours(2.0)
                .originalStartTime(LocalDateTime.of(2026, 10, 20, 10, 0))
                .deadline(LocalDateTime.of(2026, 10, 21, 10, 0))
                .flexibilityWindowHours(2)
                .isExempt(false)
                .build();

        Job saved = jobRepository.save(job);
        Long id = saved.getId();

        // Update fields
        saved.setFlexibilityWindowHours(8);
        saved.setExempt(true);
        saved.setRawConstraints("Operator flagged as non-negotiable emergency run");
        saved.setStatus("SCHEDULED");
        jobRepository.save(saved);

        Job updated = jobRepository.findById(id).orElseThrow();
        assertThat(updated.getFlexibilityWindowHours()).isEqualTo(8);
        assertThat(updated.isExempt()).isTrue();
        assertThat(updated.getRawConstraints()).isEqualTo("Operator flagged as non-negotiable emergency run");
        assertThat(updated.getStatus()).isEqualTo("SCHEDULED");
    }
}
