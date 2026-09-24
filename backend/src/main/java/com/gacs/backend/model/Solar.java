package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

@Entity
@Table(name = "solar", indexes = {
    @Index(name = "idx_solar_time", columnList = "time")
})
@IdClass(WeatherKey.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class Solar {

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "location_id", nullable = false)
    private Location location;

    @Id
    @Column(nullable = false)
    private LocalDateTime time;

    @Column(name = "cloud_cover")
    private Double cloudCover;

    @Column(name = "direct_normal_irradiance")
    private Double directNormalIrradiance;

    @Column(name = "shortwave_radiation")
    private Double shortwaveRadiation;

    @Column(name = "temperature_2m")
    private Double temperature2m;
}
