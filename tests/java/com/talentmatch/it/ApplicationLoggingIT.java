package com.talentmatch.it;

import static com.talentmatch.application.tailoring.TailoringFixtures.EMAIL;
import static com.talentmatch.application.tailoring.TailoringFixtures.NAME;
import static com.talentmatch.application.tailoring.TailoringFixtures.PHONE;
import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.talentmatch.application.content.DocumentKind;
import com.talentmatch.application.tailoring.TailoringFixtures;
import com.talentmatch.support.AbstractGenerationIT;
import com.talentmatch.support.Api.Res;
import com.talentmatch.support.FakeTailoringModel;
import java.net.SocketTimeoutException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

/**
 * Phase 7 §9.2 item 11 (logging), for the paths the generation and document logging tests don't drive:
 * the tracker (notes on create, replace and clear, rejected notes, malformed bodies, denied and forced
 * transitions, delete), rejected document edits, a failing model call whose exception message carries
 * text, and a letter in the configured zone. No notes, highlight or generated text, finding value, job
 * description, file name or owner detail may appear in any captured line.
 */
@ExtendWith(OutputCaptureExtension.class)
class ApplicationLoggingIT extends AbstractGenerationIT {

    private static final String NOTES = "Recruiter Bartholomew Pennywhistle said salary is negotiable";
    private static final String NEW_NOTES = "Second call with Ottoline Fairweather on Tuesday";
    private static final String TOO_LONG_SECRET = "Gwendolyn Thistlewood ";
    private static final String MALFORMED_SECRET = "Cornelius Ravensworth";
    private static final String EDIT_SECRET = "Archduke of Snorkelvania";
    private static final String MODEL_SECRET = "Provider said: Prudence Larkspur's CV is too long";

    private Res put(UUID id, String json) {
        return api.put(BASE + "/" + id, json);
    }

    private String docsPath(UUID appId) {
        return BASE + "/" + appId + "/documents";
    }

    private UUID docId(UUID appId, String kind, int version) {
        return jdbc.queryForObject("SELECT id FROM application_document WHERE application_id = ? AND kind = ? "
                + "AND version = ?", UUID.class, appId, kind, version);
    }

    private ObjectNode content(UUID appId, UUID docId) {
        Res r = api.get(docsPath(appId) + "/" + docId);
        assertThat(r.status()).as("GET document %s", r).isEqualTo(200);
        return (ObjectNode) r.json().get("content").deepCopy();
    }

    private Res edit(UUID appId, UUID basedOn, JsonNode content) {
        ObjectNode b = mapper.createObjectNode();
        b.put("basedOn", basedOn.toString());
        b.set("content", content);
        return api.post(docsPath(appId), b.toString());
    }

    @Test
    void trackerDocumentAndFailurePathsLogIdsOnly(CapturedOutput output) {
        saveProfile();

        // ---- tracker: notes on create, replace, clear; rejected and malformed bodies; transitions; delete
        Res created = createApplication(initechJob(), "notes", NOTES);
        assertThat(created.status()).as(created.toString()).isEqualTo(201);
        UUID tracked = id(created.json());
        assertThat(put(tracked, api.json(body("status", "SHORTLISTED", "notes", NEW_NOTES))).status())
                .isEqualTo(200);
        Res tooLong = put(tracked, api.json(body("status", "SHORTLISTED", "notes", TOO_LONG_SECRET.repeat(500))));
        assertError(tooLong, 400, "VALIDATION_FAILED");
        Res malformed = put(tracked, "{\"status\": \"SHORTLISTED\", \"notes\": \"" + MALFORMED_SECRET + "\", "
                + "\"secretField\": \"" + MALFORMED_SECRET + "\"}");
        assertError(malformed, 400, "MALFORMED_REQUEST");
        Res broken = put(tracked, "{\"notes\": \"" + MALFORMED_SECRET + "\" \"status\"");
        assertThat(broken.status()).isEqualTo(400);
        Res denied = put(tracked, api.json(body("status", "OFFER", "notes", NEW_NOTES)));
        assertError(denied, 409, "INVALID_STATUS_TRANSITION");
        assertThat(put(tracked, api.json(body("status", "OFFER", "force", true, "notes", NEW_NOTES))).status())
                .isEqualTo(200);
        assertThat(put(tracked, api.json(body("status", "OFFER", "notes", null))).status()).isEqualTo(200);
        assertThat(api.delete(BASE + "/" + tracked).status()).isEqualTo(204);

        // ---- documents: rejected edits carrying text in read-only and free-text fields
        tailor.respondJson(DocumentKind.COVER_LETTER, FakeTailoringModel.LETTER_JSON);
        JsonNode app = createGenerating(initechJob("Staff Java Developer"), "notes", NOTES);
        UUID appId = id(app);
        awaitGeneration(appId, generationId(app), "SUCCEEDED");
        UUID cv1 = docId(appId, "CV", 1);
        UUID l1 = docId(appId, "COVER_LETTER", 1);
        ObjectNode badTitle = content(appId, cv1);
        ((ObjectNode) badTitle.at("/experience/0")).put("title", EDIT_SECRET);
        assertError(edit(appId, cv1, badTitle), 400, "VALIDATION_FAILED");
        ObjectNode badSkills = content(appId, cv1);
        badSkills.putArray("skills").add(EDIT_SECRET);
        assertError(edit(appId, cv1, badSkills), 400, "VALIDATION_FAILED");
        ObjectNode badLetter = content(appId, l1);
        badLetter.put("signature", EDIT_SECRET);
        assertError(edit(appId, l1, badLetter), 400, "VALIDATION_FAILED");

        // ---- a failing model call whose exception message carries text
        tailor.fail(new RuntimeException(MODEL_SECRET, new SocketTimeoutException(MODEL_SECRET)));
        Res again = postGeneration(appId, null);
        assertThat(again.status()).as(again.toString()).isEqualTo(202);
        assertThat(awaitGeneration(appId, id(again.json()), "FAILED").get("failureReason").asText())
                .isEqualTo("TIMEOUT");
        awaitAiIdle();

        String all = output.getAll();
        assertThat(all).contains("Application created id=" + tracked);
        assertThat(all).contains("Application status changed id=" + tracked + " from=NEW to=SHORTLISTED forced=false");
        assertThat(all).contains("Application status changed id=" + tracked + " from=SHORTLISTED to=OFFER forced=true");
        assertThat(all).contains("Application deleted id=" + tracked + " status=OFFER");
        assertThat(all).contains("outcome=timeout");
        List<String> forbidden = List.of(
                NOTES, "Bartholomew", "Pennywhistle", NEW_NOTES, "Ottoline", "Fairweather",   // notes
                "Gwendolyn", "Thistlewood", MALFORMED_SECRET, "Ravensworth",                  // rejected notes
                EDIT_SECRET, "Snorkelvania",                                                  // rejected edits
                MODEL_SECRET, "Prudence", "Larkspur",                                         // exception text
                TailoringFixtures.E1H1, TailoringFixtures.E2H1, "API latency",                // highlight text
                FakeTailoringModel.G0, FakeTailoringModel.G1, "Kind regards",                 // generated text
                JOB_DESCRIPTION, "Zephyrine", "Quillfeather",                                 // job description
                NAME, "Ndlovu", "Müller", EMAIL, PHONE,                                       // owner
                "CV - ", "Cover letter - ", ".pdf", ".docx");                                 // file names
        for (String s : forbidden) {
            assertThat(all).as("logs must not contain '%s'", s).doesNotContain(s);
        }
    }
}
