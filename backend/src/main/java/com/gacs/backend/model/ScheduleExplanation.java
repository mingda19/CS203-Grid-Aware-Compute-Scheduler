package com.gacs.backend.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "schedule_explanations", indexes = {
    @Index(name = "idx_schedule_explanations_owner", columnList = "owner_email")
})
@Getter
@Setter
@NoArgsConstructor
public class ScheduleExplanation {

    @Id
    @Column(name = "run_id", nullable = false, updatable = false, length = 36)
    private String runId;

    @Column(name = "owner_email", nullable = false, length = 255)
    private String ownerEmail;

    @Column(nullable = false, length = 10000)
    private String explanation;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
