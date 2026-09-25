package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** EIA monthly US electricity generation mix, by fuel type (% of total generation). */
@Entity
@Table(name = "fuel_generation_monthly")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FuelGenerationMonthly {

    @Id
    @Column(nullable = false)
    private LocalDateTime period;

    @Column(name = "col_pct")
    private Double colPct;

    @Column(name = "ng_pct")
    private Double ngPct;

    @Column(name = "nuc_pct")
    private Double nucPct;

    @Column(name = "sun_pct")
    private Double sunPct;

    @Column(name = "wnd_pct")
    private Double wndPct;

    @Column(name = "hyc_pct")
    private Double hycPct;

    @Column(name = "geo_pct")
    private Double geoPct;

    @Column(name = "pet_pct")
    private Double petPct;

    @Column(name = "bio_pct")
    private Double bioPct;

    @Column(name = "oth_pct")
    private Double othPct;
}
