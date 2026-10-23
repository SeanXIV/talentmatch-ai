package com.talentmatch.it;

import static com.talentmatch.application.tailoring.TailoringFixtures.EMAIL;
import static com.talentmatch.application.tailoring.TailoringFixtures.NAME;
import static com.talentmatch.application.tailoring.TailoringFixtures.PHONE;
import static com.talentmatch.support.FakeTailoringModel.G0;
import static com.talentmatch.support.FakeTailoringModel.G1;
import static com.talentmatch.support.FakeTailoringModel.G2;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.render.Block;
import com.talentmatch.application.tailoring.TailoringFixtures;
import com.talentmatch.support.AbstractGenerationIT;
import com.talentmatch.support.Api.Res;
import com.talentmatch.support.FakeTailoringModel;
import java.io.ByteArrayInputStream;
import java.net.URLDecoder;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.extractor.XWPFWordExtractor;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Phase 7 §9.2 item 8 (documents: list, get, edit, approve, download, computed fields, sent versions in the
 * tracker PUT) and item 11 for the document INFO lines, through the API with the scripted
 * {@link FakeTailoringModel}.
 */
@ExtendWith(OutputCaptureExtension.class)
class DocumentIT extends AbstractGenerationIT {

    private static final String DOCX_TYPE = "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
    private static final String KIND_MESSAGE = "kind must be CV or COVER_LETTER.";
    private static final String FORMAT_MESSAGE = "format must be pdf or docx.";
    /** 96 words in 6 paragraphs; paragraph 1 has a number no profile text contains (BLOCKING in an AI letter). */
    private static final String THIRTEEN = "I bring 13 years of backend experience with Java and PostgreSQL.";

    // ------------------------------------------------------------------ helpers

    /** Saves the fixture profile, generates {@code kinds} for a fresh Initech job; returns the application id. */
    private UUID generated(String... kinds) {
        return generatedFor(initechJob(), kinds);
    }

    private UUID generatedFor(UUID job, String... kinds) {
        if (jdbc.queryForObject("SELECT count(*) FROM owner_profile", Integer.class) == 0) {
            saveProfile();
        }
        JsonNode app = kinds.length == 0 ? createGenerating(job) : createGenerating(job, "kinds", List.of(kinds));
        UUID appId = id(app);
        awaitGeneration(appId, generationId(app), "SUCCEEDED");
        return appId;
    }

    private UUID docId(UUID appId, String kind, int version) {
        return jdbc.queryForObject("SELECT id FROM application_document WHERE application_id = ? AND kind = ? "
                + "AND version = ?", UUID.class, appId, kind, version);
    }

    private String docsPath(UUID appId) {
        return BASE + "/" + appId + "/documents";
    }

    private String docPath(UUID appId, UUID docId) {
        return docsPath(appId) + "/" + docId;
    }

    private JsonNode getDoc(UUID appId, UUID docId) {
        Res r = api.get(docPath(appId, docId));
        assertThat(r.status()).as("GET document %s", r).isEqualTo(200);
        return r.json();
    }

    private ObjectNode content(UUID appId, UUID docId) {
        return (ObjectNode) getDoc(appId, docId).get("content").deepCopy();
    }

    private Res edit(UUID appId, UUID basedOn, JsonNode content) {
        ObjectNode b = mapper.createObjectNode();
        if (basedOn != null) {
            b.put("basedOn", basedOn.toString());
        }
        if (content != null) {
            b.set("content", content);
        }
        return api.post(docsPath(appId), b.toString());
    }

    private JsonNode editOk(UUID appId, UUID basedOn, JsonNode content) {
        Res r = edit(appId, basedOn, content);
        assertThat(r.status()).as("edit %s", r).isEqualTo(201);
        return r.json();
    }

    private Res approve(UUID appId, UUID docId, Boolean ack) {
        String path = docPath(appId, docId) + "/approve";
        if (ack == null) {
            return api.post(path, null);
        }
        return api.post(path, "{\"acknowledgeFindings\": " + ack + "}");
    }

    private JsonNode approveOk(UUID appId, UUID docId, Boolean ack) {
        Res r = approve(appId, docId, ack);
        assertThat(r.status()).as("approve %s", r).isEqualTo(200);
        return r.json();
    }

    private HttpResponse<byte[]> download(UUID appId, UUID docId, String query) {
        return api.getBytes(docPath(appId, docId) + "/file" + (query == null ? "" : "?" + query));
    }

    private static String fieldError(Res r, String field) {
        JsonNode b = assertError(r, 400, "VALIDATION_FAILED");
        assertThat(fields(b)).as("fields of %s", r).contains(field);
        return fieldMessages(b).get(field);
    }

    private static String malformedField(Res r) {
        JsonNode b = assertError(r, 400, "MALFORMED_REQUEST");
        assertThat(b.get("fieldErrors").size()).as("%s", r).isEqualTo(1);
        return b.at("/fieldErrors/0/field").asText();
    }

    private static List<String> texts(JsonNode array) {
        List<String> out = new ArrayList<>();
        array.forEach(n -> out.add(n.asText()));
        return out;
    }

    private static List<Integer> versions(JsonNode list) {
        List<Integer> out = new ArrayList<>();
        list.forEach(n -> out.add(n.get("version").asInt()));
        return out;
    }

    private static List<String> findingCodes(JsonNode doc) {
        return codes(doc.get("findings"));
    }

    private static List<String> severities(JsonNode doc) {
        List<String> out = new ArrayList<>();
        doc.get("findings").forEach(f -> out.add(f.get("severity").asText()));
        return out;
    }

    /** Saves the fixture profile with another headline: a new profile version. */
    private int saveChangedProfile(String headline) {
        ObjectNode profile = mapper.valueToTree(TailoringFixtures.profile());
        profile.put("headline", headline);
        ObjectNode body = mapper.createObjectNode();
        body.put("createMissingSkills", true);
        body.set("profile", profile);
        Res r = api.put("/api/profile", body.toString());
        assertThat(r.status()).as("PUT /api/profile %s", r).isEqualTo(200);
        return r.json().get("version").asInt();
    }

    private int currentProfileVersion() {
        return jdbc.queryForObject("SELECT max(version) FROM owner_profile_version", Integer.class);
    }

    private static String sha256(byte[] bytes) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private static String pdfText(byte[] pdf) throws Exception {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc);
        }
    }

    private static String docxText(byte[] docx) throws Exception {
        try (XWPFDocument doc = new XWPFDocument(new ByteArrayInputStream(docx));
             XWPFWordExtractor extractor = new XWPFWordExtractor(doc)) {
            return extractor.getText();
        }
    }

    /** The decoded {@code filename*=UTF-8''…} of a Content-Disposition header. */
    private static String fileName(HttpResponse<byte[]> r) {
        String cd = r.headers().firstValue("Content-Disposition").orElseThrow();
        assertThat(cd).startsWith("attachment").contains("filename*=UTF-8''");
        Matcher m = Pattern.compile("filename\\*=UTF-8''([^;]+)").matcher(cd);
        assertThat(m.find()).isTrue();
        return URLDecoder.decode(m.group(1).replace("+", "%2B"), StandardCharsets.UTF_8);
    }

    private static void assertDownloadHeaders(HttpResponse<byte[]> r, String contentType) {
        assertThat(r.statusCode()).isEqualTo(200);
        assertThat(r.headers().firstValue("Content-Type").orElseThrow()).startsWith(contentType);
        assertThat(r.headers().firstValue("X-Content-Type-Options").orElseThrow()).isEqualTo("nosniff");
    }

    private List<Map<String, Object>> fileRows(UUID docId) {
        return jdbc.queryForList("SELECT format, content, size_bytes, octet_length(content) AS octets, sha256, "
                + "created_at FROM application_document_file WHERE document_id = ? ORDER BY format", docId);
    }

    // ------------------------------------------------------------------ list and get

    @Test
    void listIsNewestFirstWithACaseInsensitiveKindFilter() {
        UUID appId = generated();
        UUID cv1 = docId(appId, "CV", 1);
        UUID letter1 = docId(appId, "COVER_LETTER", 1);
        JsonNode v2 = editOk(appId, cv1, content(appId, cv1));

        Res all = api.get(docsPath(appId));
        assertThat(all.status()).as(all.toString()).isEqualTo(200);
        JsonNode list = all.json();
        assertThat(list.isArray()).isTrue();
        assertThat(list.size()).isEqualTo(3);
        assertThat(list.get(0).get("id").asText()).as("the edit is the newest").isEqualTo(v2.get("id").asText());
        assertThat(texts(list).size()).isEqualTo(3);
        List<String> ids = new ArrayList<>();
        list.forEach(n -> ids.add(n.get("id").asText()));
        assertThat(ids).containsExactlyInAnyOrder(v2.get("id").asText(), cv1.toString(), letter1.toString());

        // summary shape: no content, findings or files
        JsonNode s = list.get(0);
        assertThat(s.get("kind").asText()).isEqualTo("CV");
        assertThat(s.get("version").asInt()).isEqualTo(2);
        assertThat(s.get("source").asText()).isEqualTo("EDIT");
        assertThat(s.get("basedOnId").asText()).isEqualTo(cv1.toString());
        assertThat(s.get("profileVersion").asInt()).isEqualTo(currentProfileVersion());
        assertThat(s.get("profileCurrent").asBoolean()).isTrue();
        assertThat(s.get("model").isNull()).isTrue();
        assertThat(s.get("createdAt").asText()).isNotBlank();
        assertThat(s.get("approvedAt").isNull()).isTrue();
        assertThat(s.get("findingCounts").has("blocking")).isTrue();
        assertThat(s.get("findingCounts").has("warning")).isTrue();
        assertThat(s.get("findingCounts").has("info")).isTrue();
        assertThat(s.has("content")).isFalse();
        assertThat(s.has("findings")).isFalse();

        assertThat(versions(api.get(docsPath(appId) + "?kind=CV").json())).containsExactly(2, 1);
        assertThat(versions(api.get(docsPath(appId) + "?kind=cv").json())).containsExactly(2, 1);
        JsonNode letters = api.get(docsPath(appId) + "?kind=Cover_Letter").json();
        assertThat(letters.size()).isEqualTo(1);
        assertThat(letters.get(0).get("id").asText()).isEqualTo(letter1.toString());
        assertThat(letters.get(0).get("source").asText()).isEqualTo("AI");
        assertThat(letters.get(0).get("model").asText()).isEqualTo(FakeTailoringModel.LABEL);
        assertThat(api.get(docsPath(appId) + "?kind=").json().size()).as("blank = both").isEqualTo(3);

        JsonNode bad = assertError(api.get(docsPath(appId) + "?kind=letter"), 400, "INVALID_PARAMETER");
        assertThat(bad.get("message").asText()).isEqualTo(KIND_MESSAGE);
        assertThat(fieldMessages(bad)).containsEntry("kind", KIND_MESSAGE);

        UUID unknown = UUID.randomUUID();
        JsonNode nf = assertError(api.get(docsPath(unknown)), 404, "APPLICATION_NOT_FOUND");
        assertThat(nf.get("message").asText()).isEqualTo("No application with id " + unknown + ".");
        assertError(api.get(BASE + "/not-a-uuid/documents"), 400, "INVALID_ID");
    }

    @Test
    void anApplicationWithoutDocumentsListsNothing() {
        saveProfile();
        UUID appId = createTracked(initechJob());
        Res r = api.get(docsPath(appId));
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.json().size()).isZero();
    }

    @Test
    void getHasTheDocumentShapeAndUnknownOrForeignDocumentsAre404() {
        UUID appId = generated("CV");
        UUID cv1 = docId(appId, "CV", 1);
        JsonNode d = getDoc(appId, cv1);
        assertThat(d.get("id").asText()).isEqualTo(cv1.toString());
        assertThat(d.get("kind").asText()).isEqualTo("CV");
        assertThat(d.get("version").asInt()).isEqualTo(1);
        assertThat(d.get("source").asText()).isEqualTo("AI");
        assertThat(d.get("basedOnId").isNull()).isTrue();
        assertThat(d.get("profileVersion").asInt()).isEqualTo(currentProfileVersion());
        assertThat(d.get("profileCurrent").asBoolean()).isTrue();
        assertThat(d.get("model").asText()).isEqualTo(FakeTailoringModel.LABEL);
        assertThat(d.get("approvedAt").isNull()).isTrue();
        assertThat(d.get("approvalAcknowledged").isNull()).isTrue();
        assertThat(d.get("jobChanged").asBoolean()).isFalse();
        assertThat(d.get("files").isArray()).isTrue();
        assertThat(d.get("files").size()).isZero();
        assertThat(d.at("/content/header/fullName").asText()).isEqualTo(NAME);
        assertThat(d.at("/content/experience/0/ref").asText()).isEqualTo("E1");
        assertThat(d.at("/content/experience/0/title").asText()).isEqualTo("Senior Backend Engineer");
        assertThat(d.get("findings").isArray()).isTrue();
        assertThat(d.at("/findingCounts/info").asInt()).isEqualTo(d.get("findings").size());
        String base = "/api/applications/" + appId + "/documents/" + cv1 + "/file?format=";
        assertThat(d.at("/downloads/pdf").asText()).isEqualTo(base + "pdf");
        assertThat(d.at("/downloads/docx").asText()).isEqualTo(base + "docx");

        // the summary in the application view agrees
        JsonNode app = api.get(BASE + "/" + appId).json();
        assertThat(app.at("/documents/cv/latest/id").asText()).isEqualTo(cv1.toString());
        assertThat(app.at("/documents/cv/versions").asInt()).isEqualTo(1);

        UUID unknown = UUID.randomUUID();
        JsonNode nf = assertError(api.get(docPath(appId, unknown)), 404, "DOCUMENT_NOT_FOUND");
        assertThat(nf.get("message").asText()).isEqualTo("Application " + appId + " has no document version "
                + unknown + ".");
        assertError(api.get(docPath(UUID.randomUUID(), cv1)), 404, "APPLICATION_NOT_FOUND");
        assertError(api.get(docsPath(appId) + "/nope"), 400, "INVALID_ID");

        // a version of another application is not found through this one, on every document endpoint
        UUID otherApp = generatedFor(initechJob("Staff Java Developer"), "CV");
        UUID foreign = docId(otherApp, "CV", 1);
        assertError(api.get(docPath(appId, foreign)), 404, "DOCUMENT_NOT_FOUND");
        assertError(approve(appId, foreign, true), 404, "DOCUMENT_NOT_FOUND");
        assertError(api.get(docPath(appId, foreign) + "/file"), 404, "DOCUMENT_NOT_FOUND");
        assertThat(jdbc.queryForObject("SELECT approved_at FROM application_document WHERE id = ?", Timestamp.class,
                foreign)).isNull();
    }

    // ------------------------------------------------------------------ edit errors

    @Test
    void editRejectsChangedFactsRemovedOrReorderedRolesAndBadRefs() {
        UUID appId = generated("CV");
        UUID cv1 = docId(appId, "CV", 1);
        int pv = currentProfileVersion();
        ObjectNode original = content(appId, cv1);

        ObjectNode title = original.deepCopy();
        ((ObjectNode) title.at("/experience/0")).put("title", "Principal Backend Engineer");
        assertThat(fieldError(edit(appId, cv1, title), "content.experience[0].title")).isEqualTo(
                "content.experience[0].title can't be changed here: it comes from your profile (version " + pv
                        + "). Change it with PUT /api/profile, then generate again.");

        ObjectNode company = original.deepCopy();
        ((ObjectNode) company.at("/experience/1")).put("company", "Globex Corporation");
        assertThat(fieldError(edit(appId, cv1, company), "content.experience[1].company"))
                .startsWith("content.experience[1].company can't be changed here");

        ObjectNode header = original.deepCopy();
        ((ObjectNode) header.get("header")).put("email", "someone@example.com");
        assertThat(fieldError(edit(appId, cv1, header), "content.header.email"))
                .startsWith("content.header.email can't be changed here");

        String rolesMessage = "content.experience: every role from your profile stays in the CV, in profile order "
                + "(E1, E2).";
        ObjectNode removed = original.deepCopy();
        ((ArrayNode) removed.get("experience")).remove(1);
        assertThat(fieldError(edit(appId, cv1, removed), "content.experience")).isEqualTo(rolesMessage);

        ObjectNode reordered = original.deepCopy();
        ArrayNode roles = (ArrayNode) reordered.get("experience");
        JsonNode first = roles.remove(0);
        roles.add(first);
        assertThat(fieldError(edit(appId, cv1, reordered), "content.experience")).isEqualTo(rolesMessage);

        ObjectNode otherRole = original.deepCopy();
        ((ObjectNode) otherRole.at("/experience/0/highlights/0")).put("ref", "E2.H1");
        assertThat(fieldError(edit(appId, cv1, otherRole), "content.experience[0].highlights[0].ref"))
                .isEqualTo("content.experience[0].highlights[0].ref 'E2.H1' belongs to another role.");

        ObjectNode unknownBullet = original.deepCopy();
        ((ObjectNode) unknownBullet.at("/experience/0/highlights/0")).put("ref", "E1.H9");
        assertThat(fieldError(edit(appId, cv1, unknownBullet), "content.experience[0].highlights[0].ref"))
                .isEqualTo("content.experience[0].highlights[0].ref 'E1.H9' is not in profile version " + pv + ".");

        ObjectNode unknownProject = original.deepCopy();
        ObjectNode p9 = mapper.createObjectNode();
        p9.put("ref", "P9");
        p9.put("name", "Ledger");
        unknownProject.set("projects", mapper.createArrayNode().add(p9));
        assertThat(fieldError(edit(appId, cv1, unknownProject), "content.projects[0].ref"))
                .isEqualTo("content.projects[0].ref 'P9' is not in profile version " + pv + ".");

        ObjectNode skill = original.deepCopy();
        ArrayNode skills = (ArrayNode) skill.get("skills");
        int index = skills.size();
        skills.add("Kubernetes");
        assertThat(fieldError(edit(appId, cv1, skill), "content.skills[" + index + "]")).isEqualTo("content.skills["
                + index + "]: 'Kubernetes' is not in your profile skills. Add it to your profile first.");

        ObjectNode longBullet = original.deepCopy();
        ((ObjectNode) longBullet.at("/experience/0/highlights/0")).put("text", "x".repeat(401));
        assertThat(fieldError(edit(appId, cv1, longBullet), "content.experience[0].highlights[0].text"))
                .isEqualTo("content.experience[0].highlights[0].text must be 1 to 400 characters.");

        assertThat(documentCount(appId, "CV")).as("nothing stored").isEqualTo(1);
    }

    @Test
    void editRequiresBasedOnAndContentFromThisApplication() {
        UUID appId = generated("CV");
        UUID cv1 = docId(appId, "CV", 1);
        ObjectNode original = content(appId, cv1);

        assertThat(fieldError(edit(appId, null, original), "basedOn")).isEqualTo("basedOn is required.");
        assertThat(fieldError(edit(appId, cv1, null), "content")).isEqualTo("content is required.");
        JsonNode both = assertError(edit(appId, null, null), 400, "VALIDATION_FAILED");
        assertThat(fields(both)).containsExactly("basedOn", "content");
        Res explicitNull = api.post(docsPath(appId), "{\"basedOn\": \"" + cv1 + "\", \"content\": null}");
        assertThat(fieldError(explicitNull, "content")).isEqualTo("content is required.");

        UUID unknown = UUID.randomUUID();
        assertThat(fieldError(edit(appId, unknown, original), "basedOn"))
                .isEqualTo("No version " + unknown + " in this application.");
        UUID otherApp = generatedFor(initechJob("Staff Java Developer"), "CV");
        UUID foreign = docId(otherApp, "CV", 1);
        assertThat(fieldError(edit(appId, foreign, original), "basedOn"))
                .isEqualTo("No version " + foreign + " in this application.");

        assertError(edit(UUID.randomUUID(), cv1, original), 404, "APPLICATION_NOT_FOUND");
        assertThat(documentCount(appId, "CV")).isEqualTo(1);
        assertThat(documentCount(otherApp, "CV")).isEqualTo(1);
    }

    @Test
    void unknownOrWrongTypedFieldsAreMalformed() {
        UUID appId = generated("CV");
        UUID cv1 = docId(appId, "CV", 1);
        ObjectNode original = content(appId, cv1);

        ObjectNode unknownTop = original.deepCopy();
        unknownTop.put("objective", "x");
        assertThat(malformedField(edit(appId, cv1, unknownTop))).isEqualTo("content.objective");

        ObjectNode unknownNested = original.deepCopy();
        ((ObjectNode) unknownNested.at("/experience/0")).put("titel", "x");
        assertThat(malformedField(edit(appId, cv1, unknownNested))).isEqualTo("content.experience[0].titel");

        ObjectNode wrongType = original.deepCopy();
        wrongType.set("experience", mapper.getNodeFactory().textNode("E1"));
        assertThat(malformedField(edit(appId, cv1, wrongType))).isEqualTo("content.experience");

        Res notObject = api.post(docsPath(appId), "{\"basedOn\": \"" + cv1 + "\", \"content\": \"text\"}");
        assertThat(malformedField(notObject)).isEqualTo("content");

        ObjectNode b = mapper.createObjectNode();
        b.put("basedOn", cv1.toString());
        b.set("content", original);
        b.put("comment", "x");
        assertError(api.post(docsPath(appId), b.toString()), 400, "MALFORMED_REQUEST");

        assertError(api.post(docsPath(appId), "{\"basedOn\": \"not-a-uuid\", \"content\": {}}"), 400,
                "MALFORMED_REQUEST");
        assertThat(documentCount(appId, "CV")).isEqualTo(1);
    }

    // ------------------------------------------------------------------ valid edits

    @Test
    void aValidCvEditIsVersionTwoWithWarningsAndTheBasesProfileVersion() {
        UUID appId = generated("CV");
        UUID cv1 = docId(appId, "CV", 1);
        int pv = currentProfileVersion();
        ObjectNode edited = content(appId, cv1);
        ((ObjectNode) edited.at("/experience/0/highlights/0")).put("text",
                TailoringFixtures.E1H1 + " across 37 regions");

        Res r = edit(appId, cv1, edited);
        assertThat(r.status()).as(r.toString()).isEqualTo(201);
        JsonNode v2 = r.json();
        UUID v2Id = id(v2);
        assertThat(r.header("Location")).isEqualTo("/api/applications/" + appId + "/documents/" + v2Id);
        assertThat(v2.get("kind").asText()).isEqualTo("CV");
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        assertThat(v2.get("source").asText()).isEqualTo("EDIT");
        assertThat(v2.get("basedOnId").asText()).isEqualTo(cv1.toString());
        assertThat(v2.get("profileVersion").asInt()).isEqualTo(pv);
        assertThat(v2.get("profileCurrent").asBoolean()).isTrue();
        assertThat(v2.get("model").isNull()).isTrue();
        assertThat(v2.get("approvedAt").isNull()).isTrue();
        assertThat(v2.at("/content/experience/0/highlights/0/text").asText()).endsWith("across 37 regions");
        assertThat(severities(v2)).contains("WARNING").doesNotContain("BLOCKING");
        JsonNode number = null;
        for (JsonNode f : v2.get("findings")) {
            if ("NUMBER_NOT_IN_PROFILE".equals(f.get("code").asText())) {
                number = f;
            }
        }
        assertThat(number).as("findings %s", v2.get("findings")).isNotNull();
        assertThat(number.get("severity").asText()).isEqualTo("WARNING");
        assertThat(number.get("value").asText()).isEqualTo("37");
        assertThat(v2.at("/findingCounts/blocking").asInt()).isZero();
        assertThat(v2.at("/findingCounts/warning").asInt()).isPositive();

        Map<String, Object> row = jdbc.queryForMap("SELECT d.source, d.model, d.input_hash, d.based_on_id, "
                + "d.profile_version, d.job_updated_at, j.updated_at AS job_now FROM application_document d "
                + "JOIN application a ON a.id = d.application_id JOIN job j ON j.id = a.job_id WHERE d.id = ?", v2Id);
        assertThat(row.get("source")).isEqualTo("EDIT");
        assertThat(row.get("model")).isNull();
        assertThat(row.get("input_hash")).isNull();
        assertThat(row.get("based_on_id")).isEqualTo(cv1);
        assertThat(row.get("profile_version")).isEqualTo(pv);
        assertThat(row.get("job_updated_at")).isEqualTo(row.get("job_now"));
        assertThat(getDoc(appId, v2Id).get("findings")).isEqualTo(v2.get("findings"));

        // after a profile save, an edit of v1 still validates against (and records) v1's profile version
        int newer = saveChangedProfile("Staff backend engineer");
        assertThat(newer).isGreaterThan(pv);
        JsonNode v3 = editOk(appId, cv1, content(appId, cv1));
        assertThat(v3.get("version").asInt()).isEqualTo(3);
        assertThat(v3.get("profileVersion").asInt()).isEqualTo(pv);
        assertThat(v3.get("profileCurrent").asBoolean()).isFalse();
        assertThat(findingCodes(v3).get(0)).isEqualTo("PROFILE_CHANGED");
        // an edit of v2 (an EDIT version) works too and keeps the chain
        JsonNode v4 = editOk(appId, v2Id, content(appId, v2Id));
        assertThat(v4.get("version").asInt()).isEqualTo(4);
        assertThat(v4.get("basedOnId").asText()).isEqualTo(v2Id.toString());
    }

    @Test
    void aLetterEditKeepsTheFixedPartsAndAnAddedNumberIsOnlyAWarning() {
        UUID appId = generated("COVER_LETTER");
        UUID l1 = docId(appId, "COVER_LETTER", 1);
        ObjectNode base = content(appId, l1);
        assertThat(getDoc(appId, l1).at("/findingCounts/blocking").asInt()).isZero();

        ObjectNode edited = base.deepCopy();
        ((ArrayNode) edited.get("paragraphs")).set(1, mapper.getNodeFactory().textNode(THIRTEEN));
        JsonNode v2 = editOk(appId, l1, edited);
        assertThat(v2.get("kind").asText()).isEqualTo("COVER_LETTER");
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        assertThat(v2.at("/content/paragraphs/1").asText()).isEqualTo(THIRTEEN);
        for (String fixed : List.of("header", "company", "jobTitle", "date", "salutation", "closing", "signature")) {
            assertThat(v2.at("/content/" + fixed)).as(fixed).isEqualTo(base.get(fixed));
        }
        assertThat(severities(v2)).contains("WARNING").doesNotContain("BLOCKING");
        assertThat(findingCodes(v2)).contains("NUMBER_NOT_IN_PROFILE");
        assertThat(v2.at("/findingCounts/blocking").asInt()).isZero();

        ObjectNode salutation = base.deepCopy();
        salutation.put("salutation", "Hi there,");
        assertThat(fieldError(edit(appId, l1, salutation), "content.salutation"))
                .startsWith("content.salutation can't be changed here");
        ObjectNode signature = base.deepCopy();
        signature.put("signature", "Someone Else");
        assertThat(fieldError(edit(appId, l1, signature), "content.signature"))
                .startsWith("content.signature can't be changed here");
        ObjectNode tooMany = base.deepCopy();
        ArrayNode paragraphs = mapper.createArrayNode();
        for (int i = 0; i < 9; i++) {
            paragraphs.add("Paragraph " + (i + 1) + ".");
        }
        tooMany.set("paragraphs", paragraphs);
        assertThat(fieldError(edit(appId, l1, tooMany), "content.paragraphs")).isEqualTo(
                "content.paragraphs must have 1 to 8 paragraphs of at most 1500 characters, 600 words in total.");
        assertThat(documentCount(appId, "COVER_LETTER")).isEqualTo(2);
    }

    // ------------------------------------------------------------------ approve

    @Test
    void approvingABlockingLetterNeedsTheAcknowledgementAndStoresBothFiles() throws Exception {
        saveProfile();
        tailor.respondJson(DocumentKind.COVER_LETTER, FakeTailoringModel.letter(G0, THIRTEEN, G1, G2, G1, G2));
        UUID appId = generated("COVER_LETTER");
        UUID l1 = docId(appId, "COVER_LETTER", 1);

        for (Boolean ack : new Boolean[] {null, false}) {
            JsonNode err = assertError(approve(appId, l1, ack), 409, "FINDINGS_NOT_ACKNOWLEDGED");
            assertThat(err.get("message").asText()).isEqualTo("This version has 1 finding(s) that need your review "
                    + "(NUMBER_NOT_IN_PROFILE). Read them, then approve again with acknowledgeFindings=true.");
        }
        assertThat(jdbc.queryForObject("SELECT approved_at FROM application_document WHERE id = ?", Timestamp.class,
                l1)).isNull();
        assertThat(fileRows(l1)).isEmpty();
        assertError(api.post(docPath(appId, l1) + "/approve", "{\"acknowledge\": true}"), 400, "MALFORMED_REQUEST");

        JsonNode approved = approveOk(appId, l1, true);
        assertThat(approved.get("approvedAt").isNull()).isFalse();
        assertThat(approved.get("approvalAcknowledged").asBoolean()).isTrue();
        assertThat(texts(approved.get("files"))).containsExactly("PDF", "DOCX");
        assertThat(findingCodes(approved)).contains("NUMBER_NOT_IN_PROFILE");

        List<Map<String, Object>> files = fileRows(l1);
        assertThat(files).extracting(f -> f.get("format")).containsExactlyInAnyOrder("PDF", "DOCX");
        for (Map<String, Object> f : files) {
            byte[] bytes = (byte[]) f.get("content");
            assertThat(bytes.length).isPositive();
            assertThat(((Number) f.get("size_bytes")).intValue()).isEqualTo(((Number) f.get("octets")).intValue())
                    .isEqualTo(bytes.length);
            assertThat(f.get("sha256")).isEqualTo(sha256(bytes));
        }

        // approving again changes nothing (with or without the acknowledgement)
        JsonNode again = approveOk(appId, l1, null);
        assertThat(again.get("approvedAt").asText()).isEqualTo(approved.get("approvedAt").asText());
        assertThat(again.get("approvalAcknowledged").asBoolean()).isTrue();
        assertThat(texts(again.get("files"))).containsExactly("PDF", "DOCX");
        approveOk(appId, l1, true);
        List<Map<String, Object>> after = fileRows(l1);
        assertThat(after).hasSize(2);
        for (int i = 0; i < 2; i++) {
            assertThat(after.get(i).get("sha256")).isEqualTo(files.get(i).get("sha256"));
            assertThat(after.get(i).get("created_at")).isEqualTo(files.get(i).get("created_at"));
        }
        // the application view shows the approved version
        JsonNode app = api.get(BASE + "/" + appId).json();
        assertThat(app.at("/documents/coverLetter/approved/id").asText()).isEqualTo(l1.toString());
        assertThat(app.at("/documents/coverLetter/approved/approvedAt").asText())
                .isEqualTo(approved.get("approvedAt").asText());
    }

    @Test
    void withoutBlockingFindingsApprovalNeedsNoBodyAndIsNotAcknowledged() {
        UUID appId = generated("CV");
        UUID cv1 = docId(appId, "CV", 1);
        assertThat(getDoc(appId, cv1).at("/findingCounts/blocking").asInt()).isZero();
        JsonNode approved = approveOk(appId, cv1, null);
        assertThat(approved.get("approvalAcknowledged").asBoolean()).isFalse();
        assertThat(approved.get("approvalAcknowledged").isBoolean()).isTrue();
        assertThat(texts(approved.get("files"))).containsExactly("PDF", "DOCX");
        assertThat(jdbc.queryForObject("SELECT approval_acknowledged FROM application_document WHERE id = ?",
                Boolean.class, cv1)).isFalse();
        assertError(approve(UUID.randomUUID(), cv1, null), 404, "APPLICATION_NOT_FOUND");
        assertError(approve(appId, UUID.randomUUID(), null), 404, "DOCUMENT_NOT_FOUND");
    }

    // ------------------------------------------------------------------ downloads

    @Test
    void anApprovedDownloadIsTheStoredBytesWithoutDraftText() throws Exception {
        UUID appId = generated("CV");
        UUID cv1 = docId(appId, "CV", 1);
        approveOk(appId, cv1, null);
        Map<String, byte[]> stored = new java.util.HashMap<>();
        fileRows(cv1).forEach(f -> stored.put((String) f.get("format"), (byte[]) f.get("content")));

        HttpResponse<byte[]> pdf1 = download(appId, cv1, null);
        assertDownloadHeaders(pdf1, "application/pdf");
        HttpResponse<byte[]> pdf2 = download(appId, cv1, "format=pdf");
        assertThat(pdf1.body()).isEqualTo(stored.get("PDF"));
        assertThat(sha256(pdf2.body())).isEqualTo(sha256(pdf1.body()));
        String pdfName = fileName(pdf1);
        assertThat(pdfName).isEqualTo("CV - " + NAME + " - Initech.pdf");
        String text = pdfText(pdf1.body());
        assertThat(text).contains("Senior Backend Engineer").doesNotContain("DRAFT");

        for (String q : List.of("format=docx", "format=DOCX", "format=%20Docx%20")) {
            HttpResponse<byte[]> docx = download(appId, cv1, q);
            assertDownloadHeaders(docx, DOCX_TYPE);
            assertThat(docx.body()).as(q).isEqualTo(stored.get("DOCX"));
            assertThat(fileName(docx)).isEqualTo("CV - " + NAME + " - Initech.docx");
        }
        assertThat(docxText(stored.get("DOCX"))).contains("Senior Backend Engineer").doesNotContain("DRAFT");

        for (String q : List.of("format=xls", "format=doc")) {
            JsonNode err = assertError(api.get(docPath(appId, cv1) + "/file?" + q), 400, "INVALID_PARAMETER");
            assertThat(err.get("message").asText()).isEqualTo(FORMAT_MESSAGE);
        }
        assertError(api.get(docPath(appId, UUID.randomUUID()) + "/file"), 404, "DOCUMENT_NOT_FOUND");
        assertError(api.get(docPath(UUID.randomUUID(), cv1) + "/file"), 404, "APPLICATION_NOT_FOUND");
    }

    @Test
    void anUnapprovedDownloadIsADraft() throws Exception {
        UUID appId = generated("COVER_LETTER");
        UUID l1 = docId(appId, "COVER_LETTER", 1);

        HttpResponse<byte[]> pdf = download(appId, l1, null);
        assertDownloadHeaders(pdf, "application/pdf");
        assertThat(fileName(pdf)).isEqualTo("Cover letter - " + NAME + " - Initech-draft.pdf");
        assertThat(pdfText(pdf.body())).contains(Block.Footer.DRAFT_TEXT);

        HttpResponse<byte[]> docx = download(appId, l1, "format=docx");
        assertDownloadHeaders(docx, DOCX_TYPE);
        assertThat(fileName(docx)).isEqualTo("Cover letter - " + NAME + " - Initech-draft.docx");
        assertThat(docxText(docx.body())).contains(Block.Footer.DRAFT_TEXT);
        assertThat(fileRows(l1)).as("drafts are not stored").isEmpty();

        // once approved, the same version downloads without the footer
        approveOk(appId, l1, null);
        HttpResponse<byte[]> approved = download(appId, l1, null);
        assertThat(fileName(approved)).isEqualTo("Cover letter - " + NAME + " - Initech.pdf");
        assertThat(pdfText(approved.body())).doesNotContain("DRAFT");
    }

    // ------------------------------------------------------------------ computed fields

    @Test
    void aProfileSaveMakesVersionsProfileChangedAndApprovalNeedsTheAcknowledgement() {
        UUID appId = generated("CV");
        UUID cv1 = docId(appId, "CV", 1);
        int pv = currentProfileVersion();
        JsonNode before = getDoc(appId, cv1);
        int storedBlocking = before.at("/findingCounts/blocking").asInt();
        int storedFindings = before.get("findings").size();
        assertThat(storedBlocking).isZero();

        int newer = saveChangedProfile("Staff backend engineer");
        JsonNode d = getDoc(appId, cv1);
        assertThat(d.get("profileCurrent").asBoolean()).isFalse();
        assertThat(d.get("profileVersion").asInt()).isEqualTo(pv);
        assertThat(d.get("findings").size()).isEqualTo(storedFindings + 1);
        JsonNode first = d.at("/findings/0");
        assertThat(first.get("code").asText()).isEqualTo("PROFILE_CHANGED");
        assertThat(first.get("severity").asText()).isEqualTo("BLOCKING");
        assertThat(first.get("message").asText()).contains("profile version " + pv, "the current one is " + newer);
        assertThat(d.at("/findingCounts/blocking").asInt()).isEqualTo(1);

        JsonNode summary = api.get(docsPath(appId)).json().get(0);
        assertThat(summary.get("profileCurrent").asBoolean()).isFalse();
        assertThat(summary.at("/findingCounts/blocking").asInt()).isEqualTo(1);
        JsonNode latest = api.get(BASE + "/" + appId).json().at("/documents/cv/latest");
        assertThat(latest.get("profileCurrent").asBoolean()).isFalse();
        assertThat(latest.at("/findingCounts/blocking").asInt()).isEqualTo(1);
        assertThat(latest.at("/findingCounts/info").asInt()).isEqualTo(d.at("/findingCounts/info").asInt());

        JsonNode err = assertError(approve(appId, cv1, null), 409, "FINDINGS_NOT_ACKNOWLEDGED");
        assertThat(err.get("message").asText()).isEqualTo("This version has 1 finding(s) that need your review "
                + "(PROFILE_CHANGED). Read them, then approve again with acknowledgeFindings=true.");
        JsonNode approved = approveOk(appId, cv1, true);
        assertThat(approved.get("approvalAcknowledged").asBoolean()).isTrue();
        assertThat(findingCodes(approved).get(0)).isEqualTo("PROFILE_CHANGED");
        // the stored findings are unchanged (computed only)
        assertThat(json(jdbc.queryForObject("SELECT findings::text FROM application_document WHERE id = ?",
                String.class, cv1)).size()).isEqualTo(storedFindings);
    }

    @Test
    void aBlockingLetterAfterAProfileSaveCountsBothCodes() {
        saveProfile();
        tailor.respondJson(DocumentKind.COVER_LETTER, FakeTailoringModel.letter(G0, THIRTEEN, G1, G2, G1, G2));
        UUID appId = generated("COVER_LETTER");
        UUID l1 = docId(appId, "COVER_LETTER", 1);
        saveChangedProfile("Staff backend engineer");
        JsonNode err = assertError(approve(appId, l1, false), 409, "FINDINGS_NOT_ACKNOWLEDGED");
        assertThat(err.get("message").asText()).isEqualTo("This version has 2 finding(s) that need your review "
                + "(PROFILE_CHANGED, NUMBER_NOT_IN_PROFILE). Read them, then approve again with "
                + "acknowledgeFindings=true.");
    }

    @Test
    void aJobUpdateSetsJobChanged() {
        UUID job = initechJob();
        UUID appId = generatedFor(job, "CV");
        UUID cv1 = docId(appId, "CV", 1);
        assertThat(getDoc(appId, cv1).get("jobChanged").asBoolean()).isFalse();

        Res put = api.put("/api/jobs/" + job, jobBody("Senior Java Developer", "Initech",
                JOB_DESCRIPTION + " We also use Terraform.", "Java", "Kubernetes", "~Docker"));
        assertThat(put.status()).as(put.toString()).isEqualTo(200);
        assertThat(getDoc(appId, cv1).get("jobChanged").asBoolean()).isTrue();

        // an edit made now is built against the updated job
        JsonNode v2 = editOk(appId, cv1, content(appId, cv1));
        assertThat(v2.get("jobChanged").asBoolean()).isFalse();
    }

    @Test
    void aDeletedJobIsNotJobChangedAndEditsStillWork() {
        UUID job = initechJob();
        UUID appId = generatedFor(job, "CV");
        UUID cv1 = docId(appId, "CV", 1);
        Res put = api.put("/api/jobs/" + job, jobBody("Senior Java Developer", "Initech",
                JOB_DESCRIPTION + " Changed.", "Java", "Kubernetes", "~Docker"));
        assertThat(put.status()).isEqualTo(200);
        assertThat(getDoc(appId, cv1).get("jobChanged").asBoolean()).isTrue();

        assertThat(api.delete("/api/jobs/" + job).status()).isEqualTo(204);
        assertThat(getDoc(appId, cv1).get("jobChanged").asBoolean()).isFalse();
        JsonNode v2 = editOk(appId, cv1, content(appId, cv1));
        assertThat(v2.get("version").asInt()).isEqualTo(2);
        assertThat(v2.get("jobChanged").asBoolean()).isFalse();
        assertThat(jdbc.queryForObject("SELECT job_updated_at FROM application_document WHERE id = ?",
                Timestamp.class, id(v2))).isNull();
        // and it can be approved and downloaded (the company comes from the snapshot)
        approveOk(appId, id(v2), null);
        HttpResponse<byte[]> pdf = download(appId, id(v2), null);
        assertThat(pdf.statusCode()).isEqualTo(200);
        assertThat(fileName(pdf)).isEqualTo("CV - " + NAME + " - Initech.pdf");
    }

    // ------------------------------------------------------------------ sent versions in the tracker PUT

    @Test
    void enteringAppliedSendsTheNewestApprovedVersionsAndExplicitIdsAreValidated() {
        UUID appId = generated();
        UUID cv1 = docId(appId, "CV", 1);
        UUID letter1 = docId(appId, "COVER_LETTER", 1);
        approveOk(appId, cv1, null);
        UUID cv2 = id(editOk(appId, cv1, content(appId, cv1)));
        approveOk(appId, cv2, null);
        UUID cv3 = id(editOk(appId, cv2, content(appId, cv2)));

        Res unapproved = api.put(BASE + "/" + appId, api.json(body("status", "APPLIED", "sentCvId", cv3)));
        assertThat(fieldError(unapproved, "sentCvId")).isEqualTo("CV version 3 is not approved yet. Approve it "
                + "first (POST /api/applications/" + appId + "/documents/" + cv3 + "/approve) or choose an approved "
                + "version.");
        Res letterAsCv = api.put(BASE + "/" + appId, api.json(body("status", "APPLIED", "sentCvId", letter1)));
        assertThat(fieldError(letterAsCv, "sentCvId")).isEqualTo("No CV version " + letter1 + " in this application.");
        Res cvAsLetter = api.put(BASE + "/" + appId,
                api.json(body("status", "APPLIED", "sentCoverLetterId", cv1)));
        assertThat(fieldError(cvAsLetter, "sentCoverLetterId"))
                .isEqualTo("No cover letter version " + cv1 + " in this application.");
        Res unapprovedLetter = api.put(BASE + "/" + appId,
                api.json(body("status", "APPLIED", "sentCoverLetterId", letter1)));
        assertThat(fieldError(unapprovedLetter, "sentCoverLetterId")).startsWith("Cover letter version 1 is not "
                + "approved yet.");
        assertThat(jdbc.queryForObject("SELECT status FROM application WHERE id = ?", String.class, appId))
                .isEqualTo("NEW");

        Res applied = api.put(BASE + "/" + appId, api.json(body("status", "APPLIED")));
        assertThat(applied.status()).as(applied.toString()).isEqualTo(200);
        assertThat(applied.json().at("/sentCv/id").asText()).isEqualTo(cv2.toString());
        assertThat(applied.json().at("/sentCv/version").asInt()).isEqualTo(2);
        assertThat(applied.json().get("sentCoverLetter").isNull()).isTrue();

        // an explicit older approved version is accepted while applied
        approveOk(appId, letter1, null);
        Res older = api.put(BASE + "/" + appId, api.json(body("status", "APPLIED", "sentCvId", cv1,
                "sentCoverLetterId", letter1)));
        assertThat(older.status()).as(older.toString()).isEqualTo(200);
        assertThat(older.json().at("/sentCv/version").asInt()).isEqualTo(1);
        assertThat(older.json().at("/sentCoverLetter/id").asText()).isEqualTo(letter1.toString());
    }

    // ------------------------------------------------------------------ §9.2.11 logging

    @Test
    void documentLogLinesCarryIdsAndCountsOnly(CapturedOutput output) throws Exception {
        saveProfile();
        tailor.respondJson(DocumentKind.COVER_LETTER, FakeTailoringModel.letter(G0, THIRTEEN, G1, G2, G1, G2));
        UUID appId = generated();
        UUID cv1 = docId(appId, "CV", 1);
        UUID l1 = docId(appId, "COVER_LETTER", 1);
        String secret = "Orchestrated Quibblewick migrations across 37 regions";
        ObjectNode edited = content(appId, cv1);
        ((ObjectNode) edited.at("/experience/0/highlights/0")).put("text", secret);
        UUID cv2 = id(editOk(appId, cv1, edited));
        assertError(approve(appId, l1, null), 409, "FINDINGS_NOT_ACKNOWLEDGED");
        approveOk(appId, cv2, null);
        approveOk(appId, cv2, null);
        approveOk(appId, l1, true);
        download(appId, cv2, "format=docx");
        download(appId, cv1, null);

        String all = output.getAll();
        assertThat(all).contains("Document edited application=" + appId + " document=" + cv2 + " kind=CV version=2 "
                + "basedOn=" + cv1 + " warnings=");
        assertThat(all).contains("Document approved application=" + appId + " document=" + cv2 + " kind=CV "
                + "version=2 acknowledged=false blocking=0 pdfBytes=");
        assertThat(all).contains("Document approved application=" + appId + " document=" + l1
                + " kind=COVER_LETTER version=1 acknowledged=true blocking=1 pdfBytes=");
        List<String> lines = all.lines().filter(l -> l.contains("Document edited") || l.contains("Document approved")
                || l.contains("Document already approved")).toList();
        assertThat(lines).hasSizeGreaterThanOrEqualTo(3);
        Pattern safe = Pattern.compile(".*Document (edited|approved|already approved)( [a-zA-Z]+=[A-Za-z0-9_-]+)+\\s*$");
        for (String line : lines) {
            assertThat(line).as("only key=value ids and counts").matches(safe);
        }
        List<String> forbidden = List.of(NAME, "Ndlovu", "Müller", EMAIL, PHONE,           // owner
                "CV - ", "Cover letter - ", ".pdf", ".docx", "-draft",                     // file names
                secret, "Quibblewick", "37 regions", TailoringFixtures.E1H1, THIRTEEN, G0, // content
                "13 years", "is not in your profile", "Check it before approving",       // finding values/messages
                JOB_DESCRIPTION, "Zephyrine");                                             // job description
        for (String s : forbidden) {
            assertThat(all).as("logs must not contain '%s'", s).doesNotContain(s);
        }
    }
}
