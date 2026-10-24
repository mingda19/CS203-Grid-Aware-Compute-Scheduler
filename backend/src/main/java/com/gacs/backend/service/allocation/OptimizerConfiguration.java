package com.gacs.backend.service.allocation;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers the optimiser's configurable constants and the cost model built from them. */
@Configuration
@EnableConfigurationProperties(OptimizerProperties.class)
public class OptimizerConfiguration {

    @Bean
    public EnergyCostModel energyCostModel(OptimizerProperties properties) {
        return new EnergyCostModel(properties);
    }
}
