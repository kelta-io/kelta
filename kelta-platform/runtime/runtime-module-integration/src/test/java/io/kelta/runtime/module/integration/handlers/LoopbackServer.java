package io.kelta.runtime.module.integration.handlers;

import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * A real HTTP server on loopback for the outbound handler tests. Each test sets the canned
 * response; the last request is recorded.
 */
final class LoopbackServer implements AutoCloseable {

    private final HttpServer server;
    final AtomicInteger requests = new AtomicInteger();
    volatile String lastMethod;
    volatile String lastPath;
    volatile String lastBody;
    volatile Headers lastHeaders;

    volatile int status = 200;
    volatile String responseBody = "{\"ok\":true}";
    volatile String contentType = "application/json";
    volatile String location;
    volatile long delayMillis;

    LoopbackServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            lastMethod = exchange.getRequestMethod();
            lastPath = exchange.getRequestURI().toString();
            lastHeaders = exchange.getRequestHeaders();
            lastBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            if (location != null) {
                exchange.getResponseHeaders().add("Location", location);
            }
            byte[] bytes = responseBody == null ? new byte[0] : responseBody.getBytes(StandardCharsets.UTF_8);
            if (contentType != null) {
                exchange.getResponseHeaders().add("Content-Type", contentType);
            }
            boolean noBody = bytes.length == 0 || "HEAD".equals(lastMethod);
            exchange.sendResponseHeaders(status, noBody ? -1 : bytes.length);
            if (!noBody) {
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(bytes);
                }
            }
            exchange.close();
        });
        server.start();
    }

    /** {@code http://127.0.0.1:<port>} */
    String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
