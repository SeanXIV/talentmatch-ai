package com.talentmatch.ai;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.repository.ExplanationTarget;
import org.junit.jupiter.api.Test;

class AudienceTest {

    @Test
    void twoAudiencesWithLowerCaseTags() {
        assertThat(Audience.values()).containsExactly(Audience.RECRUITER, Audience.SEEKER);
        assertThat(Audience.RECRUITER.tag()).isEqualTo("recruiter");
        assertThat(Audience.SEEKER.tag()).isEqualTo("seeker");
    }

    @Test
    void eachAudienceMapsToItsOwnStorageTarget() {
        assertThat(Audience.RECRUITER.target()).isEqualTo(ExplanationTarget.RECRUITER);
        assertThat(Audience.SEEKER.target()).isEqualTo(ExplanationTarget.SEEKER);
        for (Audience a : Audience.values()) {
            assertThat(a.target().name()).isEqualTo(a.name());
        }
        assertThat(ExplanationTarget.values()).hasSameSizeAs(Audience.values());
    }

    @Test
    void eachAudienceHasItsOwnSystemPrompt() {
        assertThat(Audience.RECRUITER.systemPrompt()).isSameAs(ExplanationPrompts.SYSTEM);
        assertThat(Audience.SEEKER.systemPrompt()).isSameAs(SeekerExplanationPrompts.SYSTEM);
        assertThat(Audience.SEEKER.systemPrompt()).isNotEqualTo(Audience.RECRUITER.systemPrompt());
    }

    @Test
    void theSeekerAssistantSendsTheSeekerSystemPrompt() throws Exception {
        var m = SeekerExplanationAssistant.class.getMethod("explain", String.class);
        assertThat(m.getAnnotation(dev.langchain4j.service.SystemMessage.class).value())
                .containsExactly(SeekerExplanationPrompts.SYSTEM);
        assertThat(m.getParameters()[0].isAnnotationPresent(dev.langchain4j.service.UserMessage.class)).isTrue();
    }
}
