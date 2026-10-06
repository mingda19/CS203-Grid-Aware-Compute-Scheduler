package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * ERCOT day-ahead load forecast, from GridStatus's ercot_load_forecast_dam
 * dataset: "the load forecast for each interval based on the 14:30 CT
 * forecast from the previous day." Genuinely forward-looking (the earlier
 * EIA-sourced demand_forecast_mwh / hourly_demand_forecast table was
 * retrospective instead, and has been removed) - published once per day,
 * before the delivery day it covers, so it's usable as a same-day feature
 * without lagging. `zone` is ERCOT's weather-zone breakdown
 * (north/south/west/houston/system_total), a different zoning
 * scheme than the LZ_ settlement points in ElectricalPrice.
 */
@Entity
@Table(name = "load_forecast_dam", indexes = {
    @Index(name = "idx_load_forecast_dam_zone", columnList = "zone")
})
@IdClass(LoadForecastDamKey.class)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoadForecastDam {

    @Id
    @Column(name = "interval_start_utc", nullable = false)
    private LocalDateTime intervalStartUtc;

    @Id
    @Column(nullable = false)
    private String zone;

    @Column(name = "load_forecast_mwh")
    private Double loadForecastMwh;

    @Column(name = "publish_time_utc")
    private LocalDateTime publishTimeUtc; // when ERCOT published this forecast - kept for leakage audits
}
