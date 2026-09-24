package com.gacs.backend.model;

import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Builder
public class WeatherKey implements Serializable {

    private static final long serialVersionUID = 1L;

    private Long location;        // matches @Id field name 'location' in weather entities
    private LocalDateTime time;   // matches @Id field name 'time' in weather entities
}
