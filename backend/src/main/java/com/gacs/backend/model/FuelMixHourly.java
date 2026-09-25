package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** ERCOT hourly fuel mix for Texas: generation (MWh) and share (%) per fuel type. */
@Entity
@Table(name = "fuel_pct_hourly", indexes = {
    @Index(name = "idx_fuel_pct_hourly_period", columnList = "period")
})
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FuelMixHourly {

    @Id
    @Column(nullable = false)
    private LocalDateTime period;

    @Column(name = "bat_mwh")
    private Double batMwh;

    @Column(name = "col_mwh")
    private Double colMwh;

    @Column(name = "ng_mwh")
    private Double ngMwh;

    @Column(name = "nuc_mwh")
    private Double nucMwh;

    @Column(name = "oth_mwh")
    private Double othMwh;

    @Column(name = "sun_mwh")
    private Double sunMwh;

    @Column(name = "wat_mwh")
    private Double watMwh;

    @Column(name = "wnd_mwh")
    private Double wndMwh;

    @Column(name = "bat_pct")
    private Double batPct;

    @Column(name = "col_pct")
    private Double colPct;

    @Column(name = "ng_pct")
    private Double ngPct;

    @Column(name = "nuc_pct")
    private Double nucPct;

    @Column(name = "oth_pct")
    private Double othPct;

    @Column(name = "sun_pct")
    private Double sunPct;

    @Column(name = "wat_pct")
    private Double watPct;

    @Column(name = "wnd_pct")
    private Double wndPct;

    @Column(name = "ues_mwh")
    private Double uesMwh;

    @Column(name = "ues_pct")
    private Double uesPct;
}
