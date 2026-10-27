package com.talentmatch.support;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.talentmatch.application.tailoring.TailoringFixtures;
import com.talentmatch.support.Api.Res;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Helpers for the Phase 7 generation ITs (§9.2 items 3–7, 10, 11): the owner profile of
 * {@link TailoringFixtures#profile()} saved through the API, the "Senior Java Developer at Initech" job the
 * default {@link FakeTailoringModel} answers are grounded in, application/generation calls and database reads.
 */
public abstract class AbstractGenerationIT extends AbstractAiApiIT {

    protected static final String BASE = "/api/applications";

    /** A distinctive job description, so logging tests can look for it. */
    protected static final String JOB_DESCRIPTION =
            "We build payments with Java, Kubernetes and Docker. Zephyrine Quillfeather leads the platform team.";

    // ------------------------------------------------------------------ fixtures

    /** Saves {@link TailoringFixtures#profile()} (creating its missing skills); returns the profile version. */
    protected int saveProfile() {
        ObjectNode body = mapper.createObjectNode();
        body.put("createMissingSkills", true);
        body.set("profile", mapper.valueToTree(TailoringFixtures.profile()));
        Res r = api.put("/api/profile", body.toString());
        assertThat(r.status()).as("PUT /api/profile %s", r).isEqualTo(200);
        return r.json().get("version").asInt();
    }

    /** "Senior Java Developer" at Initech (required Java, Kubernetes; nice Docker). */
    protected UUID initechJob() {
        return initechJob("Senior Java Developer");
    }

    protected UUID initechJob(String title) {
        Res r = api.post("/api/jobs", jobBody(title, "Initech", JOB_DESCRIPTION, "Java", "Kubernetes", "~Docker"));
        assertThat(r.status()).as("POST /api/jobs %s", r).isEqualTo(201);
        return UUID.fromString(r.json().get("id").asText());
    }

    protected static Map<String, Object> body(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    protected Res createApplication(UUID jobId, Object... extra) {
        Map<String, Object> b = body(extra);
        b.put("jobId", jobId);
        return api.post(BASE, api.json(b));
    }

    /** Creates an application with {@code generate=true}; returns the 201 body. */
    protected JsonNode createGenerating(UUID jobId, Object... extra) {
        Object[] all = new Object[extra.length + 2];
        all[0] = "generate";
        all[1] = true;
        System.arraycopy(extra, 0, all, 2, extra.length);
        Res r = createApplication(jobId, all);
        assertThat(r.status()).as("create %s", r).isEqualTo(201);
        return r.json();
    }

    /** Creates an application without a generation; returns its id. */
    protected UUID createTracked(UUID jobId) {
        Res r = createApplication(jobId);
        assertThat(r.status()).as("create %s", r).isEqualTo(201);
        return UUID.fromString(r.json().get("id").asText());
    }

    protected Res postGeneration(UUID applicationId, String json) {
        return api.post(BASE + "/" + applicationId + "/generations", json);
    }

    protected Res getGeneration(UUID applicationId, UUID generationId) {
        return api.get(BASE + "/" + applicationId + "/generations/" + generationId);
    }

    /** Polls the generation until it has {@code status}; returns it. */
    protected JsonNode awaitGeneration(UUID applicationId, UUID generationId, String status) {
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(50)).until(() -> {
            Res r = getGeneration(applicationId, generationId);
            return r.status() == 200 && status.equals(r.json().get("status").asText());
        });
        return getGeneration(applicationId, generationId).json();
    }

    protected static UUID id(JsonNode node) {
        return UUID.fromString(node.get("id").asText());
    }

    protected static UUID generationId(JsonNode application) {
        return UUID.fromString(application.at("/latestGeneration/id").asText());
    }

    // ------------------------------------------------------------------ database reads

    /** Every stored version of the application: kind, version, source, model, input_hash, profile_version. */
    protected List<Map<String, Object>> documentRows(UUID applicationId) {
        return jdbc.queryForList("SELECT id, kind, version, source, model, input_hash, profile_version, "
                + "based_on_id, job_updated_at, content::text AS content, findings::text AS findings "
                + "FROM application_document WHERE application_id = ? ORDER BY kind, version", applicationId);
    }

    protected int documentCount(UUID applicationId, String kind) {
        return jdbc.queryForObject("SELECT count(*) FROM application_document WHERE application_id = ? AND kind = ?",
                Integer.class, applicationId, kind);
    }

    /** The single stored version of {@code kind} (asserts there is exactly one). */
    protected Map<String, Object> onlyDocument(UUID applicationId, String kind) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map<String, Object> row : documentRows(applicationId)) {
            if (kind.equals(row.get("kind"))) {
                rows.add(row);
            }
        }
        assertThat(rows).as("%s versions of application %s", kind, applicationId).hasSize(1);
        return rows.get(0);
    }

    protected JsonNode json(Object text) {
        try {
            return mapper.readTree((String) text);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected Map<String, Object> generationRow(UUID generationId) {
        return jdbc.queryForMap("SELECT * FROM application_generation WHERE id = ?", generationId);
    }

    /** The finding codes of a stored findings array. */
    protected static List<String> codes(JsonNode findings) {
        List<String> out = new ArrayList<>();
        findings.forEach(f -> out.add(f.get("code").asText()));
        return out;
    }

    /** A result entry of a generation view for {@code kind}, or null. */
    protected static JsonNode result(JsonNode generation, String kind) {
        for (JsonNode r : generation.get("results")) {
            if (kind.equals(r.get("kind").asText())) {
                return r;
            }
        }
        return null;
    }
}
