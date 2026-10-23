package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import com.talentmatch.support.MutableClock;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

/**
 * Phase 7 §9.2 item 2: the tracker endpoints (create, list, get, update, delete) with step 1's rules.
 * Documents are inserted directly via SQL (no document endpoints yet) to exercise the sent-version
 * validation and defaults, and the cascade through the API's DELETE. A movable clock sits near midnight
 * UTC to prove {@code appliedOn} uses talentmatch.applications.zone (Africa/Johannesburg, UTC+2).
 */
@Import(ApplicationTrackerIT.ClockConfig.class)
class ApplicationTrackerIT extends AbstractApiIT {

    private static final String BASE = "/api/applications";
    private static final String HASH = "c".repeat(64);
    private static final String SERVICE_BUSY =
            "The server is busy with a conflicting update. Please try again in a few seconds.";
    private static final String STATUS_FILTER = "status must be a comma-separated list of NEW, SHORTLISTED, APPLIED, "
            + "INTERVIEW, OFFER, REJECTED, WITHDRAWN, DISMISSED.";
    private static final String UPDATE_STATUS = "status must be one of NEW, SHORTLISTED, APPLIED, INTERVIEW, OFFER, "
            + "REJECTED, WITHDRAWN, DISMISSED.";
    private static final String APPLIED_ON_INVALID =
            "appliedOn must be a date like 2026-10-19, not in the future and not before 2000-01-01.";
    private static final String APPLIED_ON_TOO_EARLY =
            "appliedOn can only be set once the application is APPLIED or later.";

    private static final List<String> ALL = List.of("NEW", "SHORTLISTED", "APPLIED", "INTERVIEW", "OFFER", "REJECTED",
            "WITHDRAWN", "DISMISSED");
    private static final Map<String, List<String>> ALLOWED = Map.of(
            "NEW", List.of("SHORTLISTED", "APPLIED", "DISMISSED"),
            "SHORTLISTED", List.of("NEW", "APPLIED", "DISMISSED"),
            "APPLIED", List.of("INTERVIEW", "OFFER", "REJECTED", "WITHDRAWN"),
            "INTERVIEW", List.of("OFFER", "REJECTED", "WITHDRAWN"),
            "OFFER", List.of("REJECTED", "WITHDRAWN"),
            "REJECTED", List.of(),
            "WITHDRAWN", List.of(),
            "DISMISSED", List.of("NEW", "SHORTLISTED"));

    @TestConfiguration(proxyBeanMethods = false)
    static class ClockConfig {
        @Bean
        @Primary
        MutableClock applicationTestClock() {
            return MutableClock.ticking();
        }
    }

    @Autowired
    private MutableClock clock;

    private int jobCounter;

    @BeforeEach
    void resetClock() {
        moveClockTo(Instant.now());
        skills("Java");
    }

    // ------------------------------------------------------------------ helpers

    private void moveClockTo(Instant target) {
        clock.advance(Duration.between(clock.instant(), target));
    }

    /** Puts the clock at 23:30 UTC today: already tomorrow in Africa/Johannesburg (UTC+2). */
    private LocalDate nearMidnightUtc() {
        LocalDate utcToday = LocalDate.now(ZoneOffset.UTC);
        moveClockTo(utcToday.atTime(23, 30).toInstant(ZoneOffset.UTC));
        return utcToday;
    }

    private UUID newJob() {
        return job("Engineer " + (++jobCounter), "Acme", "Java");
    }

    /** A FEED job (job + feed_job row with the given URL). */
    private UUID feedJob(String title, String url) {
        UUID id = jdbc.queryForObject("INSERT INTO job (title, company, origin) VALUES (?, 'Feedco', 'FEED') "
                + "RETURNING id", UUID.class, title);
        jdbc.update("INSERT INTO feed_job (job_id, dedup_key, primary_url) VALUES (?, ?, ?)", id, "k-" + id, url);
        return id;
    }

    private Res create(Map<String, Object> body) {
        return api.post(BASE, api.json(body));
    }

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private JsonNode created(UUID jobId, Object... extra) {
        Map<String, Object> b = body(extra);
        b.put("jobId", jobId);
        Res r = create(b);
        assertThat(r.status()).as("create %s", r).isEqualTo(201);
        return r.json();
    }

    private UUID createdId(UUID jobId, Object... extra) {
        return UUID.fromString(created(jobId, extra).get("id").asText());
    }

    private Res put(UUID id, Object... kv) {
        return api.put(BASE + "/" + id, api.json(body(kv)));
    }

    private JsonNode putOk(UUID id, Object... kv) {
        Res r = put(id, kv);
        assertThat(r.status()).as("PUT %s", r).isEqualTo(200);
        return r.json();
    }

    private JsonNode get(UUID id) {
        Res r = api.get(BASE + "/" + id);
        assertThat(r.status()).as("GET %s", r).isEqualTo(200);
        return r.json();
    }

    /** Puts a new application for a fresh job into {@code status} (forced where needed). */
    private UUID applicationIn(String status) {
        UUID id = createdId(newJob());
        if (!status.equals("NEW")) {
            putOk(id, "status", status, "force", true);
        }
        return id;
    }

    private static boolean isNull(JsonNode n, String field) {
        return n.has(field) && n.get(field).isNull();
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static List<String> listIds(JsonNode page) {
        List<String> out = new ArrayList<>();
        page.get("content").forEach(n -> out.add(n.get("id").asText()));
        return out;
    }

    private static String fieldMessage(Res r, String field) {
        JsonNode b = assertError(r, 400, "VALIDATION_FAILED");
        return fieldMessages(b).get(field);
    }

    private int profileVersion() {
        Integer v = jdbc.queryForObject("SELECT max(version) FROM owner_profile_version", Integer.class);
        if (v != null) {
            return v;
        }
        return jdbc.queryForObject("INSERT INTO owner_profile_version (version, profile) VALUES (1, '{}') "
                + "RETURNING version", Integer.class);
    }

    private UUID doc(UUID app, String kind, int version, boolean approved) {
        UUID id = jdbc.queryForObject("INSERT INTO application_document (application_id, kind, version, source, "
                + "profile_version, content, model, input_hash) VALUES (?, ?, ?, 'AI', ?, '{}', 'fake/m', ?) "
                + "RETURNING id", UUID.class, app, kind, version, profileVersion(), HASH);
        if (approved) {
            jdbc.update("UPDATE application_document SET approved_at = now(), approval_acknowledged = false "
                    + "WHERE id = ?", id);
        }
        return id;
    }

    private String status(UUID id) {
        return jdbc.queryForObject("SELECT status FROM application WHERE id = ?", String.class, id);
    }

    // ------------------------------------------------------------------ create

    @Test
    void createDefaultsToNewWithSnapshotAndHistory() {
        UUID jobId = job("Backend Engineer", "Acme", "Java");
        Res r = create(body("jobId", jobId, "notes", "  Referred by Sam  "));
        assertThat(r.status()).as("%s", r).isEqualTo(201);
        JsonNode a = r.json();
        String id = a.get("id").asText();
        assertThat(r.header("Location")).endsWith("/api/applications/" + id);
        assertThat(a.get("jobId").asText()).isEqualTo(jobId.toString());
        assertThat(a.get("jobTitle").asText()).isEqualTo("Backend Engineer");
        assertThat(a.get("jobCompany").asText()).isEqualTo("Acme");
        assertThat(isNull(a, "jobUrl")).as("MANUAL job has no URL").isTrue();
        assertThat(a.get("jobExists").asBoolean()).isTrue();
        assertThat(a.get("jobClosed").asBoolean()).isFalse();
        assertThat(a.get("jobOrigin").asText()).isEqualTo("MANUAL");
        assertThat(a.get("status").asText()).isEqualTo("NEW");
        assertThat(Instant.parse(a.get("statusChangedAt").asText())).isNotNull();
        assertThat(isNull(a, "appliedOn")).isTrue();
        assertThat(a.get("notes").asText()).isEqualTo("Referred by Sam");
        assertThat(texts(a.get("allowedTransitions"))).containsExactly("SHORTLISTED", "APPLIED", "DISMISSED");
        assertThat(isNull(a, "sentCv")).isTrue();
        assertThat(isNull(a, "sentCoverLetter")).isTrue();
        for (String kind : List.of("cv", "coverLetter")) {
            JsonNode k = a.get("documents").get(kind);
            assertThat(isNull(k, "latest")).isTrue();
            assertThat(isNull(k, "approved")).isTrue();
            assertThat(k.get("versions").asInt()).isZero();
        }
        assertThat(isNull(a, "latestGeneration")).isTrue();
        assertThat(a.get("history")).hasSize(1);
        JsonNode e = a.get("history").get(0);
        assertThat(isNull(e, "from")).isTrue();
        assertThat(e.get("to").asText()).isEqualTo("NEW");
        assertThat(e.get("forced").asBoolean()).isFalse();
        assertThat(Instant.parse(e.get("at").asText())).isNotNull();
        assertThat(Instant.parse(a.get("createdAt").asText())).isNotNull();
        assertThat(Instant.parse(a.get("updatedAt").asText())).isNotNull();

        assertThat(get(UUID.fromString(id))).isEqualTo(a);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_event WHERE application_id = ?::uuid",
                Integer.class, id)).isEqualTo(1);
    }

    @Test
    void createShortlistedCaseInsensitiveAndBlankNotesAreNone() {
        JsonNode a = created(newJob(), "status", "shortlisted", "notes", "   ");
        assertThat(a.get("status").asText()).isEqualTo("SHORTLISTED");
        assertThat(isNull(a, "notes")).isTrue();
        assertThat(texts(a.get("allowedTransitions"))).containsExactly("NEW", "APPLIED", "DISMISSED");
        assertThat(a.get("history").get(0).get("to").asText()).isEqualTo("SHORTLISTED");
    }

    @Test
    void feedJobSnapshotHasTheUrlAndTheClosedFlag() {
        UUID jobId = feedJob("Platform Engineer", "https://jobs.example.test/acme/1");
        JsonNode a = created(jobId);
        assertThat(a.get("jobUrl").asText()).isEqualTo("https://jobs.example.test/acme/1");
        assertThat(a.get("jobOrigin").asText()).isEqualTo("FEED");
        assertThat(a.get("jobClosed").asBoolean()).isFalse();
        jdbc.update("UPDATE feed_job SET closed_at = now() WHERE job_id = ?", jobId);
        JsonNode after = get(UUID.fromString(a.get("id").asText()));
        assertThat(after.get("jobClosed").asBoolean()).isTrue();
        assertThat(after.get("jobExists").asBoolean()).isTrue();
    }

    @Test
    void createRejectsNonInitialStatusesAndBadFields() {
        UUID jobId = newJob();
        for (String status : List.of("APPLIED", "INTERVIEW", "OFFER", "REJECTED", "WITHDRAWN", "DISMISSED", "bogus",
                "")) {
            assertThat(fieldMessage(create(body("jobId", jobId, "status", status)), "status"))
                    .as(status).isEqualTo("A new application starts as NEW or SHORTLISTED.");
        }
        JsonNode err = assertError(create(body("status", "APPLIED", "notes", "x".repeat(10_001))), 400,
                "VALIDATION_FAILED");
        assertThat(fieldMessages(err)).containsEntry("jobId", "jobId is required.")
                .containsEntry("status", "A new application starts as NEW or SHORTLISTED.")
                .containsEntry("notes", "Notes can be at most 10000 characters.");
        assertThat(fieldMessage(create(body()), "jobId")).isEqualTo("jobId is required.");
        // 10000 characters (code points) is fine
        created(jobId, "notes", "😀".repeat(10_000));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application", Integer.class)).isEqualTo(1);
    }

    @Test
    void createForAnUnknownJobIs404() {
        UUID missing = UUID.randomUUID();
        assertError(create(body("jobId", missing)), 404, "JOB_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application", Integer.class)).isZero();
    }

    @Test
    void duplicateJobIs409WithTheExistingId() {
        UUID jobId = newJob();
        UUID first = createdId(jobId);
        JsonNode err = assertError(create(body("jobId", jobId, "status", "SHORTLISTED")), 409,
                "APPLICATION_ALREADY_EXISTS");
        assertThat(err.get("message").asText()).isEqualTo("This job is already tracked as application " + first
                + ". Open it with GET /api/applications/" + first + ".");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application", Integer.class)).isEqualTo(1);
    }

    @Test
    void unknownJsonFieldsAreMalformed() {
        UUID jobId = newJob();
        // Phase 7 step 5: "generate" is a real field now (see GenerationIT); a near-miss name is still unknown
        assertError(api.post(BASE, "{\"jobId\":\"" + jobId + "\",\"generateNow\":true}"), 400, "MALFORMED_REQUEST");
        assertError(api.post(BASE, "{\"jobId\":\"" + jobId + "\",\"bogus\":1}"), 400, "MALFORMED_REQUEST");
        assertError(api.post(BASE, "{\"jobId\":\"not-a-uuid\"}"), 400, "MALFORMED_REQUEST");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application", Integer.class)).isZero();
        UUID id = createdId(jobId);
        assertError(api.put(BASE + "/" + id, "{\"status\":\"SHORTLISTED\",\"colour\":\"red\"}"), 400,
                "MALFORMED_REQUEST");
        assertThat(status(id)).isEqualTo("NEW");
    }

    // ------------------------------------------------------------------ get / ids

    @Test
    void getUnknownOrInvalidId() {
        UUID missing = UUID.randomUUID();
        JsonNode err = assertError(api.get(BASE + "/" + missing), 404, "APPLICATION_NOT_FOUND");
        assertThat(err.get("message").asText()).isEqualTo("No application with id " + missing + ".");
        assertError(api.get(BASE + "/not-a-uuid"), 400, "INVALID_ID");
        assertError(put(missing, "status", "SHORTLISTED"), 404, "APPLICATION_NOT_FOUND");
        assertError(api.delete(BASE + "/" + missing), 404, "APPLICATION_NOT_FOUND");
    }

    // ------------------------------------------------------------------ list

    @Test
    void listFiltersSortsAndPages() {
        UUID a = createdId(newJob());                          // NEW
        UUID b = createdId(newJob(), "status", "SHORTLISTED");
        UUID c = createdId(newJob());
        putOk(c, "status", "APPLIED");                        // APPLIED
        UUID d = createdId(newJob());
        putOk(d, "status", "DISMISSED");
        putOk(a, "status", "NEW", "notes", "touched");         // a is now the most recently updated

        JsonNode all = api.get(BASE).json();
        assertThat(all.get("totalElements").asLong()).isEqualTo(4);
        assertThat(all.get("page").asInt()).isZero();
        assertThat(all.get("size").asInt()).isEqualTo(20);
        assertThat(all.get("totalPages").asInt()).isEqualTo(1);
        assertThat(listIds(all)).containsExactly(a.toString(), d.toString(), c.toString(), b.toString());
        assertThat(listIds(api.get(BASE + "?sort=UPDATED").json())).isEqualTo(listIds(all));
        assertThat(listIds(api.get(BASE + "?sort=created").json()))
                .containsExactly(d.toString(), c.toString(), b.toString(), a.toString());

        assertThat(listIds(api.get(BASE + "?status=new,applied").json())).containsExactly(a.toString(), c.toString());
        assertThat(listIds(api.get(BASE + "?status=NEW&status=Dismissed").json()))
                .containsExactly(a.toString(), d.toString());
        assertThat(listIds(api.get(BASE + "?status=SHORTLISTED,&status=").json())).containsExactly(b.toString());
        assertThat(api.get(BASE + "?status=OFFER").json().get("totalElements").asLong()).isZero();

        JsonNode page1 = api.get(BASE + "?size=3&page=1&sort=created").json();
        assertThat(page1.get("totalPages").asInt()).isEqualTo(2);
        assertThat(page1.get("totalElements").asLong()).isEqualTo(4);
        assertThat(listIds(page1)).containsExactly(a.toString());

        JsonNode item = api.get(BASE + "?status=APPLIED").json().get("content").get(0);
        assertThat(item.get("jobTitle").asText()).startsWith("Engineer ");
        assertThat(item.get("jobCompany").asText()).isEqualTo("Acme");
        assertThat(item.get("jobExists").asBoolean()).isTrue();
        assertThat(item.get("status").asText()).isEqualTo("APPLIED");
        assertThat(item.get("appliedOn").asText()).isNotBlank();
        assertThat(isNull(item, "latestCvVersion")).isTrue();
        assertThat(isNull(item, "latestCoverLetterVersion")).isTrue();
        assertThat(item.get("approvedCv").asBoolean()).isFalse();
        assertThat(item.get("approvedCoverLetter").asBoolean()).isFalse();
        assertThat(isNull(item, "generationStatus")).isTrue();
        assertThat(Instant.parse(item.get("updatedAt").asText())).isNotNull();
    }

    @Test
    void listParameterErrors() {
        for (String q : List.of("status=BOGUS", "status=NEW,bogus", "status=NEW&status=x")) {
            JsonNode err = assertError(api.get(BASE + "?" + q), 400, "INVALID_PARAMETER");
            assertThat(err.get("message").asText()).as(q).isEqualTo(STATUS_FILTER);
        }
        JsonNode err = assertError(api.get(BASE + "?sort=title"), 400, "INVALID_PARAMETER");
        assertThat(err.get("message").asText()).isEqualTo("sort must be updated or created.");
        assertError(api.get(BASE + "?size=0"), 400, "INVALID_PARAMETER");
        assertError(api.get(BASE + "?size=101"), 400, "INVALID_PARAMETER");
        assertError(api.get(BASE + "?page=-1"), 400, "INVALID_PARAMETER");
    }

    // ------------------------------------------------------------------ transitions

    @Test
    void fullTransitionMatrixWithoutForce() {
        for (String from : ALL) {
            for (String to : ALL) {
                if (from.equals(to)) {
                    continue;
                }
                UUID id = applicationIn(from);
                Res r = put(id, "status", to);
                if (ALLOWED.get(from).contains(to)) {
                    assertThat(r.status()).as("%s -> %s: %s", from, to, r).isEqualTo(200);
                    assertThat(r.json().get("status").asText()).isEqualTo(to);
                } else {
                    JsonNode err = assertError(r, 409, "INVALID_STATUS_TRANSITION");
                    List<String> allowed = ALLOWED.get(from);
                    String next = allowed.isEmpty() ? "none" : String.join(", ", allowed);
                    assertThat(err.get("message").asText()).as("%s -> %s", from, to).isEqualTo("An application can't "
                            + "move from " + from + " to " + to + ". Allowed next: " + next
                            + ". To correct a mistake, send force=true.");
                    assertThat(status(id)).isEqualTo(from);
                }
            }
            // allowedTransitions in the view, in table order
            assertThat(texts(get(applicationIn(from)).get("allowedTransitions"))).as(from)
                    .isEqualTo(ALLOWED.get(from));
        }
    }

    @Test
    void historyRecordsMovesAndTheForceFlag() {
        UUID id = createdId(newJob());
        putOk(id, "status", "SHORTLISTED", "force", true);     // allowed anyway; the flag is recorded as sent
        putOk(id, "status", "APPLIED");
        putOk(id, "status", "APPLIED", "notes", "same status");  // no move, no event
        putOk(id, "status", "REJECTED");
        assertError(put(id, "status", "INTERVIEW"), 409, "INVALID_STATUS_TRANSITION");
        JsonNode a = putOk(id, "status", "INTERVIEW", "force", true);
        assertThat(a.get("status").asText()).isEqualTo("INTERVIEW");
        JsonNode h = a.get("history");
        assertThat(h).hasSize(5);
        String[][] expected = {
                {null, "NEW", "false"}, {"NEW", "SHORTLISTED", "true"}, {"SHORTLISTED", "APPLIED", "false"},
                {"APPLIED", "REJECTED", "false"}, {"REJECTED", "INTERVIEW", "true"}};
        for (int i = 0; i < expected.length; i++) {
            JsonNode e = h.get(i);
            if (expected[i][0] == null) {
                assertThat(isNull(e, "from")).isTrue();
            } else {
                assertThat(e.get("from").asText()).isEqualTo(expected[i][0]);
            }
            assertThat(e.get("to").asText()).isEqualTo(expected[i][1]);
            assertThat(e.get("forced").asText()).as("event %d", i).isEqualTo(expected[i][2]);
        }
    }

    @Test
    void statusChangedAtMovesOnlyWithTheStatus() throws Exception {
        UUID id = createdId(newJob());
        Instant created = Instant.parse(get(id).get("statusChangedAt").asText());
        Thread.sleep(20);
        JsonNode same = putOk(id, "status", "NEW", "notes", "n");
        assertThat(Instant.parse(same.get("statusChangedAt").asText())).isEqualTo(created);
        assertThat(Instant.parse(same.get("updatedAt").asText())).isAfter(created);
        JsonNode moved = putOk(id, "status", "SHORTLISTED");
        assertThat(Instant.parse(moved.get("statusChangedAt").asText())).isAfter(created);
    }

    // ------------------------------------------------------------------ update fields

    @Test
    void updateFieldValidation() {
        UUID id = createdId(newJob());
        assertThat(fieldMessage(put(id), "status")).isEqualTo(UPDATE_STATUS);
        assertThat(fieldMessage(put(id, "status", "bogus"), "status")).isEqualTo(UPDATE_STATUS);
        assertThat(fieldMessage(put(id, "status", "SHORTLISTED", "notes", "x".repeat(10_001)), "notes"))
                .isEqualTo("Notes can be at most 10000 characters.");
        assertThat(fieldMessage(put(id, "status", "SHORTLISTED", "appliedOn", "2026-01-05"), "appliedOn"))
                .isEqualTo(APPLIED_ON_TOO_EARLY);
        UUID some = UUID.randomUUID();
        assertThat(fieldMessage(put(id, "status", "NEW", "sentCvId", some), "sentCvId"))
                .isEqualTo("sentCvId can only be set once the application is APPLIED or later.");
        assertThat(fieldMessage(put(id, "status", "DISMISSED", "sentCoverLetterId", some), "sentCoverLetterId"))
                .isEqualTo("sentCoverLetterId can only be set once the application is APPLIED or later.");
        for (String bad : List.of("1999-12-31", "2026-02-30", "19-10-2026", "2026/10/19", "yesterday",
                LocalDate.now(ZoneOffset.UTC).plusDays(3).toString())) {
            assertThat(fieldMessage(put(id, "status", "APPLIED", "appliedOn", bad), "appliedOn")).as(bad)
                    .isEqualTo(APPLIED_ON_INVALID);
        }
        assertThat(status(id)).isEqualTo("NEW");
        assertThat(putOk(id, "status", "APPLIED", "appliedOn", "2000-01-01").get("appliedOn").asText())
                .isEqualTo("2000-01-01");
    }

    @Test
    void validationOrderFieldErrorsThenTransitionThenSentIds() {
        UUID id = createdId(newJob());
        // a field error wins over the transition check
        assertError(put(id, "status", "INTERVIEW", "notes", "x".repeat(10_001)), 400, "VALIDATION_FAILED");
        assertError(put(id, "status", "INTERVIEW", "appliedOn", "1990-01-01"), 400, "VALIDATION_FAILED");
        // the transition check wins over sent-id errors
        UUID unknown = UUID.randomUUID();
        assertError(put(id, "status", "INTERVIEW", "sentCvId", unknown), 409, "INVALID_STATUS_TRANSITION");
        // valid move, unknown sent ids: 400, nothing changed
        JsonNode err = assertError(put(id, "status", "APPLIED", "sentCvId", unknown, "sentCoverLetterId", unknown), 400,
                "VALIDATION_FAILED");
        assertThat(fieldMessages(err))
                .containsEntry("sentCvId", "No CV version " + unknown + " in this application.")
                .containsEntry("sentCoverLetterId", "No cover letter version " + unknown + " in this application.");
        assertThat(status(id)).isEqualTo("NEW");
        assertThat(get(id).get("history")).hasSize(1);
    }

    @Test
    void notesAreReplacedAndNullClearsThem() {
        UUID id = createdId(newJob(), "notes", "first");
        assertThat(putOk(id, "status", "NEW", "notes", " second ").get("notes").asText()).isEqualTo("second");
        assertThat(isNull(putOk(id, "status", "NEW", "notes", null), "notes")).isTrue();
        putOk(id, "status", "NEW", "notes", "third");
        assertThat(isNull(putOk(id, "status", "SHORTLISTED"), "notes")).as("omitted = null (full replace)").isTrue();
        assertThat(isNull(putOk(id, "status", "SHORTLISTED", "notes", "  "), "notes")).isTrue();
    }

    // ------------------------------------------------------------------ applied date and sent versions

    @Test
    void appliedOnDefaultsToTodayInTheConfiguredZone() {
        LocalDate utcToday = nearMidnightUtc();
        LocalDate zoneToday = utcToday.plusDays(1);   // Africa/Johannesburg is UTC+2, no DST
        UUID id = createdId(newJob());
        JsonNode a = putOk(id, "status", "APPLIED");
        assertThat(a.get("appliedOn").asText()).isEqualTo(zoneToday.toString());
        assertThat(isNull(a, "sentCv")).isTrue();
        assertThat(isNull(a, "sentCoverLetter")).isTrue();
        assertThat(texts(a.get("allowedTransitions"))).containsExactly("INTERVIEW", "OFFER", "REJECTED", "WITHDRAWN");

        // today in the zone is accepted explicitly; tomorrow in the zone is the future
        UUID other = createdId(newJob());
        assertThat(putOk(other, "status", "APPLIED", "appliedOn", zoneToday.toString()).get("appliedOn").asText())
                .isEqualTo(zoneToday.toString());
        UUID third = createdId(newJob());
        assertThat(fieldMessage(put(third, "status", "APPLIED", "appliedOn", zoneToday.plusDays(1).toString()),
                "appliedOn")).isEqualTo(APPLIED_ON_INVALID);
    }

    @Test
    void appliedOnIsKeptWhileStayingAppliedAndClearedWhenForcedBack() {
        UUID id = createdId(newJob(), "notes", "keep me");
        assertThat(putOk(id, "status", "APPLIED", "appliedOn", "2026-01-05", "notes", "keep me")
                .get("appliedOn").asText()).isEqualTo("2026-01-05");
        assertThat(putOk(id, "status", "INTERVIEW").get("appliedOn").asText()).isEqualTo("2026-01-05");
        assertThat(putOk(id, "status", "OFFER", "appliedOn", "2026-01-06").get("appliedOn").asText())
                .isEqualTo("2026-01-06");
        assertThat(putOk(id, "status", "OFFER").get("appliedOn").asText()).isEqualTo("2026-01-06");
        JsonNode back = putOk(id, "status", "SHORTLISTED", "force", true);
        assertThat(isNull(back, "appliedOn")).isTrue();
        // entering again takes today's default, not the old date
        LocalDate today = LocalDate.now(java.time.ZoneId.of("Africa/Johannesburg"));
        assertThat(putOk(id, "status", "APPLIED").get("appliedOn").asText()).isEqualTo(today.toString());
    }

    @Test
    void sentVersionsDefaultToTheNewestApprovedAndAreValidated() {
        UUID id = createdId(newJob());
        UUID cv1 = doc(id, "CV", 1, true);
        UUID cv2 = doc(id, "CV", 2, true);
        UUID cv3 = doc(id, "CV", 3, false);
        UUID letter1 = doc(id, "COVER_LETTER", 1, false);
        UUID otherApp = createdId(newJob());
        UUID foreignCv = doc(otherApp, "CV", 1, true);

        // explicit: unapproved, foreign, wrong kind
        assertThat(fieldMessage(put(id, "status", "APPLIED", "sentCvId", cv3), "sentCvId")).isEqualTo("CV version 3 is "
                + "not approved yet. Approve it first (POST /api/applications/" + id + "/documents/" + cv3
                + "/approve) or choose an approved version.");
        assertThat(fieldMessage(put(id, "status", "APPLIED", "sentCoverLetterId", letter1), "sentCoverLetterId"))
                .isEqualTo("Cover letter version 1 is not approved yet. Approve it first (POST /api/applications/" + id
                        + "/documents/" + letter1 + "/approve) or choose an approved version.");
        assertThat(fieldMessage(put(id, "status", "APPLIED", "sentCvId", foreignCv), "sentCvId"))
                .isEqualTo("No CV version " + foreignCv + " in this application.");
        assertThat(fieldMessage(put(id, "status", "APPLIED", "sentCvId", letter1), "sentCvId"))
                .isEqualTo("No CV version " + letter1 + " in this application.");
        assertThat(fieldMessage(put(id, "status", "APPLIED", "sentCoverLetterId", cv1), "sentCoverLetterId"))
                .isEqualTo("No cover letter version " + cv1 + " in this application.");
        assertThat(status(id)).isEqualTo("NEW");

        // default: the newest approved CV (v2, not the unapproved v3); no approved letter -> null
        JsonNode a = putOk(id, "status", "APPLIED");
        assertThat(a.get("sentCv").get("id").asText()).isEqualTo(cv2.toString());
        assertThat(a.get("sentCv").get("version").asInt()).isEqualTo(2);
        assertThat(isNull(a, "sentCoverLetter")).isTrue();
        JsonNode cvDocs = a.get("documents").get("cv");
        assertThat(cvDocs.get("versions").asInt()).isEqualTo(3);
        assertThat(cvDocs.get("latest").get("id").asText()).isEqualTo(cv3.toString());
        assertThat(cvDocs.get("approved").get("id").asText()).isEqualTo(cv2.toString());
        assertThat(a.get("documents").get("coverLetter").get("versions").asInt()).isEqualTo(1);
        assertThat(isNull(a.get("documents").get("coverLetter"), "approved")).isTrue();

        // kept while staying applied; an explicit approved older version is accepted
        assertThat(putOk(id, "status", "INTERVIEW").get("sentCv").get("id").asText()).isEqualTo(cv2.toString());
        assertThat(putOk(id, "status", "INTERVIEW", "sentCvId", cv1).get("sentCv").get("version").asInt())
                .isEqualTo(1);
        assertThat(putOk(id, "status", "OFFER").get("sentCv").get("id").asText()).isEqualTo(cv1.toString());

        // list summary reflects the versions
        JsonNode item = api.get(BASE + "?status=OFFER").json().get("content").get(0);
        assertThat(item.get("latestCvVersion").asInt()).isEqualTo(3);
        assertThat(item.get("latestCoverLetterVersion").asInt()).isEqualTo(1);
        assertThat(item.get("approvedCv").asBoolean()).isTrue();
        assertThat(item.get("approvedCoverLetter").asBoolean()).isFalse();

        // forced back: date and sent versions cleared
        JsonNode back = putOk(id, "status", "SHORTLISTED", "force", true);
        assertThat(isNull(back, "appliedOn")).isTrue();
        assertThat(isNull(back, "sentCv")).isTrue();
        assertThat(isNull(back, "sentCoverLetter")).isTrue();
        assertThat(jdbc.queryForObject("SELECT sent_cv_id FROM application WHERE id = ?", UUID.class, id)).isNull();
    }

    // ------------------------------------------------------------------ delete

    @Test
    void deleteIs204ThenGone() {
        UUID id = createdId(newJob());
        putOk(id, "status", "APPLIED");
        Res del = api.delete(BASE + "/" + id);
        assertThat(del.status()).isEqualTo(204);
        assertError(api.get(BASE + "/" + id), 404, "APPLICATION_NOT_FOUND");
        assertError(api.delete(BASE + "/" + id), 404, "APPLICATION_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_event WHERE application_id = ?",
                Integer.class, id)).isZero();
    }

    @Test
    void deleteCascadesDocumentsFilesGenerationsAndHistoryThroughTheTriggers() {
        UUID id = createdId(newJob());
        UUID cv = doc(id, "CV", 1, true);
        UUID letter = doc(id, "COVER_LETTER", 1, true);
        UUID edit = jdbc.queryForObject("INSERT INTO application_document (application_id, kind, version, source, "
                + "based_on_id, profile_version, content) VALUES (?, 'CV', 2, 'EDIT', ?, ?, '{}') RETURNING id",
                UUID.class, id, cv, profileVersion());
        for (UUID d : List.of(cv, letter)) {
            jdbc.update("INSERT INTO application_document_file (document_id, format, content, size_bytes, sha256) "
                    + "VALUES (?, 'PDF', ?, 3, ?)", d, new byte[] {1, 2, 3}, HASH);
        }
        jdbc.update("INSERT INTO application_generation (application_id, want_cv, want_cover_letter, status, model, "
                + "cv_document_id, cover_letter_document_id, started_at, finished_at) "
                + "VALUES (?, true, true, 'SUCCEEDED', 'fake/m', ?, ?, now(), now())", id, cv, letter);
        putOk(id, "status", "APPLIED");   // sends cv v1 and letter v1 (FK from application to the documents)

        assertThat(api.delete(BASE + "/" + id).status()).isEqualTo(204);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_document WHERE id IN (?, ?, ?)",
                Integer.class, cv, letter, edit)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_document_file", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_generation", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM application_event", Integer.class)).isZero();
    }

    // ------------------------------------------------------------------ job deletion

    @Test
    void deletingTheJobDetachesTheApplication() {
        UUID jobId = job("Data Engineer", "Initech", "Java");
        UUID id = createdId(jobId);
        assertThat(api.delete("/api/jobs/" + jobId).status()).isEqualTo(204);
        JsonNode a = get(id);
        assertThat(isNull(a, "jobId")).isTrue();
        assertThat(a.get("jobExists").asBoolean()).isFalse();
        assertThat(isNull(a, "jobOrigin")).isTrue();
        assertThat(a.get("jobClosed").asBoolean()).isFalse();
        assertThat(a.get("jobTitle").asText()).isEqualTo("Data Engineer");
        assertThat(a.get("jobCompany").asText()).isEqualTo("Initech");
        JsonNode item = api.get(BASE).json().get("content").get(0);
        assertThat(item.get("jobExists").asBoolean()).isFalse();
        // still editable and deletable
        putOk(id, "status", "SHORTLISTED");
        assertThat(api.delete(BASE + "/" + id).status()).isEqualTo(204);
    }

    // ------------------------------------------------------------------ locking

    @Test
    void heldRowLockMakesPutAndDeleteServiceBusy() throws Exception {
        UUID id = createdId(newJob(), "notes", "before");
        try (Connection c = rawConnection()) {
            c.setAutoCommit(false);
            try (var ps = c.prepareStatement("SELECT id FROM application WHERE id = ? FOR UPDATE")) {
                ps.setObject(1, id);
                ps.executeQuery().close();
            }
            Map<String, Long> waited = new HashMap<>();
            long t0 = System.nanoTime();
            Res r = put(id, "status", "SHORTLISTED", "notes", "after");
            waited.put("put", (System.nanoTime() - t0) / 1_000_000);
            JsonNode err = assertError(r, 503, "SERVICE_BUSY");
            assertThat(r.header("Retry-After")).isEqualTo("2");
            assertThat(err.get("message").asText()).isEqualTo(SERVICE_BUSY);

            t0 = System.nanoTime();
            Res d = api.delete(BASE + "/" + id);
            waited.put("delete", (System.nanoTime() - t0) / 1_000_000);
            assertError(d, 503, "SERVICE_BUSY");
            assertThat(d.header("Retry-After")).isEqualTo("2");
            assertThat(waited.values()).as("waited for the 10s lock_timeout").allSatisfy(ms ->
                    assertThat(ms).isBetween(9_000L, 30_000L));
            // reads are not blocked
            assertThat(api.get(BASE + "/" + id).status()).isEqualTo(200);
            c.rollback();
        }
        JsonNode a = get(id);
        assertThat(a.get("status").asText()).isEqualTo("NEW");
        assertThat(a.get("notes").asText()).isEqualTo("before");
        assertThat(putOk(id, "status", "SHORTLISTED").get("status").asText()).isEqualTo("SHORTLISTED");
    }
}
