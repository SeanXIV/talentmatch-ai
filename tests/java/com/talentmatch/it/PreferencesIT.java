package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import java.time.Instant;
import org.junit.jupiter.api.Test;

/** Phase 5 step 2: GET/PUT /api/preferences (spec §5.3, §3.4 job_preferences). */
class PreferencesIT extends AbstractApiIT {

    private static final String URL = "/api/preferences";

    private static final String FULL = """
            {"preferences": {
              "targetTitles": ["  Backend   Engineer ", "backend engineer", "Data Engineer"],
              "excludedTitleKeywords": ["Sales"],
              "regions": {"countries": ["za", "GB"], "includeRemote": true, "remoteScope": "ANYWHERE",
                          "remoteLocationKeywords": ["worldwide"]},
              "seniority": ["SENIOR", "MID"],
              "salaryFloor": {"amount": 50000, "currency": "zar", "period": "MONTH"},
              "workAuthorization": ["ZA"],
              "noticePeriodDays": 30
            }}""";

    private int storedVersion() {
        return jdbc.queryForObject("SELECT version FROM job_preferences", Integer.class);
    }

    @Test
    void notFoundBeforeTheFirstSave() {
        JsonNode err = assertError(api.get(URL), 404, "PREFERENCES_NOT_FOUND");
        assertThat(err.get("message").asText()).isNotBlank();
    }

    @Test
    void saveNormalizesAndVersions() {
        Res first = api.put(URL, FULL);
        assertThat(first.status()).as("%s", first).isEqualTo(200);
        JsonNode b = first.json();
        assertThat(b.get("version").asInt()).isEqualTo(1);
        assertThat(Instant.parse(b.get("updatedAt").asText())).isNotNull();
        JsonNode p = b.get("preferences");
        assertThat(p.get("targetTitles")).extracting(JsonNode::asText).containsExactly("Backend Engineer", "Data Engineer");
        assertThat(p.at("/regions/countries")).extracting(JsonNode::asText).containsExactly("ZA", "GB");
        assertThat(p.at("/regions/remoteScope").asText()).isEqualTo("ANYWHERE");
        assertThat(p.get("seniority")).extracting(JsonNode::asText).containsExactly("MID", "SENIOR");
        assertThat(p.at("/salaryFloor/amount").decimalValue()).isEqualByComparingTo("50000");
        assertThat(p.at("/salaryFloor/currency").asText()).isEqualTo("ZAR");
        assertThat(p.at("/salaryFloor/period").asText()).isEqualTo("MONTH");
        assertThat(p.get("noticePeriodDays").asInt()).isEqualTo(30);

        Res get = api.get(URL);
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.json()).isEqualTo(b);

        Res second = api.put(URL, "{\"preferences\": {}}");
        assertThat(second.status()).isEqualTo(200);
        assertThat(second.json().get("version").asInt()).isEqualTo(2);
        JsonNode d = second.json().get("preferences");
        assertThat(d.get("targetTitles")).isEmpty();
        assertThat(d.at("/regions/countries")).extracting(JsonNode::asText).containsExactly("ZA");
        assertThat(d.at("/regions/includeRemote").asBoolean()).isTrue();
        assertThat(d.at("/regions/remoteScope").asText()).isEqualTo("ELIGIBLE_FROM_COUNTRIES");
        assertThat(d.at("/regions/remoteLocationKeywords")).hasSize(6);
        assertThat(d.path("salaryFloor").isNull() || d.path("salaryFloor").isMissingNode()).as("full replace").isTrue();
        assertThat(api.get(URL).json().get("version").asInt()).isEqualTo(2);
        assertThat(storedVersion()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_preferences", Integer.class)).isEqualTo(1);
    }

    @Test
    void validationErrorsUseFullPathsAndSaveNothing() {
        JsonNode err = assertError(api.put(URL, """
                {"preferences": {"regions": {"countries": ["ZA", "XX"]}, "seniority": ["SENIOR", "UNKNOWN"],
                 "salaryFloor": {"amount": 100, "currency": "ZAR", "period": "DAY"}, "noticePeriodDays": 400,
                 "targetTitles": ["A"]}}"""), 400, "VALIDATION_FAILED");
        assertThat(fields(err)).containsExactly("preferences.noticePeriodDays", "preferences.regions.countries[1]",
                "preferences.salaryFloor.period", "preferences.seniority[1]", "preferences.targetTitles[0]");
        assertThat(fieldMessages(err).get("preferences.regions.countries[1]")).isEqualTo("'XX' is not an ISO country code.");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_preferences", Integer.class)).isZero();

        api.put(URL, "{\"preferences\": {}}");
        assertError(api.put(URL, "{\"preferences\": {\"noticePeriodDays\": -1}}"), 400, "VALIDATION_FAILED");
        assertThat(storedVersion()).as("a rejected save does not bump the version").isEqualTo(1);
    }

    @Test
    void missingPreferencesIsAFieldError() {
        assertThat(fields(assertError(api.put(URL, "{}"), 400, "VALIDATION_FAILED"))).containsExactly("preferences");
        assertThat(fields(assertError(api.put(URL, "{\"preferences\": null}"), 400, "VALIDATION_FAILED")))
                .containsExactly("preferences");
    }

    @Test
    void malformedRequests() {
        assertError(api.put(URL, "{\"preferences\": {}, \"version\": 3}"), 400, "MALFORMED_REQUEST");
        JsonNode nested = assertError(api.put(URL, "{\"preferences\": {\"regions\": {\"country\": [\"ZA\"]}}}"),
                400, "MALFORMED_REQUEST");
        assertThat(nested.get("message").asText()).contains("country");
        assertError(api.put(URL, "{\"preferences\": {\"regions\": {\"remoteScope\": \"X\"}}}"), 400, "MALFORMED_REQUEST");
        assertError(api.put(URL, "{\"preferences\": {\"seniority\": [\"GURU\"]}}"), 400, "MALFORMED_REQUEST");
        assertError(api.put(URL, "{\"preferences\": {\"salaryFloor\": {\"amount\": 1, \"currency\": \"ZAR\", "
                + "\"period\": \"WEEK\"}}}"), 400, "MALFORMED_REQUEST");
        assertError(api.put(URL, "{\"preferences\": {\"noticePeriodDays\": \"soon\"}}"), 400, "MALFORMED_REQUEST");
        assertError(api.put(URL, "{\"preferences\": "), 400, "MALFORMED_REQUEST");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_preferences", Integer.class)).isZero();
    }

    @Test
    void storedJsonHoldsTheNormalizedPreferences() {
        api.put(URL, FULL);
        String stored = jdbc.queryForObject("SELECT preferences::text FROM job_preferences", String.class);
        assertThat(stored).contains("\"Backend Engineer\"").contains("\"ZAR\"").doesNotContain("backend engineer");
    }
}
