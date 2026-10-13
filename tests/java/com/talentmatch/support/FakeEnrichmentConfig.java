package com.talentmatch.support;

import com.talentmatch.feed.skills.JobSkillExtractionModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Phase 5 step 10: a scripted {@link FakeExtractionModel} behind a {@code @Primary}
 * {@link JobSkillExtractionModel} (local, so the {@code LocalModelGate} applies). Not a ChatModel bean
 * of its own; tests reach it via {@code ((FakeExtractionModel) holder.chatModel())}.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeEnrichmentConfig {

    public static final String LABEL = "fake/enricher";

    @Bean
    @Primary
    public JobSkillExtractionModel fakeJobSkillExtractionModel() {
        return new JobSkillExtractionModel(new FakeExtractionModel(), LABEL, null, true);
    }
}
