package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Facility and cluster hardware capacity configuration entity.
 * Models per-user / per-tenant cluster hardware limits and grid node locations.
 */
@Entity
@Table(name = "facilities", indexes = {
    @Index(name = "idx_facilities_owner", columnList = "owner_email")
})
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Facility {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "owner_email", nullable = false, length = 255)
    private String ownerEmail;

    @Builder.Default
    @Column(nullable = false, length = 255)
    private String name = "Primary Compute Cluster";

    @Builder.Default
    @Column(name = "max_cpu_hours", nullable = false)
    private Double maxCpuHours = 256.0;

    @Builder.Default
    @Column(name = "max_gpu_hours", nullable = false)
    private Double maxGpuHours = 64.0;

    @Builder.Default
    @Column(nullable = false, length = 50)
    private String location = "LZ_NORTH";

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        this.updatedAt = LocalDateTime.now();
    }
}
