package com.talentmatch.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpHeaders;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

/** Minimal JDK-HttpClient wrapper: raw status, headers and body, plus parsed JSON. */
public final class Api {

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final String base;
    private final ObjectMapper mapper;

    public Api(int port, ObjectMapper mapper) {
        this.base = "http://localhost:" + port;
        this.mapper = mapper;
    }

    public record Res(int status, HttpHeaders headers, String body, JsonNode json) {

        public String header(String name) {
            return headers.firstValue(name).orElse(null);
        }

        @Override
        public String toString() {
            return status + " " + body;
        }
    }

    public Res get(String path, String... headerPairs) {
        return send("GET", path, null, null, headerPairs);
    }

    public Res delete(String path) {
        return send("DELETE", path, null, null);
    }

    public Res post(String path, String json, String... headerPairs) {
        return send("POST", path, json, json == null ? null : "application/json", headerPairs);
    }

    public Res put(String path, String json) {
        return send("PUT", path, json, "application/json");
    }

    public Res send(String method, String path, String body, String contentType, String... headerPairs) {
        return sendRaw(method, path, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body), contentType, headerPairs);
    }

    /** multipart/form-data POST with one file part. */
    public Res upload(String path, String field, String fileName, String fileContentType, byte[] content) {
        String boundary = "----talentmatch" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"" + field + "\"; filename=\"" + fileName + "\"\r\n"
                + "Content-Type: " + fileContentType + "\r\n\r\n";
        out.writeBytes(head.getBytes(StandardCharsets.UTF_8));
        out.writeBytes(content);
        out.writeBytes(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return postBytes(path, out.toByteArray(), "multipart/form-data; boundary=" + boundary);
    }

    /** POST of raw bytes with the given Content-Type. */
    public Res postBytes(String path, byte[] body, String contentType) {
        return sendRaw("POST", path, HttpRequest.BodyPublishers.ofByteArray(body), contentType);
    }

    /** GET returning the raw bytes (for file downloads). */
    public HttpResponse<byte[]> getBytes(String path) {
        try {
            return client.send(HttpRequest.newBuilder(URI.create(base + path)).timeout(Duration.ofSeconds(60)).GET()
                    .build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (IOException e) {
            throw new IllegalStateException("GET " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private Res sendRaw(String method, String path, HttpRequest.BodyPublisher publisher, String contentType,
                     String... headerPairs) {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(60))
                .method(method, publisher);
        if (contentType != null) {
            b.header("Content-Type", contentType);
        }
        b.header("Accept", "application/json");
        for (int i = 0; i + 1 < headerPairs.length; i += 2) {
            b.header(headerPairs[i], headerPairs[i + 1]);
        }
        try {
            HttpResponse<String> r = client.send(b.build(), HttpResponse.BodyHandlers.ofString());
            JsonNode json = null;
            String text = r.body();
            if (text != null && !text.isBlank()) {
                try {
                    json = mapper.readTree(text);
                } catch (IOException notJson) {
                    json = null;
                }
            }
            return new Res(r.statusCode(), r.headers(), text, json);
        } catch (IOException e) {
            throw new IllegalStateException(method + " " + path + " failed", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }
}
