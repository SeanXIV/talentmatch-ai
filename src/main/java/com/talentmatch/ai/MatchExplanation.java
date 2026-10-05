package com.talentmatch.ai;

import dev.langchain4j.model.output.structured.Description;
import java.util.List;

/** Structured LLM output for one match (also the persisted explanation_payload shape). */
public record MatchExplanation(
        @Description("At most 12 words summarising the fit") String headline,
        @Description("2 to 4 plain-English sentences explaining the score") String explanation,
        @Description("0 to 3 short phrases, each naming a matched skill from MATCH FACTS") List<String> strengths,
        @Description("0 to 3 short phrases, each naming a missing skill from MATCH FACTS") List<String> gaps) {
}
