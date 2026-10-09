package com.talentmatch.support;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/**
 * Local JDK HTTP stub for integration tests (127.0.0.1, random port): routes by exact path, records
 * every request (with its headers), unrouted paths answer 404 text/plain. A route can be a fixed
 * {@link Reply} or a function of the request (conditional requests, sequences). Lives for the JVM
 * (one per IT context).
 */
public final class RouteStubServer {

    public record Captured(String method, URI uri, Map<String, List<String>> headers) {

        public Captured(String method, URI uri) {
            this(method, uri, Map.of());
        }

        public String path() {
            return uri.getPath();
        }

        /** First value of a request header (case-insensitive), or null. */
        public String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().get(0)).findFirst().orElse(null);
        }
    }

    /** A canned answer: status, content type, body, an optional delay, and extra response headers. */
    public record Reply(int status, String contentType, String body, Duration delay, Map<String, String> headers) {

        public Reply {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
        }

        public Reply(int status, String contentType, String body, Duration delay) {
            this(status, contentType, body, delay, Map.of());
        }

        public static Reply json(int status, String body) {
            return new Reply(status, "application/json", body, Duration.ZERO);
        }

        public static Reply text(int status, String body) {
            return new Reply(status, "text/plain", body, Duration.ZERO);
        }

        public Reply delayed(Duration d) {
            return new Reply(status, contentType, body, d, headers);
        }

        public Reply withHeader(String name, String value) {
            Map<String, String> h = new java.util.LinkedHashMap<>(headers);
            h.put(name, value);
            return new Reply(status, contentType, body, delay, h);
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "route-stub");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Function<Captured, Reply>> routes = new ConcurrentHashMap<>();
    private final List<Captured> requests = new CopyOnWriteArrayList<>();

    public RouteStubServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.setExecutor(executor);
        server.createContext("/", this::handle);
        server.start();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getRequestBody().readAllBytes();
            Captured captured = new Captured(exchange.getRequestMethod(), exchange.getRequestURI(),
                    Map.copyOf(exchange.getRequestHeaders()));
            requests.add(captured);
            Function<Captured, Reply> route = routes.get(exchange.getRequestURI().getPath());
            Reply reply = route == null ? Reply.text(404, "Not Found") : route.apply(captured);
            if (!reply.delay().isZero()) {
                try {
                    Thread.sleep(reply.delay().toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
            byte[] body = reply.body() == null ? new byte[0] : reply.body().getBytes(StandardCharsets.UTF_8);
            if (reply.contentType() != null) {
                exchange.getResponseHeaders().add("Content-Type", reply.contentType());
            }
            reply.headers().forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            if (reply.status() == 304 || reply.status() == 204) {
                body = new byte[0];
            }
            exchange.sendResponseHeaders(reply.status(), body.length == 0 ? -1 : body.length);
            if (body.length > 0) {
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(body);
                }
            }
        } catch (IOException e) {
            // client went away (timeouts): fine
        }
    }

    public String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    public void route(String path, Reply reply) {
        routes.put(path, request -> reply);
    }

    /** A route answering per request (conditional requests, sequences of replies). */
    public void route(String path, Function<Captured, Reply> handler) {
        routes.put(path, handler);
    }

    public void reset() {
        routes.clear();
        requests.clear();
    }

    public List<Captured> requests() {
        return List.copyOf(requests);
    }

    public List<Captured> requests(String path) {
        return requests.stream().filter(c -> c.path().equals(path)).toList();
    }
}
