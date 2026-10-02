package com.talentmatch.it;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.talentmatch.support.AbstractApiIT;
import com.talentmatch.support.Api.Res;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** §8.9 CRUD: 201 + Location, normalization, timestamps, cascades; list endpoints and filters. */
class CrudIT extends AbstractApiIT {

    private static List<String> contentField(JsonNode page, String field) {
        List<String> values = new ArrayList<>();
        page.get("content").forEach(n -> values.add(n.get(field).asText()));
        return values;
    }

    // ------------------------------------------------------------------ skills

    @Test
    void createAndReadSkill() {
        Res r = api.post("/api/skills", "{\"name\":\"  Spring \\t  Boot \",\"category\":\"   \"}");
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        String id = r.json().get("id").asText();
        assertThat(r.header("Location")).isEqualTo("/api/skills/" + id);
        assertThat(r.json().get("name").asText()).isEqualTo("Spring Boot");
        assertThat(r.json().get("category").isNull()).isTrue();
        Res get = api.get(r.header("Location"));
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.json()).isEqualTo(r.json());
    }

    @Test
    void skillListSortedAndSearchable() {
        for (String n : List.of("python", "Java", "JavaScript", "C++", "100%Pure", "snake_case", "snakeXcase", "Wow!", "apache kafka")) {
            skill(n);
        }
        JsonNode all = api.get("/api/skills").json();
        assertThat(contentField(all, "name")).containsExactly(
                "100%Pure", "apache kafka", "C++", "Java", "JavaScript", "python", "snake_case", "snakeXcase", "Wow!");
        assertThat(all.get("page").asInt()).isZero();
        assertThat(all.get("size").asInt()).isEqualTo(20);
        assertThat(all.get("totalElements").asLong()).isEqualTo(9);
        assertThat(all.get("totalPages").asInt()).isEqualTo(1);

        assertThat(contentField(api.get("/api/skills?q=JAVA").json(), "name")).containsExactly("Java", "JavaScript");
        assertThat(contentField(api.get("/api/skills?q=%20%20java%20").json(), "name")).containsExactly("Java", "JavaScript");
        // LIKE wildcards are literal (escape '!')
        assertThat(contentField(api.get("/api/skills?q=_").json(), "name")).containsExactly("snake_case");
        assertThat(contentField(api.get("/api/skills?q=%25").json(), "name")).containsExactly("100%Pure");
        assertThat(contentField(api.get("/api/skills?q=!").json(), "name")).containsExactly("Wow!");
        assertThat(contentField(api.get("/api/skills?q=%2B%2B").json(), "name")).containsExactly("C++");
        assertThat(api.get("/api/skills?q=zzz").json().get("totalElements").asLong()).isZero();
        // blank q = no filter
        assertThat(api.get("/api/skills?q=%20").json().get("totalElements").asLong()).isEqualTo(9);

        JsonNode p1 = api.get("/api/skills?size=4&page=1").json();
        assertThat(contentField(p1, "name")).containsExactly("JavaScript", "python", "snake_case", "snakeXcase");
        assertThat(p1.get("totalPages").asInt()).isEqualTo(3);
        JsonNode search = api.get("/api/skills?q=a&size=2&page=1").json();
        assertThat(search.get("totalElements").asLong()).isEqualTo(5);
        assertThat(contentField(search, "name")).containsExactly("JavaScript", "snake_case");
    }

    // ------------------------------------------------------------------ candidates

    @Test
    void candidateLifecycle() {
        skill("Java", "Language");
        skill("SQL", "Database");
        skill("Docker", "DevOps");
        Res r = api.post("/api/candidates", candidateBody("  Ada Lovelace ", "  Ada@Example.COM ", "  Analyst  ",
                "sql", "Java:5"));
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        JsonNode body = r.json();
        UUID id = UUID.fromString(body.get("id").asText());
        assertThat(r.header("Location")).isEqualTo("/api/candidates/" + id);
        assertThat(body.get("fullName").asText()).isEqualTo("Ada Lovelace");
        assertThat(body.get("email").asText()).isEqualTo("ada@example.com");
        assertThat(body.get("summary").asText()).isEqualTo("Analyst");
        assertThat(body.at("/skills/0/name").asText()).isEqualTo("Java");
        assertThat(body.at("/skills/0/category").asText()).isEqualTo("Language");
        assertThat(body.at("/skills/0/yearsExperience").asInt()).isEqualTo(5);
        assertThat(body.at("/skills/1/name").asText()).isEqualTo("SQL");
        assertThat(body.at("/skills/1/yearsExperience").isNull()).isTrue();
        assertThat(body.at("/skills/1/skillId").asText()).isNotBlank();
        // DB-generated timestamps are read back after insert
        Instant created = Instant.parse(body.get("createdAt").asText());
        Instant updated = Instant.parse(body.get("updatedAt").asText());
        assertThat(created).isEqualTo(updated).isEqualTo(updatedAt("candidate", id));

        Res get = api.get(r.header("Location"));
        assertThat(get.status()).isEqualTo(200);
        assertThat(get.json()).isEqualTo(body);

        // scalar change: updatedAt advances (Hibernate @Generated UPDATE), createdAt stays
        Res put = api.put("/api/candidates/" + id, candidateBody("Ada King", "ada@example.com", "Analyst", "SQL", "Java:5"));
        assertThat(put.status()).as(put.toString()).isEqualTo(200);
        Instant afterScalar = Instant.parse(put.json().get("updatedAt").asText());
        assertThat(afterScalar).isAfter(updated).isEqualTo(updatedAt("candidate", id));
        assertThat(Instant.parse(put.json().get("createdAt").asText())).isEqualTo(created);
        assertThat(put.json().get("fullName").asText()).isEqualTo("Ada King");

        // links-only change: updatedAt re-read after the V2 trigger bumped it
        Res links = api.put("/api/candidates/" + id, candidateBody("Ada King", "ada@example.com", "Analyst", "Java:6", "Docker"));
        assertThat(links.status()).isEqualTo(200);
        Instant afterLinks = Instant.parse(links.json().get("updatedAt").asText());
        assertThat(afterLinks).isAfter(afterScalar).isEqualTo(updatedAt("candidate", id));
        assertThat(links.json().get("skills")).hasSize(2);
        assertThat(links.json().at("/skills/0/name").asText()).isEqualTo("Docker");
        assertThat(links.json().at("/skills/1/yearsExperience").asInt()).isEqualTo(6);
        assertThat(api.get("/api/candidates/" + id).json()).isEqualTo(links.json());

        // clearing skills and summary
        Res cleared = api.put("/api/candidates/" + id, "{\"fullName\":\"Ada King\",\"email\":\"ada@example.com\"}");
        assertThat(cleared.status()).isEqualTo(200);
        assertThat(cleared.json().get("skills")).isEmpty();
        assertThat(cleared.json().get("summary").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_skill WHERE candidate_id = ?", Integer.class, id)).isZero();
    }

    @Test
    void deleteCandidateCascades() {
        skill("Java");
        UUID c = candidate("Ada Lovelace", "ada@example.com", "Java");
        UUID other = candidate("Bob", "bob@example.com", "Java");
        UUID job = job("Backend Engineer", "Acme", "Java");
        matches(job);
        assertThat(countMatchRows(job)).isEqualTo(2);

        assertThat(api.delete("/api/candidates/" + c).status()).isEqualTo(204);
        assertThat(api.get("/api/candidates/" + c).status()).isEqualTo(404);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_match WHERE candidate_id = ?", Integer.class, c)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM candidate_skill WHERE candidate_id = ?", Integer.class, c)).isZero();
        assertThat(countMatchRows(job)).isEqualTo(1);
        assertThat(api.delete("/api/candidates/" + c).status()).isEqualTo(404);
        assertThat(api.get("/api/candidates/" + other).status()).isEqualTo(200);
    }

    @Test
    void candidateListSortedFilteredAndPaged() {
        skill("Java");
        skill("SQL");
        candidate("Zoe", "zoe@example.com", "Java");
        candidate("Adam", "adam@example.com", "SQL");
        UUID m1 = candidate("Mia", "mia1@example.com", "Java", "SQL");
        UUID m2 = candidate("Mia", "mia2@example.com");
        JsonNode all = api.get("/api/candidates").json();
        assertThat(contentField(all, "fullName")).containsExactly("Adam", "Mia", "Mia", "Zoe");
        List<String> mias = new ArrayList<>(List.of(m1.toString(), m2.toString()));
        mias.sort(Comparator.naturalOrder());
        assertThat(contentField(all, "id").subList(1, 3)).containsExactlyElementsOf(mias);
        JsonNode first = all.get("content").get(0);
        assertThat(first.get("email").asText()).isEqualTo("adam@example.com");
        assertThat(first.has("summary")).isTrue();
        assertThat(first.has("skills")).isFalse();

        assertThat(contentField(api.get("/api/candidates?skill=java").json(), "fullName")).containsExactly("Mia", "Zoe");
        assertThat(contentField(api.get("/api/candidates?skill=%20SQL%20").json(), "fullName")).containsExactly("Adam", "Mia");
        JsonNode unknown = api.get("/api/candidates?skill=Cobol").json();
        assertThat(unknown.get("content")).isEmpty();
        assertThat(unknown.get("totalElements").asLong()).isZero();
        assertThat(unknown.get("totalPages").asInt()).isZero();

        JsonNode paged = api.get("/api/candidates?size=3&page=1").json();
        assertThat(contentField(paged, "fullName")).containsExactly("Zoe");
        assertThat(paged.get("page").asInt()).isEqualTo(1);
        assertThat(paged.get("size").asInt()).isEqualTo(3);
        assertThat(paged.get("totalPages").asInt()).isEqualTo(2);
        assertThat(paged.get("totalElements").asLong()).isEqualTo(4);
        JsonNode filteredPaged = api.get("/api/candidates?skill=java&size=1&page=1").json();
        assertThat(contentField(filteredPaged, "fullName")).containsExactly("Zoe");
        assertThat(filteredPaged.get("totalElements").asLong()).isEqualTo(2);
    }

    // ------------------------------------------------------------------ jobs

    @Test
    void jobLifecycle() {
        skill("Java", "Language");
        skill("Docker", "DevOps");
        skill("SQL", "Database");
        Res r = api.post("/api/jobs", "{\"title\":\" Backend Engineer \",\"company\":\" Acme \",\"description\":\"  \","
                + "\"skills\":[{\"name\":\"java\"},{\"name\":\"Docker\",\"required\":false}]}");
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        JsonNode body = r.json();
        UUID id = UUID.fromString(body.get("id").asText());
        assertThat(r.header("Location")).isEqualTo("/api/jobs/" + id);
        assertThat(body.get("title").asText()).isEqualTo("Backend Engineer");
        assertThat(body.get("company").asText()).isEqualTo("Acme");
        assertThat(body.get("description").isNull()).isTrue();
        assertThat(body.get("matchable").asBoolean()).isTrue();
        assertThat(body.at("/skills/0/name").asText()).isEqualTo("Docker");
        assertThat(body.at("/skills/0/required").asBoolean()).isFalse();
        assertThat(body.at("/skills/0/category").asText()).isEqualTo("DevOps");
        assertThat(body.at("/skills/1/name").asText()).isEqualTo("Java");
        assertThat(body.at("/skills/1/required").asBoolean()).as("required defaults to true").isTrue();
        Instant created = Instant.parse(body.get("createdAt").asText());
        assertThat(Instant.parse(body.get("updatedAt").asText())).isEqualTo(created);
        assertThat(api.get(r.header("Location")).json()).isEqualTo(body);

        Res put = api.put("/api/jobs/" + id, jobBody("Backend Engineer II", "Acme", "Build things", "SQL", "~Java"));
        assertThat(put.status()).as(put.toString()).isEqualTo(200);
        assertThat(put.json().get("title").asText()).isEqualTo("Backend Engineer II");
        assertThat(contentFieldOf(put.json().get("skills"), "name")).containsExactly("Java", "SQL");
        assertThat(put.json().at("/skills/0/required").asBoolean()).isFalse();
        assertThat(Instant.parse(put.json().get("updatedAt").asText())).isAfter(created).isEqualTo(updatedAt("job", id));
        assertThat(Instant.parse(put.json().get("createdAt").asText())).isEqualTo(created);
        assertThat(api.get("/api/jobs/" + id).json()).isEqualTo(put.json());

        // links-only change
        Res links = api.put("/api/jobs/" + id, jobBody("Backend Engineer II", "Acme", "Build things", "SQL", "Java"));
        assertThat(Instant.parse(links.json().get("updatedAt").asText()))
                .isAfter(Instant.parse(put.json().get("updatedAt").asText())).isEqualTo(updatedAt("job", id));
    }

    private static List<String> contentFieldOf(JsonNode array, String field) {
        List<String> values = new ArrayList<>();
        array.forEach(n -> values.add(n.get(field).asText()));
        return values;
    }

    @Test
    void deleteJobCascades() {
        skill("Java");
        candidate("Ada", "ada@example.com", "Java");
        UUID job = job("Backend Engineer", "Acme", "Java");
        matches(job);
        assertThat(countMatchRows(job)).isEqualTo(1);
        assertThat(api.delete("/api/jobs/" + job).status()).isEqualTo(204);
        assertThat(countMatchRows(job)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM job_skill WHERE job_id = ?", Integer.class, job)).isZero();
        assertThat(api.get("/api/jobs/" + job).status()).isEqualTo(404);
        assertThat(api.get("/api/jobs/" + job + "/matches").status()).isEqualTo(404);
    }

    @Test
    void jobListCountsSkillsSortsAndFilters() {
        skill("Java");
        skill("Docker");
        skill("SQL");
        job("Backend Engineer", "Zeta", "Java", "~Docker", "SQL");
        job("Backend Engineer", "Acme", "Java");
        job("Analyst", "Acme", "SQL", "~Docker");
        UUID empty = jobWithoutSkills("Archivist", "Acme");

        JsonNode all = api.get("/api/jobs").json();
        assertThat(contentField(all, "title")).containsExactly("Analyst", "Archivist", "Backend Engineer", "Backend Engineer");
        assertThat(contentField(all, "company")).containsExactly("Acme", "Acme", "Acme", "Zeta");
        assertThat(contentField(all, "skillCount")).containsExactly("2", "0", "1", "3");
        assertThat(contentField(all, "matchable")).containsExactly("true", "false", "true", "true");
        assertThat(all.at("/content/1/id").asText()).isEqualTo(empty.toString());
        assertThat(all.get("totalElements").asLong()).isEqualTo(4);
        assertThat(all.at("/content/0/description").isNull()).isTrue();
        assertThat(all.at("/content/0").has("skills")).isFalse();

        // matches required or nice-to-have; skillCount still counts all of the job's skills
        JsonNode docker = api.get("/api/jobs?skill=docker").json();
        assertThat(contentField(docker, "title")).containsExactly("Analyst", "Backend Engineer");
        assertThat(contentField(docker, "skillCount")).containsExactly("2", "3");
        assertThat(docker.get("totalElements").asLong()).isEqualTo(2);
        assertThat(api.get("/api/jobs?skill=Cobol").json().get("totalElements").asLong()).isZero();

        JsonNode paged = api.get("/api/jobs?size=2&page=1").json();
        assertThat(contentField(paged, "company")).containsExactly("Acme", "Zeta");
        assertThat(paged.get("totalPages").asInt()).isEqualTo(2);
        assertThat(paged.get("totalElements").asLong()).isEqualTo(4);
        JsonNode filteredPaged = api.get("/api/jobs?skill=java&size=1&page=1").json();
        assertThat(contentField(filteredPaged, "company")).containsExactly("Zeta");
        assertThat(filteredPaged.get("totalElements").asLong()).isEqualTo(2);
    }
}
