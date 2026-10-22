package com.gacs.backend.service.allocation;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import java.util.List;
import java.util.Map;

import static com.gacs.backend.service.allocation.Fixtures.COSTS;
import static com.gacs.backend.service.allocation.Fixtures.T0;
import static com.gacs.backend.service.allocation.Fixtures.at;
import static com.gacs.backend.service.allocation.Fixtures.job;
import static com.gacs.backend.service.allocation.Fixtures.prices;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

/** Contract section 6.4: cost basis (AC2), capacity rule (AC3) and the configurable constants. */
class CapacityAndCostTest {

    @Test
    @DisplayName("Settings default to the contract's seeded values and can be overridden")
    void propertiesBindWithDefaults() {
        Binder binder = new Binder(new MapConfigurationPropertySource(Map.of("gacs.optimiser.cpu-kwh-per-hour", "0.1")));
        OptimizerProperties bound = binder.bindOrCreate("gacs.optimiser", OptimizerProperties.class);

        assertThat(bound.cpuKwhPerHour()).isEqualTo(0.1);
        assertThat(bound.gpuKwhPerHour()).isEqualTo(0.40);
        assertThat(bound.defaultCapacity()).isEqualTo(new CapacityLimit(256.0, 64.0));
        assertThat(new Binder(new MapConfigurationPropertySource(Map.of()))
                .bindOrCreate("gacs.optimiser", OptimizerProperties.class))
                .isEqualTo(OptimizerProperties.DEFAULTS);
    }

    @Test
    @DisplayName("A slot's limit is its override, else the cluster capacity, else the default")
    void slotLimitFollowsTheContractOrder() {
        CapacityLimit override = new CapacityLimit(128.0, 32.0);
        SlotCapacity capacity = SlotCapacity.resolve(OptimizerProperties.DEFAULTS, 200.0, null, Map.of(T0, override));

        assertThat(capacity.limitAt(T0)).isEqualTo(override);
        assertThat(capacity.limitAt(at(1))).isEqualTo(new CapacityLimit(200.0, 64.0)); // GPU falls back to default
        assertThat(SlotCapacity.resolve(OptimizerProperties.DEFAULTS, null, null, Map.of()).limitAt(T0))
                .isEqualTo(new CapacityLimit(256.0, 64.0));
    }

    @Test
    @DisplayName("The share a slot allows is set by the tighter of CPU and GPU, ignoring an unused one")
    void maxShareUsesTheTighterResource() {
        CapacityLimit limit = new CapacityLimit(256.0, 64.0);
        assertThat(limit.maxShare(job(1, 200.0, 0.0, 0, 0, 1))).isCloseTo(1.28, within(1e-12));
        assertThat(limit.maxShare(job(1, 64.0, 16.0, 0, 0, 1))).isCloseTo(4.0, within(1e-12));
        assertThat(new CapacityLimit(128.0, 8.0).maxShare(job(1, 64.0, 16.0, 0, 0, 1))).isCloseTo(0.5, within(1e-12));
        assertThat(new CapacityLimit(0.0, 64.0).maxShare(job(1, 0.0, 16.0, 0, 0, 1))).isCloseTo(4.0, within(1e-12));
    }

    @Test
    @DisplayName("Cost matches the contract's worked example: 9.6 kWh at $112 and at $24")
    void costMatchesContractExample() {
        OptimizerJob example = job(101, 64.0, 16.0, 0, 0, 1);
        assertThat(COSTS.jobKwh(example)).isCloseTo(9.6, within(1e-12));
        assertThat(COSTS.slotCostUsd(example, 1.0, 112.0)).isCloseTo(1.0752, within(1e-12));
        assertThat(COSTS.slotCostUsd(example, 1.0, 24.0)).isCloseTo(0.2304, within(1e-12));
    }

    @Test
    @DisplayName("A job's cost is the sum over its slots of share x kWh / 1000 x price")
    void costSumsSharesOverSlots() {
        OptimizerJob tenKwh = job(1, 200.0, 0.0, 0, 1, 3);
        List<SlotAllocation> allocations = List.of(new SlotAllocation(T0, 0.25), new SlotAllocation(at(1), 0.75));
        assertThat(COSTS.costUsd(tenKwh, allocations, prices(20.0, 80.0)))
                .isCloseTo(0.25 * 10 * 20 / 1000 + 0.75 * 10 * 80 / 1000, within(1e-12));
    }

    @Test
    @DisplayName("A slot without a price is reported, and cannot be costed")
    void missingPriceIsReported() {
        OptimizerJob job = job(1, 20.0, 0.0, 0, 0, 2);
        List<SlotAllocation> allocations = List.of(new SlotAllocation(T0, 0.5), new SlotAllocation(at(1), 0.5));

        assertThat(EnergyCostModel.firstUnpricedSlot(allocations, prices(10.0, Double.NaN))).contains(at(1));
        assertThat(EnergyCostModel.firstUnpricedSlot(allocations, prices(10.0, 10.0))).isEmpty();
        assertThatThrownBy(() -> COSTS.costUsd(job, allocations, prices(10.0)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(at(1).toString());
    }

    @Test
    @DisplayName("Costs round to 4 decimal places, half up")
    void costsRoundToFourPlaces() {
        assertThat(EnergyCostModel.roundUsd(0.12345)).isEqualTo(0.1235);
        assertThat(EnergyCostModel.roundUsd(1.07524)).isEqualTo(1.0752);
        assertThat(EnergyCostModel.roundUsd(-0.15)).isEqualTo(-0.15);
    }

    @Test
    @DisplayName("Negative or non-finite capacity is rejected")
    void invalidCapacityIsRejected() {
        assertThatThrownBy(() -> new CapacityLimit(-1.0, 0.0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new CapacityLimit(0.0, Double.NaN)).isInstanceOf(IllegalArgumentException.class);
    }
}
