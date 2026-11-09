package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Deployment-wide settings. One row, always id 1. */
@Entity
@Table(name = "app_settings")
@Getter
@Setter
@NoArgsConstructor
public class AppSettings {

    public static final long ID = 1L;

    @Id
    private Long id = ID;

    /** When false, a plan's submitter cannot approve it unless they are an admin. */
    @Column(nullable = false, columnDefinition = "boolean default false")
    private boolean allowSelfApproval;
}
