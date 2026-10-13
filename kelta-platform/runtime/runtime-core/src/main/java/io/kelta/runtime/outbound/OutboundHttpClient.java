package io.kelta.runtime.outbound;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Fetches URLs that come from tenant or administrator configuration, with every target checked
 * by an {@link OutboundUrlGuard}.
 *
 * <ul>
 *   <li><b>Every hop is validated.</b> Automatic redirects are off. When {@link Limits#maxRedirects()}
 *       is above zero, a {@code GET}/{@code HEAD} redirect is followed by hand, and its
 *       {@code Location} goes through the guard again before it is requested; a hop to another
 *       origin drops credential headers. With {@code maxRedirects == 0} a redirect response is
 *       returned to the caller like any other status. Other methods never follow redirects.</li>
 *   <li><b>One deadline</b> ({@link Limits#deadline()}) covers every hop, headers and body
 *       together, so a slow server cannot hold the caller longer; connects are bounded by
 *       {@link Limits#connectTimeout()}.</li>
 *   <li><b>Bounded body.</b> At most {@link Limits#maxResponseBytes()} are kept; when the server
 *       sends more, the transfer is cancelled and the {@link Response} is marked
 *       {@link Response#truncated() truncated}. Callers that need a complete document treat that
 *       as a failure.</li>
 * </ul>
 *
 * <p><b>Resolved address.</b> The guard resolves the host through {@link java.net.InetAddress}
 * immediately before each request; the connection uses the same JVM address cache.
 *
 * <p>Plain Java, thread-safe, and meant to be long-lived: it owns one {@link HttpClient}.
 */
public final class OutboundHttpClient {

    /** Request headers the JDK client sets itself and refuses from callers; they are skipped. */
    private static final Set<String> RESTRICTED_HEADERS =
            Set.of("connection", "content-length", "expect", "host", "upgrade");

    /** Headers that carry credentials and are not forwarded to a different origin. */
    private static final Set<String> CREDENTIAL_HEADERS =
            Set.of("authorization", "cookie", "proxy-authorization");

    private final OutboundUrlGuard guard;
    private final Limits limits;
    private final HttpClient httpClient;

    public OutboundHttpClient(OutboundUrlGuard guard, Limits limits) {
        this.guard = Objects.requireNonNull(guard, "guard");
        this.limits = Objects.requireNonNull(limits, "limits");
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(limits.connectTimeout())
                .followRedirects(HttpClient.Redirect.NEVER)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
    }

    public Limits limits() {
        return limits;
    }

    /** {@code GET url} with the given headers. */
    public Response get(String url, Map<String, String> headers) {
        return send(new Request("GET", url, headers, null));
    }

    /**
     * Sends the request.
     *
     * @throws OutboundUrlGuard.RejectedUrlException when the URL, or a redirect target, may not
     *         be fetched — nothing is sent to it
     * @throws OutboundRequestException when the request fails, times out or redirects too often
     */
    public Response send(Request request) {
        long deadline = System.nanoTime() + limits.deadline().toNanos();
        String method = request.method().toUpperCase(Locale.ROOT);
        boolean followable = limits.maxRedirects() > 0 && (method.equals("GET") || method.equals("HEAD"));
        Map<String, String> headers = new LinkedHashMap<>(request.headers());

        URI target = guard.check(request.url());
        for (int redirects = 0; ; redirects++) {
            Response response = exchange(method, target, headers, request.body(), deadline);
            if (!followable || !isRedirect(response.statusCode())) {
                return response;
            }
            Optional<String> location = response.header("Location");
            if (location.isEmpty() || location.get().isBlank()) {
                return response;
            }
            if (redirects >= limits.maxRedirects()) {
                throw new OutboundRequestException(OutboundRequestException.Kind.TOO_MANY_REDIRECTS,
                        "more than " + limits.maxRedirects() + " redirects");
            }
            URI next;
            try {
                next = target.resolve(location.get().trim());
            } catch (IllegalArgumentException e) {
                throw new OutboundRequestException(OutboundRequestException.Kind.FAILED,
                        "the redirect location is not a valid URL");
            }
            URI checked = guard.check(next.toString());
            if (!sameOrigin(target, checked)) {
                headers.keySet().removeIf(name -> CREDENTIAL_HEADERS.contains(name.toLowerCase(Locale.ROOT)));
            }
            target = checked;
        }
    }

    private Response exchange(String method, URI uri, Map<String, String> headers, byte[] body,
                              long deadline) {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0) {
            throw timedOut();
        }
        HttpRequest httpRequest;
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofNanos(remaining))
                    .method(method, body == null
                            ? HttpRequest.BodyPublishers.noBody()
                            : HttpRequest.BodyPublishers.ofByteArray(body));
            for (Map.Entry<String, String> header : headers.entrySet()) {
                if (header.getKey() == null || header.getValue() == null
                        || RESTRICTED_HEADERS.contains(header.getKey().toLowerCase(Locale.ROOT))) {
                    continue;
                }
                builder.header(header.getKey(), header.getValue());
            }
            httpRequest = builder.build();
        } catch (IllegalArgumentException e) {
            throw new OutboundRequestException(OutboundRequestException.Kind.FAILED,
                    "invalid request: " + e.getMessage());
        }

        CompletableFuture<HttpResponse<CappedBody>> pending = httpClient.sendAsync(httpRequest,
                info -> new CappedBodySubscriber(limits.maxResponseBytes()));
        HttpResponse<CappedBody> response;
        try {
            response = pending.get(remaining, TimeUnit.NANOSECONDS);
        } catch (TimeoutException e) {
            pending.cancel(true);
            throw timedOut();
        } catch (InterruptedException e) {
            pending.cancel(true);
            Thread.currentThread().interrupt();
            throw new OutboundRequestException(OutboundRequestException.Kind.FAILED, "interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            if (cause instanceof HttpTimeoutException) {
                throw timedOut();
            }
            throw new OutboundRequestException(OutboundRequestException.Kind.FAILED,
                    cause.getClass().getSimpleName()
                            + (cause.getMessage() != null ? ": " + cause.getMessage() : ""));
        }
        CappedBody captured = response.body();
        return new Response(response.statusCode(), uri, response.headers().map(),
                captured.bytes(), captured.truncated());
    }

    private OutboundRequestException timedOut() {
        return new OutboundRequestException(OutboundRequestException.Kind.TIMEOUT,
                "timed out after " + limits.deadline().toMillis() + " ms");
    }

    private static boolean isRedirect(int status) {
        return status == 301 || status == 302 || status == 303 || status == 307 || status == 308;
    }

    private static boolean sameOrigin(URI a, URI b) {
        return a.getScheme().equalsIgnoreCase(b.getScheme())
                && a.getHost().equalsIgnoreCase(b.getHost())
                && effectivePort(a) == effectivePort(b);
    }

    private static int effectivePort(URI uri) {
        if (uri.getPort() >= 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(uri.getScheme()) ? 443 : 80;
    }

    /** Timeouts, the response cap and the redirect limit for one client. */
    public record Limits(Duration connectTimeout, Duration deadline, int maxResponseBytes, int maxRedirects) {
        public Limits {
            Objects.requireNonNull(connectTimeout, "connectTimeout");
            Objects.requireNonNull(deadline, "deadline");
            if (connectTimeout.isNegative() || connectTimeout.isZero()
                    || deadline.isNegative() || deadline.isZero()) {
                throw new IllegalArgumentException("timeouts must be positive");
            }
            if (maxResponseBytes <= 0) {
                throw new IllegalArgumentException("maxResponseBytes must be positive");
            }
            if (maxRedirects < 0) {
                throw new IllegalArgumentException("maxRedirects must not be negative");
            }
        }
    }

    /** An outbound request. {@code body} may be {@code null}. */
    public record Request(String method, String url, Map<String, String> headers, byte[] body) {
        public Request {
            Objects.requireNonNull(method, "method");
            headers = headers == null ? Map.of() : headers;
        }
    }

    /**
     * A response. {@code uri} is the URL that produced it (the last hop); {@code body} holds at
     * most the configured number of bytes, and {@code truncated} is true when the server sent
     * more.
     */
    public record Response(int statusCode, URI uri, Map<String, List<String>> headers, byte[] body,
                           boolean truncated) {

        public Optional<String> header(String name) {
            for (Map.Entry<String, List<String>> entry : headers.entrySet()) {
                if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)
                        && !entry.getValue().isEmpty()) {
                    return Optional.of(entry.getValue().getFirst());
                }
            }
            return Optional.empty();
        }

        public boolean isSuccess() {
            return statusCode / 100 == 2;
        }

        /** The body decoded with the {@code Content-Type} charset, or UTF-8. */
        public String bodyAsString() {
            return new String(body, charset());
        }

        private Charset charset() {
            String contentType = header("Content-Type").orElse("");
            for (String part : contentType.split(";")) {
                String trimmed = part.trim();
                if (trimmed.regionMatches(true, 0, "charset=", 0, 8)) {
                    String name = trimmed.substring(8).replace("\"", "").trim();
                    try {
                        return Charset.forName(name);
                    } catch (RuntimeException e) {
                        return StandardCharsets.UTF_8;
                    }
                }
            }
            return StandardCharsets.UTF_8;
        }
    }

    /** The request was sent (or attempted) and failed; the message is safe to log and show. */
    public static final class OutboundRequestException extends RuntimeException {

        /** What went wrong. */
        public enum Kind { TIMEOUT, TOO_MANY_REDIRECTS, FAILED }

        private final Kind kind;

        public OutboundRequestException(Kind kind, String message) {
            super(message, null, false, false);
            this.kind = kind;
        }

        public Kind kind() {
            return kind;
        }
    }

    private record CappedBody(byte[] bytes, boolean truncated) {}

    /** Keeps at most {@code cap} bytes, then cancels the transfer and reports truncation. */
    private static final class CappedBodySubscriber implements HttpResponse.BodySubscriber<CappedBody> {

        private final int cap;
        private final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        private final CompletableFuture<CappedBody> result = new CompletableFuture<>();
        private Flow.Subscription subscription;

        CappedBodySubscriber(int cap) {
            this.cap = cap;
        }

        @Override
        public CompletionStage<CappedBody> getBody() {
            return result;
        }

        @Override
        public void onSubscribe(Flow.Subscription subscription) {
            this.subscription = subscription;
            subscription.request(Long.MAX_VALUE);
        }

        @Override
        public void onNext(List<ByteBuffer> items) {
            if (result.isDone()) {
                return;
            }
            for (ByteBuffer item : items) {
                int room = cap - buffer.size();
                int available = item.remaining();
                if (available > room) {
                    byte[] part = new byte[room];
                    item.get(part);
                    buffer.write(part, 0, room);
                    result.complete(new CappedBody(buffer.toByteArray(), true));
                    subscription.cancel();
                    return;
                }
                byte[] chunk = new byte[available];
                item.get(chunk);
                buffer.write(chunk, 0, available);
            }
        }

        @Override
        public void onError(Throwable throwable) {
            result.completeExceptionally(throwable);
        }

        @Override
        public void onComplete() {
            result.complete(new CappedBody(buffer.toByteArray(), false));
        }
    }
}
