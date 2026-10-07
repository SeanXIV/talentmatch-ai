package com.talentmatch.support;

import com.talentmatch.profile.ResumeExtractionModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Replaces the provider ChatModel with {@link FakeChatModel} and the Clock with a movable
 * {@link MutableClock} (both {@code @Primary}, so the real Ollama bean, pointed at a closed port, is
 * never used). Phase 4: {@link FakeExtractionModel} behind a {@code @Primary} ResumeExtractionModel.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeChatModelConfig {

    @Bean
    @Primary
    public FakeChatModel fakeChatModel() {
        return new FakeChatModel();
    }

    @Bean
    @Primary
    public MutableClock testClock() {
        return MutableClock.ticking();
    }

    /** Not a ChatModel bean of its own (the context keeps exactly one primary ChatModel); reach it via the holder. */
    @Bean
    @Primary
    public ResumeExtractionModel fakeResumeExtractionModel() {
        return new ResumeExtractionModel(new FakeExtractionModel(), FakeExtractionModel.LABEL);
    }
}
