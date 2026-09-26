package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "wind", indexes = {
    @Index(name = "idx_wind_time", columnList = "time")
})
@IdClass(WeatherKey.class)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Wind {

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false)
    private Location location;

    @Id
    @Column(nullable = false)
    private LocalDateTime time;

    @Column(name = "wind_speed_100m")
    private Double windSpeed100m;

    @Column(name = "wind_direction_100m")
    private Double windDirection100m;

    @Column(name = "wind_gusts_10m")
    private Double windGusts10m;

    @Column(name = "wind_speed_80m")
    private Double windSpeed80m;

    @Column(name = "wind_speed_120m")
    private Double windSpeed120m;

    @Column(name = "wind_speed_180m")
    private Double windSpeed180m;

    @Column(name = "wind_speed_200m")
    private Double windSpeed200m;

    @Column(name = "wind_direction_80m")
    private Double windDirection80m;

    @Column(name = "wind_direction_120m")
    private Double windDirection120m;

    @Column(name = "wind_direction_180m")
    private Double windDirection180m;

    @Column(name = "wind_direction_200m")
    private Double windDirection200m;

    @Column(name = "temperature_120m")
    private Double temperature120m;

    @Column(name = "temperature_1000hpa")
    private Double temperature1000hpa;
}
