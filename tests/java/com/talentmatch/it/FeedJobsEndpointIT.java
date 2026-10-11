package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.support.AbstractFeedProcessingIT;
import com.talentmatch.support.Api.Res;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** §5.2 GET /api/feed/jobs, GET /api/feed/jobs/{id}, and the step-7 part of GET /api/feed/status. */
class FeedJobsEndpointIT extends AbstractFeedProcessingIT {

    private static final String BACKEND_DESC =
            "You will build Java services and write SQL every day. Kubernetes is advantageous.";
    private static final String PREFS = """
            {"targetTitles": ["Backend Engineer"],
             "regions": {"countries": ["ZA"], "includeRemote": true, "remoteScope": "ELIGIBLE_FROM_COUNTRIES"}}""";

    private UUID base;
    private UUID top;       // Backend Engineer, 0.8, notified, newest
    private UUID low;       // Senior Backend Engineer, 0.4
    private UUID filtered;  // Data Analyst, FILTERED TITLE
    private UUID closed;    // Backend Developer, closed

    /** Five jobs with first_seen_at now-5h (baseline) … now-1h (top). */
    private void fixture(boolean withProfile) {
        skills("Java", "SQL", "Kubernetes", "Python");
        if (withProfile) {
            profile("Java", "SQL");
        }
        preferences(PREFS);
        notifications(true, 0.6);
        UUID s = lever("acme", "Acme");
        Post b = old("base", "Backend Platform Engineer", "Java and SQL.");
        serve("acme", b);
        pollOk(s);
        serve("acme", b,
                fresh("top", "Backend Engineer", BACKEND_DESC),
                fresh("low", "Senior Backend Engineer", "Java and Python are required. Kubernetes is a plus."),
                fresh("flt", "Data Analyst", "Java and SQL."),
                fresh("cls", "Backend Developer", "Java and SQL."));
        jdbc.update("UPDATE feed_source SET last_polled_at = NULL WHERE id = ?", s);
        pollOk(s);
        base = jobOf(s, "base");
        top = jobOf(s, "top");
        low = jobOf(s, "low");
        filtered = jobOf(s, "flt");
        closed = jobOf(s, "cls");
        seen(top, 1);
        seen(low, 2);
        seen(filtered, 3);
        seen(closed, 4);
        seen(base, 5);
        processor.processDue();
        jdbc.update("UPDATE feed_job SET closed_at = now() WHERE job_id = ?", closed);
        jdbc.update("UPDATE feed_job SET salary_min = 50000, salary_max = 70000, salary_currency = 'ZAR', "
                + "salary_period = 'MONTH', salary_estimated = false WHERE job_id = ?", top);
    }

    private void seen(UUID job, int hoursAgo) {
        jdbc.update("UPDATE feed_job SET first_seen_at = ? WHERE job_id = ?",
                Timestamp.from(start.minus(Duration.ofHours(hoursAgo))), job);
    }

    private JsonNode list(String query) {
        Res r = api.get("/api/feed/jobs" + (query.isEmpty() ? "" : "?" + query));
        assertThat(r.status()).as("%s", r).isEqualTo(200);
        return r.json();
    }

    private static List<UUID> ids(JsonNode page) {
        List<UUID> ids = new ArrayList<>();
        page.get("content").forEach(n -> ids.add(UUID.fromString(n.get("jobId").asText())));
        return ids;
    }

    private static JsonNode item(JsonNode page, UUID id) {
        for (JsonNode n : page.get("content")) {
            if (n.get("jobId").asText().equals(id.toString())) {
                return n;
            }
        }
        throw new AssertionError(id + " not in " + page);
    }

    // ------------------------------------------------------------------ list filters

    @Test
    void defaultsHideFilteredClosedAndBaseline() {
        fixture(true);
        JsonNode page = list("");
        assertThat(ids(page)).containsExactly(top, low);
        assertThat(page.get("totalElements").asLong()).isEqualTo(2);

        assertThat(ids(list("includeFiltered=true"))).containsExactly(top, low, filtered);
        assertThat(ids(list("includeClosed=true"))).containsExactly(top, low, closed);
        assertThat(ids(list("includeBaseline=true"))).containsExactly(top, low, base);
        JsonNode all = list("includeFiltered=true&includeClosed=true&includeBaseline=true");
        assertThat(ids(all)).as("newest firstSeenAt first").containsExactly(top, low, filtered, closed, base);
        assertThat(all.get("totalElements").asLong()).isEqualTo(5);
        assertError(api.get("/api/feed/jobs?includeClosed=maybe"), 400, "INVALID_PARAMETER");
    }

    @Test
    void sinceFilter() {
        fixture(true);
        String since = start.minus(Duration.ofMinutes(150)).toString();
        assertThat(ids(list("since=" + since))).containsExactly(top, low);
        String offset = start.minus(Duration.ofMinutes(90)).atOffset(ZoneOffset.ofHours(2)).toString().replace("+", "%2B");
        assertThat(ids(list("since=" + offset))).containsExactly(top);
        assertThat(ids(list("since=" + start.plusSeconds(60)))).isEmpty();
        JsonNode err = assertError(api.get("/api/feed/jobs?since=yesterday"), 400, "INVALID_PARAMETER");
        assertThat(err.get("message").asText()).contains("since");
        assertError(api.get("/api/feed/jobs?since=2026-13-01T00:00:00Z"), 400, "INVALID_PARAMETER");
    }

    @Test
    void minScoreFilter() {
        fixture(true);
        assertThat(ids(list("minScore=0.6"))).containsExactly(top);
        assertThat(ids(list("minScore=0.4"))).containsExactly(top, low);
        assertThat(ids(list("minScore=0.81"))).isEmpty();
        assertThat(ids(list("minScore=0&includeFiltered=true&includeBaseline=true")))
                .containsExactly(top, low, filtered, base);
        assertThat(ids(list("minScore=1"))).isEmpty();
        assertError(api.get("/api/feed/jobs?minScore=1.5"), 400, "INVALID_PARAMETER");
        assertError(api.get("/api/feed/jobs?minScore=-0.1"), 400, "INVALID_PARAMETER");
        assertError(api.get("/api/feed/jobs?minScore=NaN"), 400, "INVALID_PARAMETER");
        assertError(api.get("/api/feed/jobs?minScore=high"), 400, "INVALID_PARAMETER");
    }

    @Test
    void minScoreWithoutAProfileIsEmpty() {
        fixture(false);
        assertThat(ids(list(""))).containsExactly(top, low);
        assertThat(ids(list("minScore=0"))).isEmpty();
        JsonNode t = item(list(""), top);
        assertThat(t.get("score").isNull()).isTrue();
        assertThat(t.get("scorePercent").isNull()).isTrue();
        assertThat(t.get("summary").isNull()).isTrue();
        assertThat(t.get("matchable").asBoolean()).isTrue();
        assertThat(t.get("matchedRequired")).isEmpty();
    }

    @Test
    void pagingAndOrdering() {
        fixture(true);
        String all = "includeFiltered=true&includeClosed=true&includeBaseline=true";
        JsonNode p0 = list(all + "&size=2&page=0");
        assertThat(ids(p0)).containsExactly(top, low);
        assertThat(p0.get("totalElements").asLong()).isEqualTo(5);
        assertThat(p0.get("totalPages").asInt()).isEqualTo(3);
        assertThat(p0.get("size").asInt()).isEqualTo(2);
        assertThat(ids(list(all + "&size=2&page=1"))).containsExactly(filtered, closed);
        assertThat(ids(list(all + "&size=2&page=2"))).containsExactly(base);
        JsonNode beyond = list(all + "&size=2&page=9");
        assertThat(ids(beyond)).isEmpty();
        assertThat(beyond.get("totalElements").asLong()).isEqualTo(5);
        assertError(api.get("/api/feed/jobs?size=0"), 400, "INVALID_PARAMETER");
        assertError(api.get("/api/feed/jobs?page=-1"), 400, "INVALID_PARAMETER");
    }

    @Test
    void itemFields() {
        fixture(true);
        JsonNode page = list("includeFiltered=true");
        JsonNode t = item(page, top);
        assertThat(t.get("title").asText()).isEqualTo("Backend Engineer");
        assertThat(t.get("company").asText()).isEqualTo("Acme");
        assertThat(t.get("baseline").asBoolean()).isFalse();
        assertThat(t.get("score").asDouble()).isCloseTo(0.8, within(1e-9));
        assertThat(t.get("scorePercent").asInt()).isEqualTo(80);
        assertThat(t.get("matchable").asBoolean()).isTrue();
        assertThat(t.get("summary").asText()).isNotBlank();
        assertThat(texts(t.get("matchedRequired"))).containsExactly("Java", "SQL");
        assertThat(texts(t.get("missingRequired"))).isEmpty();
        assertThat(t.get("preferenceVerdict").asText()).isEqualTo("PASS");
        assertThat(texts(t.get("filterReasons"))).isEmpty();

        List<String> skills = new ArrayList<>();
        t.get("skills").forEach(s -> skills.add(s.get("name").asText() + "/" + s.get("required").asBoolean() + "/"
                + s.get("source").asText()));
        assertThat(skills).containsExactly("Java/true/DICTIONARY", "Kubernetes/false/DICTIONARY", "SQL/true/DICTIONARY");
        assertThat(t.get("aiSuggestions")).isEmpty();

        JsonNode src = t.get("sources");
        assertThat(src).hasSize(1);
        assertThat(src.get(0).get("kind").asText()).isEqualTo("LEVER");
        assertThat(src.get(0).get("via").asText()).isEqualTo("Lever");
        assertThat(src.get(0).get("externalId").asText()).isEqualTo("top");
        assertThat(src.get(0).get("url").asText()).isEqualTo("https://jobs.lever.co/acme/top");
        assertThat(src.get(0).get("closedAt").isNull()).isTrue();

        JsonNode n = t.get("notification");
        assertThat(n.get("status").asText()).isEqualTo("PENDING");
        assertThat(n.get("channel").asText()).isEqualTo("EMAIL");
        assertThat(n.get("sentAt").isNull()).isTrue();

        JsonNode salary = t.get("salary");
        assertThat(salary.get("min").decimalValue()).isEqualByComparingTo("50000");
        assertThat(salary.get("max").decimalValue()).isEqualByComparingTo("70000");
        assertThat(salary.get("currency").asText()).isEqualTo("ZAR");
        assertThat(salary.get("period").asText()).isEqualTo("MONTH");
        assertThat(salary.get("estimated").asBoolean()).isFalse();
        assertThat(t.has("description")).as("lists omit the description").isFalse();

        JsonNode l = item(page, low);
        assertThat(l.get("score").asDouble()).isCloseTo(0.4, within(1e-9));
        assertThat(texts(l.get("missingRequired"))).containsExactly("Python");
        assertThat(l.get("notification").isNull()).isTrue();
        assertThat(l.get("salary").isNull()).isTrue();

        JsonNode f = item(page, filtered);
        assertThat(f.get("preferenceVerdict").asText()).isEqualTo("FILTERED");
        assertThat(texts(f.get("filterReasons"))).containsExactly("TITLE");
        page.get("content").forEach(i -> assertThat(i.has("description")).isFalse());
    }

    // ------------------------------------------------------------------ detail

    @Test
    void detail() {
        fixture(true);
        Res r = api.get("/api/feed/jobs/" + top);
        assertThat(r.status()).isEqualTo(200);
        JsonNode d = r.json();
        assertThat(d.get("jobId").asText()).isEqualTo(top.toString());
        assertThat(d.get("description").asText()).contains("Kubernetes is advantageous");
        assertThat(d.get("score").asDouble()).isCloseTo(0.8, within(1e-9));
        assertThat(d.get("notification").get("status").asText()).isEqualTo("PENDING");
        // closed, filtered and baseline jobs are readable by id
        assertThat(api.get("/api/feed/jobs/" + closed).json().get("closedAt").isNull()).isFalse();
        assertThat(api.get("/api/feed/jobs/" + base).json().get("baseline").asBoolean()).isTrue();
    }

    @Test
    void detailErrors() {
        skills("Java");
        assertError(api.get("/api/feed/jobs/" + UUID.randomUUID()), 404, "JOB_NOT_FOUND");
        UUID manual = job("Backend Engineer", "Acme", "Java");
        JsonNode err = assertError(api.get("/api/feed/jobs/" + manual), 404, "JOB_NOT_FOUND");
        assertThat(err.get("message").asText()).contains("is not from the job feed");
        assertError(api.get("/api/feed/jobs/not-a-uuid"), 400, "INVALID_ID");
    }

    // ------------------------------------------------------------------ status

    @Test
    void statusShowsProfileAndPreferencesVersions() {
        JsonNode empty = api.get("/api/feed/status").json();
        assertThat(empty.get("profile").get("present").asBoolean()).isFalse();
        assertThat(empty.get("profile").get("version").isNull()).isTrue();
        assertThat(empty.get("preferences").get("present").asBoolean()).isFalse();
        assertThat(empty.get("preferences").get("version").isNull()).isTrue();

        skills("Java", "SQL");
        profile("Java", "SQL");
        profile("Java");
        preferences("{}");
        JsonNode s = api.get("/api/feed/status").json();
        int version = jdbc.queryForObject("SELECT version FROM owner_profile", Integer.class);
        assertThat(s.get("profile").get("present").asBoolean()).isTrue();
        assertThat(s.get("profile").get("version").asInt()).isEqualTo(version);
        assertThat(s.get("profile").get("appliedVersion").asInt()).isEqualTo(version);
        assertThat(s.get("preferences").get("present").asBoolean()).isTrue();
        assertThat(s.get("preferences").get("version").asInt()).isEqualTo(1);

        jdbc.update("UPDATE feed_state SET applied_profile_version = 1");
        assertThat(api.get("/api/feed/status").json().get("profile").get("appliedVersion").asInt()).isEqualTo(1);
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }
}
