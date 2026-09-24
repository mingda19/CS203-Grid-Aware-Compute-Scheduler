package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "load_data", indexes = {
    @Index(name = "idx_load_data_time", columnList = "time")
})
@IdClass(WeatherKey.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class LoadData {

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false)
    private Location location;

    @Id
    @Column(nullable = false)
    private LocalDateTime time;

    @Column(name = "temperature_2m")
    private Double temperature2m;

    @Column(name = "dew_point_2m")
    private Double dewPoint2m;

    @Column(name = "apparent_temperature")
    private Double apparentTemperature;

    @Column(name = "relative_humidity_2m")
    private Double relativeHumidity2m;

    @Column(name = "wind_speed_10m")
    private Double windSpeed10m;
}
