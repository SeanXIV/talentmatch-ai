package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.talentmatch.feed.skills.SkillDictionary;
import com.talentmatch.feed.skills.SkillMention;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** Phase 5 step 3: skill aliases (spec §3.2, §5.5, §9.2 item 16 where testable before step 7). */
class SkillAliasIT extends AbstractApiIT {

    @Autowired
    SkillDictionary dictionary;

    private UUID pg;
    private UUID java;

    @BeforeEach
    void seed() {
        pg = skill("PostgreSQL");
        java = skill("Java");
    }

    private static String aliases(UUID skill) {
        return "/api/skills/" + skill + "/aliases";
    }

    private Res addAlias(UUID skill, String alias) {
        return api.post(aliases(skill), alias == null ? "{}" : "{\"alias\":" + jsonString(alias) + "}");
    }

    private String jsonString(String s) {
        try {
            return mapper.writeValueAsString(s);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private UUID aliasOk(UUID skill, String alias) {
        Res r = addAlias(skill, alias);
        assertThat(r.status()).as("%s", r).isEqualTo(201);
        return UUID.fromString(r.json().get("id").asText());
    }

    private static List<String> skillNames(JsonNode detail) {
        List<String> names = new ArrayList<>();
        detail.get("skills").forEach(s -> names.add(s.get("name").asText()));
        return names;
    }

    private int count(String table) {
        return jdbc.queryForObject("SELECT count(*) FROM " + table, Integer.class);
    }

    // ------------------------------------------------------------------ CRUD

    @Test
    void createListAndDelete() {
        Res created = addAlias(pg, "  Postgres  ");
        assertThat(created.status()).as("%s", created).isEqualTo(201);
        UUID id = UUID.fromString(created.json().get("id").asText());
        assertThat(created.header("Location")).isEqualTo(aliases(pg) + "/" + id);
        assertThat(created.json().get("alias").asText()).isEqualTo("Postgres");
        assertThat(Instant.parse(created.json().get("createdAt").asText())).isNotNull();
        aliasOk(pg, "psql  tools");

        Res list = api.get(aliases(pg));
        assertThat(list.status()).isEqualTo(200);
        List<String> names = new ArrayList<>();
        list.json().forEach(a -> names.add(a.get("alias").asText()));
        assertThat(names).containsExactlyInAnyOrder("Postgres", "psql tools");
        assertThat(api.get(aliases(java)).json()).isEmpty();

        assertThat(api.delete(aliases(pg) + "/" + id).status()).isEqualTo(204);
        assertError(api.delete(aliases(pg) + "/" + id), 404, "SKILL_ALIAS_NOT_FOUND");
        assertThat(api.get(aliases(pg)).json()).hasSize(1);
    }

    @Test
    void unknownSkillOrAliasIs404() {
        UUID missing = UUID.randomUUID();
        assertError(api.get(aliases(missing)), 404, "SKILL_NOT_FOUND");
        assertError(addAlias(missing, "Postgres"), 404, "SKILL_NOT_FOUND");
        assertError(api.delete(aliases(missing) + "/" + UUID.randomUUID()), 404, "SKILL_NOT_FOUND");
        assertError(api.delete(aliases(pg) + "/" + UUID.randomUUID()), 404, "SKILL_ALIAS_NOT_FOUND");
        // an alias id that belongs to another skill
        UUID alias = aliasOk(pg, "Postgres");
        assertError(api.delete(aliases(java) + "/" + alias), 404, "SKILL_ALIAS_NOT_FOUND");
        assertThat(count("skill_alias")).isEqualTo(1);
    }

    @Test
    void aliasValidation() {
        assertThat(fields(assertError(addAlias(pg, null), 400, "VALIDATION_FAILED"))).containsExactly("alias");
        assertThat(fields(assertError(addAlias(pg, "   "), 400, "VALIDATION_FAILED"))).containsExactly("alias");
        assertThat(fields(assertError(addAlias(pg, "x".repeat(101)), 400, "VALIDATION_FAILED"))).containsExactly("alias");
        aliasOk(pg, "y".repeat(100));
        assertError(api.post(aliases(pg), "{\"alias\":\"Pg\",\"skill\":\"x\"}"), 400, "MALFORMED_REQUEST");
        assertError(api.post(aliases(pg), "not json"), 400, "MALFORMED_REQUEST");
    }

    // ------------------------------------------------------------------ alias vs skill names

    @Test
    void anAliasCanNotEqualASkillName() {
        assertError(addAlias(pg, "postgresql"), 409, "SKILL_ALIAS_ALREADY_EXISTS");
        JsonNode other = assertError(addAlias(pg, "JAVA"), 409, "SKILL_ALIAS_ALREADY_EXISTS");
        assertThat(other.get("message").asText()).contains("Java");
        assertThat(count("skill_alias")).isZero();
    }

    @Test
    void anAliasIsUniqueIgnoringCase() {
        aliasOk(pg, "Postgres");
        assertError(addAlias(pg, "POSTGRES"), 409, "SKILL_ALIAS_ALREADY_EXISTS");
        assertError(addAlias(java, "postgres"), 409, "SKILL_ALIAS_ALREADY_EXISTS");
        assertThat(count("skill_alias")).isEqualTo(1);
    }

    @Test
    void aSkillCanNotTakeAnAliasName() {
        aliasOk(pg, "Postgres");
        JsonNode err = assertError(api.post("/api/skills", "{\"name\":\" postgres \"}"), 409, "SKILL_ALREADY_EXISTS");
        assertThat(err.get("message").asText()).contains("PostgreSQL");
        assertThat(count("skill")).isEqualTo(2);
    }

    // ------------------------------------------------------------------ resolution in requests

    @Test
    void jobAndCandidateRequestsResolveAliases() {
        aliasOk(pg, "Postgres");
        Res job = api.post("/api/jobs", jobBody("Backend Engineer", "Acme", null, "Java", "~postgres"));
        assertThat(job.status()).as("%s", job).isEqualTo(201);
        assertThat(skillNames(job.json())).containsExactlyInAnyOrder("Java", "PostgreSQL");

        Res cand = api.post("/api/candidates", candidateBody("Ada Lovelace", "ada@example.com", null, "Postgres:3"));
        assertThat(cand.status()).as("%s", cand).isEqualTo(201);
        assertThat(skillNames(cand.json())).containsExactly("PostgreSQL");
        assertThat(count("skill")).as("no skill was created").isEqualTo(2);

        // the resolved job matches the candidate through PostgreSQL
        UUID jobId = UUID.fromString(job.json().get("id").asText());
        assertThat(candidateNames(matches(jobId))).containsExactly("Ada Lovelace");
    }

    @Test
    void twoNamesForOneSkillInOneRequestAreADuplicate() {
        aliasOk(pg, "Postgres");
        assertThat(fields(assertError(api.post("/api/jobs", jobBody("Backend Engineer", "Acme", null,
                "Postgres", "PostgreSQL")), 400, "VALIDATION_FAILED"))).containsExactly("skills[1].name");
        assertThat(fields(assertError(api.post("/api/candidates", candidateBody("Ada", "ada@example.com", null,
                "PostgreSQL", "Java", "postgres")), 400, "VALIDATION_FAILED"))).containsExactly("skills[2].name");
        assertThat(count("job") + count("candidate")).isZero();
    }

    // ------------------------------------------------------------------ profile save

    private ObjectNode profileBody(boolean createMissing, String... skills) {
        ObjectNode body = mapper.createObjectNode();
        body.put("createMissingSkills", createMissing);
        ObjectNode p = body.putObject("profile");
        p.put("fullName", "Ada Lovelace");
        p.put("email", "ada@example.com");
        p.put("headline", "Backend Engineer");
        ArrayNode list = p.putArray("skills");
        for (String s : skills) {
            list.addObject().put("name", s);
        }
        return body;
    }

    @Test
    void profileSaveWithAnAliasCreatesNoSkill() {
        aliasOk(pg, "Postgres");
        Res r = api.put("/api/profile", profileBody(true, "Java", "Postgres", "Rust").toString());
        assertThat(r.status()).as("%s", r).isBetween(200, 201);
        assertThat(jdbc.queryForList("SELECT name FROM skill ORDER BY name", String.class))
                .as("Rust is created, Postgres is not").containsExactly("Java", "PostgreSQL", "Rust");
        assertThat(jdbc.queryForList("SELECT s.name FROM owner_profile o JOIN candidate_skill cs "
                + "ON cs.candidate_id = o.candidate_id JOIN skill s ON s.id = cs.skill_id ORDER BY s.name", String.class))
                .containsExactly("Java", "PostgreSQL", "Rust");
    }

    @Test
    void profileSaveWithoutCreateAcceptsAnAlias() {
        aliasOk(pg, "Postgres");
        Res r = api.put("/api/profile", profileBody(false, "Java", "postgres").toString());
        assertThat(r.status()).as("%s", r).isBetween(200, 201);
        assertThat(count("skill")).isEqualTo(2);
    }

    @Test
    void profileWithTwoNamesForOneSkillIsRejected() {
        aliasOk(pg, "Postgres");
        JsonNode err = assertError(api.put("/api/profile", profileBody(true, "PostgreSQL", "Java", "Postgres")
                .toString()), 400, "VALIDATION_FAILED");
        assertThat(fields(err)).containsExactly("profile.skills[2].name");
        assertThat(count("owner_profile")).isZero();
        assertThat(count("skill")).isEqualTo(2);
    }

    // ------------------------------------------------------------------ SkillDictionary

    @Test
    void dictionaryFingerprintChangesWithTheVocabulary() {
        String f0 = dictionary.fingerprint();
        assertThat(dictionary.refresh().resolve("postgres")).isEmpty();

        UUID alias = aliasOk(pg, "Postgres");
        String f1 = dictionary.fingerprint();
        assertThat(f1).isNotEqualTo(f0);
        SkillDictionary.Snapshot s = dictionary.refresh();
        assertThat(s.fingerprint()).isEqualTo(f1);
        assertThat(s.resolve("POSTGRES")).contains(pg);
        assertThat(s.resolve("PostgreSQL")).contains(pg);
        assertThat(s.name(pg)).contains("PostgreSQL");
        List<SkillMention> found = s.matcher().match("Backend Engineer", "Java and Postgres on day one.");
        assertThat(found).extracting(SkillMention::skillId).containsExactly(java, pg);
        assertThat(dictionary.refresh()).as("unchanged vocabulary: cached snapshot").isSameAs(s);
        assertThat(dictionary.snapshot()).isSameAs(s);

        skill("Docker");
        String f2 = dictionary.fingerprint();
        assertThat(f2).isNotEqualTo(f1);
        assertThat(dictionary.refresh().resolve("docker")).isPresent();

        api.delete(aliases(pg) + "/" + alias);
        assertThat(dictionary.fingerprint()).isNotIn(f1, f2);
        assertThat(dictionary.refresh().resolve("postgres")).isEmpty();
    }
}
