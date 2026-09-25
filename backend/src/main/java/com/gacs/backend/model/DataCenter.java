package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "data_centers", indexes = {
    @Index(name = "idx_data_centers_time", columnList = "time")
})
@IdClass(WeatherKey.class)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class DataCenter {

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
}
