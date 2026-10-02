package com.talentmatch.config;

import com.talentmatch.domain.scoring.ScoringEngine;
import com.talentmatch.domain.scoring.ScoringWeights;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Wires the pure scoring engine with the configured weights. */
@Configuration(proxyBeanMethods = false)
public class ScoringConfig {

    @Bean
    public ScoringEngine scoringEngine(ScoringProperties properties) {
        return new ScoringEngine(new ScoringWeights(properties.requiredWeight(), properties.niceToHaveWeight()));
    }
}
