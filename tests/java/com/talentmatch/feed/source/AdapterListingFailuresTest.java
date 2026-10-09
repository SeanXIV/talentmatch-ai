package com.talentmatch.feed.source;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** §9.1 item 1, shared rows: unmappable postings skipped, malformed JSON and the posting cap → INVALID_RESPONSE. */
class AdapterListingFailuresTest {

    /** One adapter under test: its fixture, where the postings array lives, and its id/URL field names. */
    record Case(String name, String fixture, String urlField, int fixturePostings) {

        PostingFields.ParsedListing parse(byte[] body, int maxPostings) {
            SourceProperties props = Fixtures.props(maxPostings);
            SourceHttpClient client = Fixtures.client(props);
            SourceTarget target = new SourceTarget("acme", "Acme");
            return switch (name) {
                case "greenhouse" -> new GreenhouseAdapter(client, props).parseListing(body, target, "h/p");
                case "lever" -> new LeverAdapter(client, props).parseListing(body, target, "h/p");
                case "ashby" -> new AshbyAdapter(client, props).parseListing(body, target, "h/p");
                default -> throw new IllegalArgumentException(name);
            };
        }

        ArrayNode postings(JsonNode root) {
            return (ArrayNode) (root.isArray() ? root : root.get("jobs"));
        }

        @Override
        public String toString() {
            return name;
        }
    }

    static Stream<Arguments> adapters() {
        return Stream.of(
                Arguments.of(new Case("greenhouse", "greenhouse-list.json", "absolute_url", 4)),
                Arguments.of(new Case("lever", "lever.json", "hostedUrl", 4)),
                Arguments.of(new Case("ashby", "ashby.json", "jobUrl", 3)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    void postingWithoutIdIsSkipped(Case c) {
        JsonNode root = Fixtures.tree(c.fixture());
        ((ObjectNode) c.postings(root).get(0)).remove("id");
        PostingFields.ParsedListing parsed = c.parse(Fixtures.write(root), 5000);
        assertThat(parsed.skipped()).isEqualTo(1);
        assertThat(parsed.postings()).hasSize(c.fixturePostings() - 1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    void postingWithJavascriptUrlIsSkipped(Case c) {
        JsonNode root = Fixtures.tree(c.fixture());
        ((ObjectNode) c.postings(root).get(0)).put(c.urlField(), "javascript:alert(1)");
        ((ObjectNode) c.postings(root).get(1)).put(c.urlField(), "/relative/path");
        PostingFields.ParsedListing parsed = c.parse(Fixtures.write(root), 5000);
        assertThat(parsed.skipped()).isEqualTo(2);
        assertThat(parsed.postings()).hasSize(c.fixturePostings() - 2);
        assertThat(parsed.postings()).extracting(RawPosting::url).allMatch(u -> u.startsWith("https://"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    void repeatedIdIsSkipped(Case c) {
        JsonNode root = Fixtures.tree(c.fixture());
        String firstId = c.postings(root).get(0).get("id").asText();
        ((ObjectNode) c.postings(root).get(1)).put("id", firstId);
        PostingFields.ParsedListing parsed = c.parse(Fixtures.write(root), 5000);
        assertThat(parsed.skipped()).isEqualTo(1);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    void malformedJsonIsInvalidResponseWithoutBodyText(Case c) {
        byte[] body = "{\"jobs\": [ {\"id\": \"SECRET-BODY-TEXT\", ".getBytes(StandardCharsets.UTF_8);
        SourceException e = assertThrows(SourceException.class, () -> c.parse(body, 5000));
        assertThat(e.kind()).isEqualTo(SourceFailure.Kind.INVALID_RESPONSE);
        assertThat(e.getMessage()).doesNotContain("SECRET-BODY-TEXT");
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    void emptyBodyAndWrongShapeAreInvalidResponse(Case c) {
        SourceException empty = assertThrows(SourceException.class, () -> c.parse(new byte[0], 5000));
        assertThat(empty.kind()).isEqualTo(SourceFailure.Kind.INVALID_RESPONSE);
        byte[] wrongShape = (c.name().equals("lever") ? "{\"jobs\":[]}" : "[]").getBytes(StandardCharsets.UTF_8);
        SourceException shape = assertThrows(SourceException.class, () -> c.parse(wrongShape, 5000));
        assertThat(shape.kind()).isEqualTo(SourceFailure.Kind.INVALID_RESPONSE);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("adapters")
    void moreThanMaxPostingsIsInvalidResponse(Case c) {
        byte[] body = Fixtures.bytes(c.fixture());
        SourceException e = assertThrows(SourceException.class, () -> c.parse(body, 2));
        assertThat(e.kind()).isEqualTo(SourceFailure.Kind.INVALID_RESPONSE);
        assertThat(c.parse(body, 4).postings()).hasSize(c.fixturePostings());       // exactly at the cap is fine
    }
}
