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

/**
 * Local JDK HTTP stub for integration tests (127.0.0.1, random port): routes by exact path, records
 * every request, unrouted paths answer 404 text/plain. Lives for the JVM (one per IT context).
 */
public final class RouteStubServer {

    public record Captured(String method, URI uri) {
        public String path() {
            return uri.getPath();
        }
    }

    /** A canned answer: status, content type, body, and an optional delay before answering. */
    public record Reply(int status, String contentType, String body, Duration delay) {

        public static Reply json(int status, String body) {
            return new Reply(status, "application/json", body, Duration.ZERO);
        }

        public static Reply text(int status, String body) {
            return new Reply(status, "text/plain", body, Duration.ZERO);
        }

        public Reply delayed(Duration d) {
            return new Reply(status, contentType, body, d);
        }
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "route-stub");
        t.setDaemon(true);
        return t;
    });
    private final Map<String, Reply> routes = new ConcurrentHashMap<>();
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
            requests.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI()));
            Reply reply = routes.getOrDefault(exchange.getRequestURI().getPath(), Reply.text(404, "Not Found"));
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
        routes.put(path, reply);
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
