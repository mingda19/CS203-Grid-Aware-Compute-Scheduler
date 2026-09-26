package com.gacs.backend.model;

import java.time.LocalDateTime;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** ERCOT real-time settlement point price. `location` is a settlement point/hub name, not a locations.location_id FK. */
@Entity
@Table(name = "electrical_price", indexes = {
    @Index(name = "idx_settlement_point_price_location", columnList = "location")
})
@IdClass(SppKey.class)
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ElectricalPrice {

    @Id
    @Column(name = "interval_start_utc", nullable = false)
    private LocalDateTime intervalStartUtc;

    @Id
    @Column(nullable = false)
    private String location;

    @Column(name = "spp_usd_mwh")
    private Double sppUsdMwh;
}
