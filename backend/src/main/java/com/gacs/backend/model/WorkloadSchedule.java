package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "workload_schedules", indexes = {
    @Index(name = "idx_workload_schedules_owner_start", columnList = "owner_email, start_utc")
})
@Getter
@Setter
@NoArgsConstructor
public class WorkloadSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_email", nullable = false, length = 255)
    private String ownerEmail;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 40)
    private String type;

    @Column(nullable = false, length = 200)
    private String cluster;

    @Column(name = "power_kw", nullable = false)
    private double powerKw;

    @Column(length = 100)
    private String savings;

    @Column(nullable = false, length = 20)
    private String status;

    @Column(name = "start_utc", nullable = false)
    private LocalDateTime startUtc;

    @Column(name = "end_utc", nullable = false)
    private LocalDateTime endUtc;

    @Column(length = 2000)
    private String notes;
}
