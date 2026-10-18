package com.talentmatch.ai;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AudienceTest {

    @Test
    void twoAudiencesWithLowerCaseTags() {
        assertThat(Audience.values()).containsExactly(Audience.RECRUITER, Audience.SEEKER);
        assertThat(Audience.RECRUITER.tag()).isEqualTo("recruiter");
        assertThat(Audience.SEEKER.tag()).isEqualTo("seeker");
    }
}
