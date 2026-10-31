package com.gopi.fraud;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A local HTTP server with a canned reply, standing in for Ollama or Bedrock so tests need neither (JDK built-in,
 * no extra dependency). Every request is recorded; the reply can be slowed past a client's timeout.
 */
public final class StubHttpServer implements AutoCloseable {

    public record Request(String method, String path, String body) {
    }

    private final HttpServer server;
    private final ExecutorService executor;
    private final List<Request> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private volatile String body = "{}";
    private volatile Duration delay = Duration.ZERO;

    private StubHttpServer(HttpServer server, ExecutorService executor) {
        this.server = server;
        this.executor = executor;
    }

    public static StubHttpServer start() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            ExecutorService executor = Executors.newCachedThreadPool(runnable -> {
                Thread thread = new Thread(runnable, "stub-http");
                thread.setDaemon(true);
                return thread;
            });
            StubHttpServer stub = new StubHttpServer(server, executor);
            server.createContext("/", stub::handle);
            server.setExecutor(executor);
            server.start();
            return stub;
        } catch (IOException e) {
            throw new IllegalStateException("Could not start the stub server", e);
        }
    }

    public String url() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    /** Every later request gets this status and JSON body. */
    public StubHttpServer reply(int status, String body) {
        this.status = status;
        this.body = body;
        return this;
    }

    /** Every later reply waits this long first. */
    public StubHttpServer delay(Duration delay) {
        this.delay = delay;
        return this;
    }

    public List<Request> requests() {
        return List.copyOf(requests);
    }

    /** Back to an instant {@code 200 {}} with no recorded requests. */
    public void reset() {
        reply(200, "{}").delay(Duration.ZERO);
        requests.clear();
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            String requestBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().getPath(), requestBody));
            int replyStatus = status;
            byte[] reply = body.getBytes(StandardCharsets.UTF_8);
            Thread.sleep(delay.toMillis());
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(replyStatus, reply.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(reply);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        server.stop(0);
        executor.shutdownNow();
    }
}
