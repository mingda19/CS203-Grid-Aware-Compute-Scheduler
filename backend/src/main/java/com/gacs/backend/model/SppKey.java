package com.gacs.backend.model;

import lombok.*;

import java.io.Serializable;
import java.time.LocalDateTime;

@Getter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
@Builder
public class SppKey implements Serializable {

    private static final long serialVersionUID = 1L;

    private LocalDateTime intervalStartUtc; // matches @Id field name 'intervalStartUtc' in SettlementPointPrice
    private String location;                // matches @Id field name 'location' in SettlementPointPrice
}
