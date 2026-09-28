package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** EIA/ERCOT hourly demand, forecast demand, net generation and interchange for Texas. */
@Entity
@Table(name = "hourly_demand_forecast", indexes = {
    @Index(name = "idx_hourly_demand_forecast_period", columnList = "period")
})
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HourlyDemandForecast {

    @Id
    @Column(nullable = false)
    private LocalDateTime period;

    @Column(name = "demand_mwh")
    private Double demandMwh;

    @Column(name = "demand_forecast_mwh")
    private Double demandForecastMwh;

    @Column(name = "net_generation_mwh")
    private Double netGenerationMwh;

    @Column(name = "total_interchange_mwh")
    private Double totalInterchangeMwh;
}
