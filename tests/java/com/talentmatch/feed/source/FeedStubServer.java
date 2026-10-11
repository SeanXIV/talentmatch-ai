package com.talentmatch.feed.source;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local JDK HTTP stub (127.0.0.1, random port) for the source layer: records requests, scriptable handler. */
final class FeedStubServer implements AutoCloseable {

    record Captured(String method, URI uri, Map<String, List<String>> headers) {
        String header(String name) {
            return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name))
                    .map(e -> e.getValue().get(0)).findFirst().orElse(null);
        }

        boolean hasHeader(String name) {
            return headers.keySet().stream().anyMatch(k -> k.equalsIgnoreCase(name));
        }
    }

    @FunctionalInterface
    interface Handler {
        void handle(HttpExchange exchange) throws IOException, InterruptedException;
    }

    private final HttpServer server;
    private final ExecutorService executor = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "feed-stub");
        t.setDaemon(true);
        return t;
    });
    private final List<Captured> requests = new CopyOnWriteArrayList<>();
    private volatile Handler handler = json(200, "{}");

    FeedStubServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                requests.add(new Captured(exchange.getRequestMethod(), exchange.getRequestURI(),
                        Map.copyOf(exchange.getRequestHeaders())));
                handler.handle(exchange);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } catch (IOException e) {
                // client went away (timeouts, cap): fine
            } finally {
                exchange.close();
            }
        });
        server.start();
    }

    URI baseUrl() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    URI uri(String pathAndQuery) {
        return URI.create(baseUrl() + pathAndQuery);
    }

    void handler(Handler handler) {
        this.handler = handler;
    }

    List<Captured> requests() {
        return requests;
    }

    Captured last() {
        return requests.get(requests.size() - 1);
    }

    static Handler json(int status, String body) {
        return bytes(status, "application/json", body.getBytes(StandardCharsets.UTF_8), Map.of());
    }

    static Handler bytes(int status, String contentType, byte[] body, Map<String, String> headers) {
        return exchange -> {
            if (contentType != null) {
                exchange.getResponseHeaders().add("Content-Type", contentType);
            }
            headers.forEach((k, v) -> exchange.getResponseHeaders().add(k, v));
            if (status == 304 || status == 204 || body == null || body.length == 0) {
                exchange.sendResponseHeaders(status, -1);
                return;
            }
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(body);
            }
        };
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
