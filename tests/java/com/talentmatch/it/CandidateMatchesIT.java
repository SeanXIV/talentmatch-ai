package com.talentmatch.it;

import static com.talentmatch.support.SeekerFixtures.feed;
import static com.talentmatch.support.SeekerFixtures.feedJob;
import static com.talentmatch.support.SeekerFixtures.jobIds;
import static com.talentmatch.support.SeekerFixtures.setCreatedAt;
import static com.talentmatch.support.SeekerFixtures.titles;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import com.talentmatch.repository.UuidOrder;
import java.sql.Timestamp;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Phase 6 step 4, §9.2 items 2–6, 14, 15 for {@code GET /api/candidates/{id}/matches} (AI off: every
 * explanation is the AI_DISABLED template). Feed rows are written by SQL.
 */
@ExtendWith(OutputCaptureExtension.class)
class CandidateMatchesIT extends AbstractApiIT {

    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    private UUID cand;

    @BeforeEach
    void baseline() {
        skills("Java", "SQL", "Docker", "Kubernetes", "Go");
        cand = candidate("Ada Lovelace", "ada@example.com", "Java:5", "SQL");
    }

    private Res get(UUID candidateId, String query) {
        return api.get("/api/candidates/" + candidateId + "/matches" + (query == null ? "" : "?" + query));
    }

    private JsonNode ok(String query) {
        return ok(cand, query);
    }

    private JsonNode ok(UUID candidateId, String query) {
        Res r = get(candidateId, query);
        assertThat(r.status()).as("GET candidate matches %s", r).isEqualTo(200);
        return r.json();
    }

    private static List<Double> scores(JsonNode page) {
        List<Double> out = new ArrayList<>();
        page.get("matches").forEach(m -> out.add(m.get("score").asDouble()));
        return out;
    }

    private static JsonNode item(JsonNode page, UUID jobId) {
        for (JsonNode m : page.get("matches")) {
            if (m.get("jobId").asText().equals(jobId.toString())) {
                return m;
            }
        }
        throw new AssertionError("job " + jobId + " not in " + page);
    }

    private Instant computedAt(UUID jobId) {
        return jdbc.queryForObject("SELECT computed_at FROM job_match WHERE job_id = ? AND candidate_id = ?",
                Timestamp.class, jobId, cand).toInstant();
    }

    // ------------------------------------------------------------------ §9.2 item 2: ranking basics

    @Test
    void ranksByScoreThenListedAtThenIdWithRanksAcrossPages() {
        UUID full = job("Full Match", "Acme", "Java", "SQL");                  // 1.0
        UUID most = job("Most", "Acme", "Java", "SQL", "~Docker");             // 0.8
        UUID halfOld = job("Half Old", "Acme", "Java", "Go");                  // 0.5
        UUID halfNew = job("Half New", "Acme", "Java", "Go");                  // 0.5
        UUID tieA = job("Tie A", "Acme", "SQL", "Kubernetes");                 // 0.5
        UUID tieB = job("Tie B", "Acme", "SQL", "Go");                         // 0.5
        UUID zero = job("Zero", "Acme", "Go");                                 // 0
        setCreatedAt(jdbc, halfOld, NOW.minus(Duration.ofDays(3)));
        setCreatedAt(jdbc, halfNew, NOW.minus(Duration.ofDays(1)));
        Instant same = NOW.minus(Duration.ofDays(2));
        setCreatedAt(jdbc, tieA, same);
        setCreatedAt(jdbc, tieB, same);
        List<UUID> ties = new ArrayList<>(List.of(tieA, tieB));
        ties.sort(UuidOrder.COMPARATOR);

        JsonNode first = ok(null);
        assertThat(first.get("recomputedJobs").asInt()).isEqualTo(7);
        assertThat(first.get("totalElements").asLong()).isEqualTo(7);
        assertThat(first.get("totalPages").asInt()).isEqualTo(1);
        assertThat(first.get("candidateId").asText()).isEqualTo(cand.toString());
        assertThat(first.get("owner").asBoolean()).isFalse();
        assertThat(first.get("profileVersion").isNull()).isTrue();
        assertThat(first.get("sort").asText()).isEqualTo("SCORE");
        assertThat(first.get("page").asInt()).isZero();
        assertThat(first.get("limit").asInt()).isEqualTo(10);
        assertThat(first.get("unmatchableJobs").asLong()).isZero();
        assertThat(first.get("reason").isNull()).isTrue();
        assertThat(first.get("message").isNull()).isTrue();
        assertThat(jobIds(first)).containsExactly(full, most, halfNew, ties.get(0), ties.get(1), halfOld, zero);
        assertThat(scores(first)).containsExactly(1.0, 0.8, 0.5, 0.5, 0.5, 0.5, 0.0);
        List<Integer> ranks = new ArrayList<>();
        first.get("matches").forEach(m -> ranks.add(m.get("rank").asInt()));
        assertThat(ranks).containsExactly(1, 2, 3, 4, 5, 6, 7);

        JsonNode top = item(first, full);
        assertThat(top.get("scorePercent").asInt()).isEqualTo(100);
        assertThat(top.get("origin").asText()).isEqualTo("MANUAL");
        assertThat(top.get("listedAtSource").asText()).isEqualTo("CREATED");
        assertThat(top.get("listedAt").isNull()).isFalse();
        assertThat(top.get("summary").asText()).isEqualTo("Matches 2 of 2 required skills.");
        assertThat(top.at("/breakdown/matchedRequired/0/name").asText()).isEqualTo("Java");
        assertThat(top.at("/breakdown/matchedRequired/0/yearsExperience").asInt()).isEqualTo(5);
        assertThat(top.at("/breakdown/earnedPoints").asInt()).isEqualTo(20);
        assertThat(top.at("/breakdown/maxPoints").asInt()).isEqualTo(20);
        assertThat(item(first, most).at("/breakdown/missingNiceToHave/0/name").asText()).isEqualTo("Docker");
        // MANUAL jobs: feed-only fields null, countryCodes []; non-owner preference fields null / []
        for (String f : new String[] {"primaryUrl", "firstSeenAt", "postedAt", "closedAt", "baseline", "workplace",
                "locationText", "seniority", "salary", "enrichmentStatus", "preferenceVerdict"}) {
            assertThat(top.get(f)).as(f).isNotNull();
            assertThat(top.get(f).isNull()).as(f).isTrue();
        }
        assertThat(top.get("countryCodes").isArray()).isTrue();
        assertThat(top.get("countryCodes")).isEmpty();
        assertThat(top.get("filterReasons")).isEmpty();
        assertThat(top.get("flags")).isEmpty();
        // AI off: the seeker template with AI_DISABLED
        assertThat(top.get("explanationStatus").asText()).isEqualTo("UNAVAILABLE");
        assertThat(top.get("aiExplanation").isNull()).isTrue();
        assertThat(top.at("/explanation/source").asText()).isEqualTo("TEMPLATE");
        assertThat(top.at("/explanation/reason").asText()).isEqualTo("AI_DISABLED");
        assertThat(top.at("/explanation/text").asText()).contains("You").doesNotContain("Ada");
        assertThat(top.get("computedAt").isNull()).isFalse();

        // paging: same order, ranks continue, totals unchanged; nothing recomputed any more
        JsonNode p0 = ok("limit=3");
        JsonNode p1 = ok("limit=3&page=1");
        JsonNode p2 = ok("limit=3&page=2");
        JsonNode p3 = ok("limit=3&page=3");
        for (JsonNode p : List.of(p0, p1, p2, p3)) {
            assertThat(p.get("totalElements").asLong()).isEqualTo(7);
            assertThat(p.get("totalPages").asInt()).isEqualTo(3);
            assertThat(p.get("recomputedJobs").asInt()).isZero();
            assertThat(p.get("limit").asInt()).isEqualTo(3);
        }
        List<UUID> paged = new ArrayList<>();
        paged.addAll(jobIds(p0));
        paged.addAll(jobIds(p1));
        paged.addAll(jobIds(p2));
        assertThat(paged).isEqualTo(jobIds(first));
        assertThat(p1.get("matches").get(0).get("rank").asInt()).isEqualTo(4);
        assertThat(p2.get("matches").get(0).get("rank").asInt()).isEqualTo(7);
        assertThat(p3.get("matches")).isEmpty();
        assertThat(p3.get("page").asInt()).isEqualTo(3);
    }

    @Test
    void secondCallRecomputesNothingAndKeepsComputedAt() {
        UUID a = job("A", "Acme", "Java");
        job("B", "Acme", "SQL", "Go");
        assertThat(ok(null).get("recomputedJobs").asInt()).isEqualTo(2);
        Instant before = computedAt(a);
        JsonNode again = ok(null);
        assertThat(again.get("recomputedJobs").asInt()).isZero();
        assertThat(computedAt(a)).isEqualTo(before);
        assertThat(Instant.parse(item(again, a).get("computedAt").asText())).isEqualTo(before);
    }

    @Test
    void minScoreCutsTheListButNotTheScoringTargets() {
        job("Full", "Acme", "Java", "SQL");       // 1.0
        job("Half", "Acme", "Java", "Go");        // 0.5
        job("Zero", "Acme", "Go");                // 0
        JsonNode high = ok("minScore=0.6");
        assertThat(high.get("recomputedJobs").asInt()).as("every in-scope job is scored").isEqualTo(3);
        assertThat(titles(high)).containsExactly("Full");
        assertThat(high.get("totalElements").asLong()).isEqualTo(1);
        assertThat(titles(ok("minScore=0.5"))).containsExactly("Full", "Half");
        assertThat(titles(ok(null))).as("0-score jobs are listed by default").containsExactly("Full", "Half", "Zero");
        assertThat(titles(ok("minScore=1"))).containsExactly("Full");
        JsonNode none = ok("minScore=1&origin=feed");
        assertThat(none.get("totalElements").asLong()).isZero();
        assertThat(none.get("totalPages").asInt()).isZero();
    }

    @Test
    void sortNewestOrdersByListedAtThenScoreThenId() {
        UUID oldFull = job("Old Full", "Acme", "Java", "SQL");
        UUID newHalf = job("New Half", "Acme", "Java", "Go");
        UUID newFull = job("New Full", "Acme", "Java", "SQL", "~Kubernetes");
        Instant t = NOW.minus(Duration.ofHours(1));
        setCreatedAt(jdbc, oldFull, NOW.minus(Duration.ofDays(5)));
        setCreatedAt(jdbc, newHalf, t);
        setCreatedAt(jdbc, newFull, t);
        JsonNode newest = ok("sort=NeWeSt");
        assertThat(newest.get("sort").asText()).isEqualTo("NEWEST");
        assertThat(jobIds(newest)).containsExactly(newFull, newHalf, oldFull);
        assertThat(jobIds(ok("sort=score"))).containsExactly(oldFull, newFull, newHalf);
    }

    // ------------------------------------------------------------------ §9.2 item 3: two-way staleness

    @Test
    void candidateSideAndJobSideReuseEachOthersScores() {
        UUID a = job("A", "Acme", "Java", "SQL");
        UUID b = job("B", "Acme", "Java", "Go");
        assertThat(ok(null).get("recomputedJobs").asInt()).isEqualTo(2);
        assertThat(matches(a).get("recomputedCandidates").asInt()).as("job side reuses the row").isZero();

        UUID c = job("C", "Acme", "SQL");
        assertThat(matches(c).get("recomputedCandidates").asInt()).isEqualTo(1);
        assertThat(ok(null).get("recomputedJobs").asInt()).as("candidate side reuses the job side's row").isZero();

        // editing one job's skills → exactly that job is rescored
        Res put = api.put("/api/jobs/" + b, jobBody("B", "Acme", null, "Java", "SQL"));
        assertThat(put.status()).as(put.toString()).isEqualTo(200);
        JsonNode after = ok(null);
        assertThat(after.get("recomputedJobs").asInt()).isEqualTo(1);
        assertThat(item(after, b).get("score").asDouble()).isEqualTo(1.0);

        // editing the candidate → every in-scope job is rescored
        Res edit = api.put("/api/candidates/" + cand, candidateBody("Ada Lovelace", "ada@example.com", null,
                "Java:6", "SQL"));
        assertThat(edit.status()).as(edit.toString()).isEqualTo(200);
        assertThat(ok(null).get("recomputedJobs").asInt()).isEqualTo(3);
        assertThat(ok(null).get("recomputedJobs").asInt()).isZero();
    }

    @Test
    void onlyInScopeJobsAreRescored() {
        job("Manual", "Acme", "Java");
        feedJob(jdbc, "Feed One", "Acme", feed(), "Java");
        feedJob(jdbc, "Feed Two", "Acme", feed(), "SQL");
        JsonNode manual = ok("origin=manual");
        assertThat(manual.get("recomputedJobs").asInt()).isEqualTo(1);
        assertThat(titles(manual)).containsExactly("Manual");
        JsonNode all = ok(null);
        assertThat(all.get("recomputedJobs").asInt()).as("the feed jobs only now").isEqualTo(2);
        assertThat(all.get("totalElements").asLong()).isEqualTo(3);

        // a candidate edit makes everything stale; a narrow request rescores only its scope
        Res edit = api.put("/api/candidates/" + cand, candidateBody("Ada Lovelace", "ada@example.com", null, "Java"));
        assertThat(edit.status()).as(edit.toString()).isEqualTo(200);
        assertThat(ok("origin=feed").get("recomputedJobs").asInt()).isEqualTo(2);
        assertThat(ok(null).get("recomputedJobs").asInt()).isEqualTo(1);
    }

    // ------------------------------------------------------------------ §9.2 item 4: not matchable

    @Test
    void jobsWithoutSkillsAreCountedButNotRanked() {
        job("Ranked", "Acme", "Java");
        jobWithoutSkills("Skill-less", "Acme");
        UUID lost = job("Lost Skills", "Acme", "SQL");
        JsonNode before = ok(null);
        assertThat(titles(before)).containsExactlyInAnyOrder("Ranked", "Lost Skills");
        assertThat(before.get("unmatchableJobs").asLong()).isEqualTo(1);

        jdbc.update("DELETE FROM job_skill WHERE job_id = ?", lost);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_match WHERE job_id = ?", Integer.class, lost))
                .as("old row kept").isEqualTo(1);
        JsonNode after = ok(null);
        assertThat(titles(after)).containsExactly("Ranked");
        assertThat(after.get("totalElements").asLong()).isEqualTo(1);
        assertThat(after.get("unmatchableJobs").asLong()).isEqualTo(2);
        assertThat(after.get("reason").isNull()).isTrue();
    }

    @Test
    void onlyUnmatchableJobsGiveNoMatchableJobs() {
        jobWithoutSkills("Skill-less", "Acme");
        JsonNode page = ok(null);
        assertThat(page.get("matches")).isEmpty();
        assertThat(page.get("totalElements").asLong()).isZero();
        assertThat(page.get("totalPages").asInt()).isZero();
        assertThat(page.get("unmatchableJobs").asLong()).isEqualTo(1);
        assertThat(page.get("reason").asText()).isEqualTo("NO_MATCHABLE_JOBS");
        assertThat(page.get("message").asText()).startsWith("No job with listed skills matches these filters.");

        // no jobs at all → same reason
        jdbc.update("DELETE FROM job");
        assertThat(ok(null).get("reason").asText()).isEqualTo("NO_MATCHABLE_JOBS");

        // a matchable job outside the scope: still NO_MATCHABLE_JOBS for that scope
        job("Manual", "Acme", "Java");
        assertThat(ok("origin=feed").get("reason").asText()).isEqualTo("NO_MATCHABLE_JOBS");
        assertThat(ok(null).get("reason").isNull()).isTrue();
    }

    @Test
    void aCandidateWithoutSkillsGetsEveryJobAtZero() {
        UUID none = candidate("Nobody", "nobody@example.com");
        job("A", "Acme", "Java");
        job("B", "Acme", "SQL", "~Go");
        JsonNode page = ok(none, null);
        assertThat(page.get("reason").asText()).isEqualTo("CANDIDATE_HAS_NO_SKILLS");
        assertThat(page.get("message").asText()).startsWith("This candidate has no skills yet");
        assertThat(scores(page)).containsExactly(0.0, 0.0);
        assertThat(page.get("recomputedJobs").asInt()).isEqualTo(2);
        JsonNode cut = ok(none, "minScore=0.5");
        assertThat(cut.get("matches")).isEmpty();
        assertThat(cut.get("reason").asText()).as("not NO_MATCHABLE_JOBS: jobs exist")
                .isEqualTo("CANDIDATE_HAS_NO_SKILLS");
    }

    // ------------------------------------------------------------------ §9.2 item 5: dates (decision d)

    @Test
    void listedAtAndItsSource() {
        UUID posted = feedJob(jdbc, "Posted", "Acme", feed().posted(NOW.minus(Duration.ofDays(1)))
                .firstSeen(NOW.minus(Duration.ofHours(1))), "Java");
        UUID skew = feedJob(jdbc, "Skewed", "Acme", feed().posted(NOW.minus(Duration.ofHours(1)))
                .firstSeen(NOW.minus(Duration.ofDays(3))), "Java");
        UUID seen = feedJob(jdbc, "Seen", "Acme", feed().firstSeen(NOW.minus(Duration.ofHours(2))), "Java");
        UUID base = feedJob(jdbc, "Baseline", "Acme", feed().baseline().firstSeen(NOW.minus(Duration.ofMinutes(10))),
                "Java");
        UUID manual = job("Manual", "Acme", "Java");
        setCreatedAt(jdbc, manual, NOW.minus(Duration.ofHours(5)));

        JsonNode page = ok("sort=newest");
        assertThat(jobIds(page)).containsExactly(seen, manual, posted, skew, base);
        assertThat(item(page, posted).get("listedAtSource").asText()).isEqualTo("POSTED");
        assertThat(Instant.parse(item(page, posted).get("listedAt").asText())).isEqualTo(NOW.minus(Duration.ofDays(1)));
        assertThat(item(page, skew).get("listedAtSource").asText()).isEqualTo("POSTED");
        assertThat(Instant.parse(item(page, skew).get("listedAt").asText())).as("LEAST(posted, firstSeen)")
                .isEqualTo(NOW.minus(Duration.ofDays(3)));
        assertThat(item(page, seen).get("listedAtSource").asText()).isEqualTo("FIRST_SEEN");
        assertThat(Instant.parse(item(page, seen).get("listedAt").asText())).isEqualTo(NOW.minus(Duration.ofHours(2)));
        assertThat(item(page, base).get("listedAtSource").asText()).isEqualTo("UNKNOWN");
        assertThat(item(page, base).get("listedAt").isNull()).isTrue();
        assertThat(item(page, base).get("baseline").asBoolean()).isTrue();
        assertThat(item(page, manual).get("listedAtSource").asText()).isEqualTo("CREATED");
        assertThat(Instant.parse(item(page, manual).get("listedAt").asText())).isEqualTo(NOW.minus(Duration.ofHours(5)));
        // feed fields
        JsonNode p = item(page, posted);
        assertThat(p.get("origin").asText()).isEqualTo("FEED");
        assertThat(p.get("primaryUrl").asText()).startsWith("https://jobs.example/");
        assertThat(Instant.parse(p.get("postedAt").asText())).isEqualTo(NOW.minus(Duration.ofDays(1)));
        assertThat(Instant.parse(p.get("firstSeenAt").asText())).isEqualTo(NOW.minus(Duration.ofHours(1)));
        assertThat(p.get("closedAt").isNull()).isTrue();
        assertThat(p.get("workplace").asText()).isEqualTo("UNKNOWN");
        assertThat(p.get("seniority").asText()).isEqualTo("UNKNOWN");
        assertThat(p.get("enrichmentStatus").asText()).isEqualTo("PENDING");
        assertThat(p.get("salary").isNull()).isTrue();

        // score sort: equal scores → listedAt desc, undated last
        assertThat(jobIds(ok(null))).containsExactly(seen, manual, posted, skew, base);

        // postedSince: undated and older jobs drop out; the skewed one uses its first-seen date
        JsonNode p2d = ok("postedSince=P2D&sort=newest");
        assertThat(jobIds(p2d)).containsExactly(seen, manual, posted);
        assertThat(jobIds(ok("postedSince=PT3H"))).containsExactly(seen);
        String since = NOW.minus(Duration.ofHours(6)).toString();
        assertThat(jobIds(ok("postedSince=" + since))).containsExactly(seen, manual);
        assertThat(jobIds(ok("postedSince=P366D"))).as("undated still out").doesNotContain(base).hasSize(4);
        assertThat(jobIds(ok(null))).as("undated listed without postedSince").contains(base);
    }

    // ------------------------------------------------------------------ §9.2 item 6: filters (decision e/f)

    @Test
    void countryAndRemoteFilters() {
        UUID za = feedJob(jdbc, "ZA Onsite", "Acme", feed().workplace("ONSITE").countries("ZA").location("Cape Town"),
                "Java");
        UUID gb = feedJob(jdbc, "GB Onsite", "Acme", feed().workplace("ONSITE").countries("GB").location("London"),
                "Java");
        UUID gbRemote = feedJob(jdbc, "GB Remote", "Acme", feed().workplace("REMOTE").countries("GB"), "Java");
        UUID nowhere = feedJob(jdbc, "No Country", "Acme", feed().workplace("HYBRID"), "Java");
        UUID multi = feedJob(jdbc, "Multi", "Acme", feed().workplace("HYBRID").countries("DE", "ZA"), "Java");
        UUID manual = job("Manual", "Acme", "Java");

        assertThat(jobIds(ok("country=ZA"))).containsExactlyInAnyOrder(za, gbRemote, nowhere, multi, manual);
        assertThat(jobIds(ok("country=za&remote=include"))).containsExactlyInAnyOrder(za, gbRemote, nowhere, multi,
                manual);
        assertThat(jobIds(ok("country=ZA&remote=exclude"))).containsExactlyInAnyOrder(za, nowhere, multi, manual);
        assertThat(jobIds(ok("country=ZA,GB"))).hasSize(6);
        assertThat(jobIds(ok("country=US&country=FR"))).containsExactlyInAnyOrder(gbRemote, nowhere, manual);
        assertThat(jobIds(ok("remote=only"))).containsExactly(gbRemote);
        assertThat(jobIds(ok("remote=only&country=ZA"))).containsExactly(gbRemote);
        assertThat(jobIds(ok("remote=exclude"))).containsExactlyInAnyOrder(za, gb, nowhere, multi, manual);
        JsonNode zaItem = item(ok("country=ZA"), za);
        assertThat(zaItem.get("countryCodes")).hasSize(1);
        assertThat(zaItem.get("countryCodes").get(0).asText()).isEqualTo("ZA");
        assertThat(zaItem.get("workplace").asText()).isEqualTo("ONSITE");
        assertThat(zaItem.get("locationText").asText()).isEqualTo("Cape Town");
        assertThat(item(ok(null), multi).get("countryCodes").toString()).isEqualTo("[\"DE\",\"ZA\"]");
        // step 5 (§12.5): the computed LOCATION_UNKNOWN is shown to any caller who passes country
        JsonNode zaPage = ok("country=ZA");
        assertThat(item(zaPage, nowhere).get("flags").toString()).isEqualTo("[\"LOCATION_UNKNOWN\"]");
        for (UUID other : List.of(za, gbRemote, multi, manual)) {
            assertThat(item(zaPage, other).get("flags")).as("no computed flag").isEmpty();
        }
        assertThat(item(ok(null), nowhere).get("flags")).as("no country filter").isEmpty();
    }

    @Test
    void locationIsAStrictCaseInsensitiveEscapedSubstring() {
        UUID cape = feedJob(jdbc, "Cape", "Acme", feed().location("Cape Town, South Africa"), "Java");
        UUID pct = feedJob(jdbc, "Pct", "Acme", feed().location("Office 50% remote"), "Java");
        UUID five = feedJob(jdbc, "Five", "Acme", feed().location("Office 500 West"), "Java");
        UUID under = feedJob(jdbc, "Under", "Acme", feed().location("Zone a_b"), "Java");
        UUID axb = feedJob(jdbc, "Axb", "Acme", feed().location("Zone axb"), "Java");
        UUID slash = feedJob(jdbc, "Slash", "Acme", feed().location("Path C:\\jobs"), "Java");
        feedJob(jdbc, "Nowhere", "Acme", feed(), "Java");
        job("Manual", "Acme", "Java");

        assertThat(jobIds(ok("location=cape"))).containsExactly(cape);
        assertThat(jobIds(ok("location=CAPE%20%20town"))).as("collapsed").containsExactly(cape);
        assertThat(jobIds(ok("location=%20south%20africa%20"))).containsExactly(cape);
        assertThat(jobIds(ok("location=50%25"))).as("% is literal").containsExactly(pct);
        assertThat(jobIds(ok("location=a_b"))).as("_ is literal").containsExactly(under);
        assertThat(jobIds(ok("location=C:%5Cj"))).as("backslash is literal").containsExactly(slash);
        assertThat(jobIds(ok("location=Office"))).containsExactlyInAnyOrder(pct, five);
        assertThat(jobIds(ok("location=zone"))).containsExactlyInAnyOrder(under, axb);
        assertThat(ok("location=Durban").get("matches")).isEmpty();
        assertThat(ok("location=Durban").get("reason").asText()).isEqualTo("NO_MATCHABLE_JOBS");
    }

    @Test
    void originClosedAndPreferenceVerdictsForANonOwner() {
        UUID manual = job("Manual", "Acme", "Java");
        UUID open = feedJob(jdbc, "Open", "Acme", feed(), "Java");
        UUID closed = feedJob(jdbc, "Closed", "Acme", feed().closed(NOW.minus(Duration.ofHours(1))), "Java");
        UUID filtered = feedJob(jdbc, "Filtered", "Acme",
                feed().verdict("FILTERED", "[\"Seniority is below your minimum\"]"), "Java");

        assertThat(jobIds(ok("origin=FEED"))).containsExactlyInAnyOrder(open, filtered);
        assertThat(jobIds(ok("origin=manual"))).containsExactly(manual);
        assertThat(jobIds(ok("origin=all"))).containsExactlyInAnyOrder(manual, open, filtered);
        JsonNode withClosed = ok("includeClosed=true");
        assertThat(jobIds(withClosed)).containsExactlyInAnyOrder(manual, open, closed, filtered);
        assertThat(Instant.parse(item(withClosed, closed).get("closedAt").asText()))
                .isEqualTo(NOW.minus(Duration.ofHours(1)));
        assertThat(jobIds(ok("includeClosed=false"))).doesNotContain(closed);
        // non-owner: FILTERED is listed and preference fields stay empty
        JsonNode f = item(ok(null), filtered);
        assertThat(f.get("preferenceVerdict").isNull()).isTrue();
        assertThat(f.get("filterReasons")).isEmpty();
        assertThat(f.get("flags")).isEmpty();
    }

    // ------------------------------------------------------------------ §9.2 item 14: errors

    @Test
    void unknownCandidateIs404AndBadIdIs400() {
        UUID unknown = UUID.randomUUID();
        JsonNode err = assertError(get(unknown, null), 404, "CANDIDATE_NOT_FOUND");
        assertThat(err.get("message").asText()).contains(unknown.toString());
        assertError(get(unknown, "minScore=0.5&sort=newest"), 404, "CANDIDATE_NOT_FOUND");
        assertError(api.get("/api/candidates/not-a-uuid/matches"), 400, "INVALID_ID");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_match", Integer.class)).isZero();
    }

    @Test
    void everyParameterErrorHasTheFullShape() {
        Map<String, String[]> cases = new java.util.LinkedHashMap<>();
        cases.put("limit=0", new String[] {"limit", "limit must be between 1 and 100."});
        cases.put("limit=101", new String[] {"limit", "limit must be between 1 and 100."});
        cases.put("page=-1", new String[] {"page", "page must be 0 or greater."});
        cases.put("page=2147483647&limit=2", new String[] {"page", "page is too large for this page size."});
        cases.put("minScore=1.5", new String[] {"minScore", "minScore must be between 0 and 1."});
        cases.put("minScore=NaN", new String[] {"minScore", "minScore must be between 0 and 1."});
        cases.put("sort=date", new String[] {"sort", "sort must be score or newest."});
        cases.put("postedSince=P0D", new String[] {"postedSince", "postedSince must be an ISO-8601 instant like "
                + "2026-10-16T00:00:00Z or a duration like P2D (at most P366D)."});
        cases.put("postedSince=-P2D", new String[] {"postedSince", null});
        cases.put("postedSince=P400D", new String[] {"postedSince", null});
        cases.put("postedSince=P1M", new String[] {"postedSince", null});
        cases.put("postedSince=yesterday", new String[] {"postedSince", null});
        cases.put("country=XX", new String[] {"country", "'XX' is not an ISO country code."});
        cases.put("country=AD,AE,AF,AG,AI,AL,AM,AO,AQ,AR,AS,AT,AU,AW,AX,AZ,BA,BB,BD,BE,BF",
                new String[] {"country", "Use at most 20 country codes."});
        cases.put("remote=yes", new String[] {"remote", "remote must be include, only or exclude."});
        cases.put("location=a", new String[] {"location", "location must be 2 to 100 characters."});
        cases.put("location=" + "x".repeat(101), new String[] {"location", "location must be 2 to 100 characters."});
        cases.put("origin=etl", new String[] {"origin", "origin must be all, feed or manual."});
        for (Map.Entry<String, String[]> c : cases.entrySet()) {
            JsonNode err = assertError(get(cand, c.getKey()), 400, "INVALID_PARAMETER");
            assertThat(fields(err)).as(c.getKey()).containsExactly(c.getValue()[0]);
            if (c.getValue()[1] != null) {
                assertThat(err.get("message").asText()).as(c.getKey()).isEqualTo(c.getValue()[1]);
                assertThat(fieldMessages(err).get(c.getValue()[0])).as(c.getKey()).isEqualTo(c.getValue()[1]);
            }
        }
        // strict boolean binding and typed numbers
        JsonNode bool = assertError(get(cand, "includeClosed=maybe"), 400, "INVALID_PARAMETER");
        assertThat(fields(bool)).containsExactly("includeClosed");
        assertThat(bool.get("message").asText()).isEqualTo("Parameter 'includeClosed' must be true or false.");
        assertThat(fields(assertError(get(cand, "limit=ten"), 400, "INVALID_PARAMETER"))).containsExactly("limit");
        assertThat(fields(assertError(get(cand, "minScore=high"), 400, "INVALID_PARAMETER")))
                .containsExactly("minScore");
        // validation happens before the candidate lookup: an unknown candidate with a bad param is 400
        assertError(get(UUID.randomUUID(), "sort=date"), 400, "INVALID_PARAMETER");
        // blank values mean the default
        assertThat(ok("sort=&remote=&origin=&location=%20&postedSince=&country=").get("sort").asText())
                .isEqualTo("SCORE");
    }

    // ------------------------------------------------------------------ §9.2 item 15 / §6: logging

    @Test
    void logsCarryIdsAndCountsOnly(CapturedOutput output) {
        Res r = api.post("/api/jobs", jobBody("Backend", "Acme", "TOP-SECRET-DESCRIPTION payments", "Java"));
        assertThat(r.status()).isEqualTo(201);
        UUID withSummary = UUID.fromString(api.post("/api/candidates", candidateBody("Grace Hopper",
                "grace@example.com", "PRIVATE-SUMMARY-TEXT compilers", "Java")).json().get("id").asText());
        feedJob(jdbc, "Feed", "Acme", feed().location("Secretville 9").description("FEED-SECRET-TEXT"), "Java");
        int mark = output.getAll().length();

        JsonNode page = ok(withSummary, "location=Secretville&sort=newest");
        assertThat(page.get("totalElements").asLong()).isEqualTo(1);
        ok(withSummary, null);

        String logs = output.getAll().substring(mark);
        assertThat(logs).containsPattern("Candidate matches candidate=" + withSummary + " owner=false sort=NEWEST "
                + "targets=\\d+ recomputed=\\d+ total=1 generated=0 latencyMs=\\d+");
        assertThat(logs).contains("Candidate matches candidate=" + withSummary + " owner=false sort=SCORE targets=1");
        assertThat(logs).doesNotContain("Secretville").doesNotContain("TOP-SECRET-DESCRIPTION")
                .doesNotContain("PRIVATE-SUMMARY-TEXT").doesNotContain("FEED-SECRET-TEXT")
                .doesNotContain("Grace Hopper").doesNotContain("grace@example.com");
    }

    /** Sanity for the fixture: PostgreSQL uuid order equals the order the API uses for ties. */
    @Test
    void uuidTieBreakFollowsPostgresOrder() {
        List<UUID> ids = new ArrayList<>();
        Instant same = NOW.minus(Duration.ofDays(1));
        for (int i = 0; i < 6; i++) {
            UUID id = job("Tie " + i, "Acme", "Java");
            setCreatedAt(jdbc, id, same);
            ids.add(id);
        }
        List<UUID> expected = jdbc.queryForList("SELECT id FROM job ORDER BY id", UUID.class);
        assertThat(jobIds(ok(null))).isEqualTo(expected);
        ids.sort(UuidOrder.COMPARATOR);
        assertThat(ids).isEqualTo(expected);
    }
}
