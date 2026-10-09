package com.talentmatch.feed;

import static org.assertj.core.api.Assertions.assertThat;

import com.talentmatch.feed.skills.SkillRequirement;
import com.talentmatch.feed.skills.SkillSuggestion;
import com.talentmatch.preferences.PreferenceFilter;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** feed_job jsonb arrays: round trips; lenient reads. */
class FeedJobJsonTest {

    @Test
    void skillsRoundTrip() {
        List<SkillRequirement> skills = List.of(new SkillRequirement(UUID.randomUUID(), "Java", true),
                new SkillRequirement(UUID.randomUUID(), "Kubernetes", false));
        assertThat(FeedJobJson.skills(FeedJobJson.write(skills))).isEqualTo(skills);
        assertThat(FeedJobJson.skills(FeedJobJson.write(List.of()))).isEmpty();
    }

    @Test
    void suggestionsRoundTrip() {
        List<SkillSuggestion> s = List.of(new SkillSuggestion("Terraform", "NICE_TO_HAVE", "Terraform a plus"));
        assertThat(FeedJobJson.suggestions(FeedJobJson.write(s))).isEqualTo(s);
    }

    @Test
    void namesRoundTrip() {
        String json = FeedJobJson.writeNames(List.of(PreferenceFilter.Reason.TITLE, PreferenceFilter.Reason.REGION));
        assertThat(json).isEqualTo("[\"TITLE\",\"REGION\"]");
        assertThat(FeedJobJson.strings(json)).containsExactly("TITLE", "REGION");
        List<PreferenceFilter.Flag> withNull = new ArrayList<>();
        withNull.add(null);
        withNull.add(PreferenceFilter.Flag.LOCATION_UNKNOWN);
        assertThat(FeedJobJson.writeNames(withNull)).isEqualTo("[\"LOCATION_UNKNOWN\"]");
        assertThat(FeedJobJson.writeNames(null)).isEqualTo("[]");
        assertThat(FeedJobJson.write(null)).isEqualTo("[]");
    }

    @Test
    void nullAndMalformedInput() {
        assertThat(FeedJobJson.skills(null)).as("ai_skills null = not enriched").isNull();
        assertThat(FeedJobJson.skills("not json")).isEmpty();
        assertThat(FeedJobJson.skills("{\"a\":1}")).isEmpty();
        assertThat(FeedJobJson.skills("null")).isEmpty();
        assertThat(FeedJobJson.suggestions(null)).isEmpty();
        assertThat(FeedJobJson.suggestions("[")).isEmpty();
        assertThat(FeedJobJson.strings(null)).isEmpty();
        assertThat(FeedJobJson.strings("{bad")).isEmpty();
        assertThat(FeedJobJson.strings("[\"A\",null,\"B\"]")).containsExactly("A", "B");
    }

    @Test
    void unknownFieldsAreIgnored() {
        UUID id = UUID.randomUUID();
        assertThat(FeedJobJson.skills("[{\"skillId\":\"" + id + "\",\"name\":\"Java\",\"required\":true,\"x\":1}]"))
                .containsExactly(new SkillRequirement(id, "Java", true));
    }
}
