package com.talentmatch.feed.source;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;

/** Fixture loading and adapter construction for the source-layer tests. */
final class Fixtures {

    static final ObjectMapper MAPPER = new ObjectMapper();

    private Fixtures() {
    }

    static byte[] bytes(String name) {
        try (InputStream in = Fixtures.class.getResourceAsStream("/feed/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture feed/" + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static JsonNode tree(String name) {
        try {
            return MAPPER.readTree(bytes(name));
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static byte[] write(JsonNode node) {
        try {
            return MAPPER.writeValueAsBytes(node);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    static ObjectNode obj(JsonNode node) {
        return (ObjectNode) node;
    }

    static SourceProperties.Http http(Duration connect, Duration read, long maxBody, Duration spacing) {
        return new SourceProperties.Http(connect, read, maxBody, 2, spacing, "TalentMatch-Test/1.0 (qa)");
    }

    static SourceProperties props(int maxPostings) {
        return new SourceProperties(maxPostings, http(Duration.ofSeconds(2), Duration.ofSeconds(2), 20_971_520L,
                Duration.ZERO), null, null, null);
    }

    /** Every provider pointed at one stub. */
    static SourceProperties stubProps(URI base, URI euBase) {
        return new SourceProperties(5000,
                http(Duration.ofSeconds(2), Duration.ofSeconds(2), 20_971_520L, Duration.ZERO),
                new SourceProperties.Greenhouse(base, 20),
                new SourceProperties.Lever(base, euBase),
                new SourceProperties.Ashby(base));
    }

    static SourceHttpClient client(SourceProperties props) {
        return new SourceHttpClient(props, Clock.systemUTC());
    }
}
