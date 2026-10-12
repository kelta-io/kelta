package io.kelta.gateway.authz;

import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.RuntimeHintsRegistrar;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;
import org.springframework.lang.Nullable;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Worker endpoints of the child-resource shape ({@code /api/{parent}/{id}/{literal}[/...]}) that a
 * dedicated controller serves instead of {@code DynamicCollectionRouter}'s generic child handlers,
 * loaded from {@value #RESOURCE}.
 *
 * <p>Spring ranks a literal segment above the router's {@code {childName}} variable, so for these
 * paths the trailing segment names an operation, not a child collection — even when a tenant has
 * a collection of that name — and {@link RouteAuthorizationFilter} keeps the parent route's own
 * decision. The file is kept in step with the worker's controllers by the worker's
 * {@code DedicatedSubResourcesDriftTest}.
 *
 * <p>Format: one {@code METHOD /api/...} per line ({@code *} = any method); a {@code {x}} segment
 * matches exactly one path segment; {@code #} starts a comment. The gateway is a native image, so
 * the resource is registered by {@link Hints}; a missing or malformed file fails startup rather
 * than silently leaving the list empty.
 */
final class DedicatedSubResources {

    static final String RESOURCE = "authz/dedicated-sub-resources.txt";

    private static final String ANY_METHOD = "*";

    /** One line: the method ({@code *} for any) and the segments after {@code /api}, {@code null} = variable. */
    private record Entry(String method, List<String> segments) {

        boolean matches(String requestMethod, List<String> requestSegments) {
            if (!ANY_METHOD.equals(method) && !method.equals(requestMethod)) {
                return false;
            }
            if (segments.size() != requestSegments.size()) {
                return false;
            }
            for (int i = 0; i < segments.size(); i++) {
                String literal = segments.get(i);
                if (literal != null && !literal.equals(requestSegments.get(i))) {
                    return false;
                }
            }
            return true;
        }
    }

    private final List<Entry> entries;

    private DedicatedSubResources(List<Entry> entries) {
        this.entries = List.copyOf(entries);
    }

    /** The list shipped in the gateway ({@value #RESOURCE}). */
    static DedicatedSubResources loadDefault() {
        ClassPathResource resource = new ClassPathResource(RESOURCE, DedicatedSubResources.class.getClassLoader());
        try (InputStream in = resource.getInputStream()) {
            String content = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            return parse(Arrays.asList(content.split("\\R")));
        } catch (IOException e) {
            throw new UncheckedIOException("Cannot read " + RESOURCE, e);
        }
    }

    /** Parses the file's lines; a malformed line is an {@link IllegalStateException} naming it. */
    static DedicatedSubResources parse(List<String> lines) {
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            entries.add(parseLine(line, i + 1));
        }
        return new DedicatedSubResources(entries);
    }

    private static Entry parseLine(String line, int lineNumber) {
        String[] parts = line.split("\\s+");
        if (parts.length != 2) {
            throw malformed(line, lineNumber, "expected 'METHOD /api/...'");
        }
        String method = parts[0];
        if (!ANY_METHOD.equals(method) && Arrays.stream(HttpMethod.values()).noneMatch(m -> m.name().equals(method))) {
            throw malformed(line, lineNumber, "unknown method");
        }
        String path = parts[1];
        if (!path.startsWith("/api/")) {
            throw malformed(line, lineNumber, "path must start with /api/");
        }
        String[] raw = path.substring("/api/".length()).split("/", -1);
        if (raw.length != 3 && raw.length != 4) {
            throw malformed(line, lineNumber, "expected three or four segments after /api");
        }
        List<String> segments = new ArrayList<>(raw.length);
        for (String segment : raw) {
            segments.add(isVariable(segment) ? null : segment);
        }
        boolean shape = segments.get(0) != null && segments.get(1) == null && segments.get(2) != null;
        for (String segment : raw) {
            if (segment.isEmpty() || (!isVariable(segment) && (segment.contains("{") || segment.contains("*")))) {
                shape = false;
            }
        }
        if (!shape) {
            throw malformed(line, lineNumber, "expected /api/<literal>/{var}/<literal>[/<literal|{var}>]");
        }
        return new Entry(method, segments);
    }

    private static boolean isVariable(String segment) {
        return segment.length() > 2 && segment.startsWith("{") && segment.endsWith("}")
                && segment.indexOf('{', 1) < 0;
    }

    private static IllegalStateException malformed(String line, int lineNumber, String reason) {
        return new IllegalStateException(RESOURCE + " line " + lineNumber + " (" + line + "): " + reason);
    }

    /**
     * Whether {@code method} on {@code requestPath} is a dedicated endpoint. Segments are compared
     * the way the worker matches them ({@link PathContainer.PathSegment#valueToMatch()}).
     */
    boolean matches(@Nullable HttpMethod method, PathContainer requestPath) {
        if (method == null) {
            return false;
        }
        List<String> segments = new ArrayList<>();
        for (PathContainer.Element element : requestPath.elements()) {
            if (element instanceof PathContainer.PathSegment segment) {
                segments.add(segment.valueToMatch());
            }
        }
        if (segments.isEmpty() || !"api".equals(segments.get(0))) {
            return false;
        }
        List<String> afterApi = segments.subList(1, segments.size());
        for (Entry entry : entries) {
            if (entry.matches(method.name(), afterApi)) {
                return true;
            }
        }
        return false;
    }

    int size() {
        return entries.size();
    }

    /** Ships {@value #RESOURCE} in the native image. */
    static final class Hints implements RuntimeHintsRegistrar {

        @Override
        public void registerHints(RuntimeHints hints, @Nullable ClassLoader classLoader) {
            hints.resources().registerPattern(RESOURCE);
        }
    }
}
