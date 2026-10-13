package com.gacs.backend.model;

import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Builder
public class LoadForecastDamKey implements Serializable {

    private static final long serialVersionUID = 1L;

    private LocalDateTime intervalStartUtc; // matches @Id field name 'intervalStartUtc' in LoadForecastDam
    private String zone;                    // matches @Id field name 'zone' in LoadForecastDam - ERCOT weather
                                              // zone (north/south/west/houston/system_total), NOT a settlement point
}
