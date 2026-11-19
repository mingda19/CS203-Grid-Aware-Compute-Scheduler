package com.gacs.backend.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.LocalDateTime;

/**
 * Pre-computed hourly model inputs for LZ_NORTH, one row per UTC hour - the cache behind
 * data_scripts/predictions.load_exogenous(). Those four values are derived from the raw
 * {@code wind}, {@code solar} and {@code fuel_price} tables (wind/solar power-output proxies summed
 * over every site, plus Henry Hub gas lagged 2 and 3 days), and rebuilding them used to mean
 * pulling years of per-site weather rows on every prediction run (LEAR alone: ~1456 days x 31
 * sites, ~90 MB per run).
 *
 * <p>Written only by data_scripts/feature_store.py - the last step of ingestion.py refreshes the
 * recent window every day (weather for the newest days is a revised forecast, so it is never
 * final), and {@code python feature_store.py --rebuild} backfills the whole history. This entity
 * only declares the table (ddl-auto=update creates it, like {@link RmseReference}); nothing in
 * the backend reads or writes it.
 *
 * <p>Price is deliberately not stored here: {@code electrical_price} already is the single source
 * of truth for it. Calendar features (hour, weekday, month, holiday) are cheap to derive from the
 * timestamp, so they are not stored either.
 *
 * <p>A NULL value means the source had no data for that hour when the row was last computed - the
 * same NaN the raw path would produce. The row still exists, so a gap in the table's hours (not in
 * its values) is what tells the reader the cache is incomplete and it must fall back to the raw
 * tables.
 */
@Entity
@Table(name = "model_features_hourly")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ModelFeatureHourly {

    /** The hour these values are for (UTC, hour start). */
    @Id
    @Column(name = "interval_start_utc", nullable = false)
    private LocalDateTime intervalStartUtc;

    /** Henry Hub price (USD/MMBtu) of the day 2 days earlier, last published value carried forward. */
    @Column(name = "henry_hub_price_usd_mmbtu_lag2d")
    private Double henryHubPriceUsdMmbtuLag2d;

    /** Henry Hub price (USD/MMBtu) of the day 3 days earlier, last published value carried forward. */
    @Column(name = "henry_hub_price_usd_mmbtu_lag3d")
    private Double henryHubPriceUsdMmbtuLag3d;

    /** Sum over all wind sites of the cubic-law turbine output proxy (predictions.add_wind_power_proxy). */
    @Column(name = "wind_power_output_proxy_total")
    private Double windPowerOutputProxyTotal;

    /** Sum over all solar sites of the panel output proxy (predictions.add_solar_power_proxy). */
    @Column(name = "solar_power_output_proxy_total")
    private Double solarPowerOutputProxyTotal;
}
