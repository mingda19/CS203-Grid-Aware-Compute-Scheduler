package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/** EIA Henry Hub natural gas spot price. */
@Entity
@Table(name = "fuel_price")
@Getter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class FuelPrice {

    @Id
    @Column(nullable = false)
    private LocalDateTime period;

    @Column(name = "henry_hub_price_usd_mmbtu")
    private Double henryHubPriceUsdMmbtu;
}
