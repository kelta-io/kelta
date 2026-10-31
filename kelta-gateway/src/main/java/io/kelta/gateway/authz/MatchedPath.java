package io.kelta.gateway.authz;

import org.springframework.http.server.PathContainer;

/**
 * A request path as the Spring MVC service behind the gateway matches it against its mappings:
 * each segment percent-decoded with its path parameters ({@code ;a=b}) dropped, empty segments
 * skipped ({@link PathContainer.PathSegment#valueToMatch()}, as {@link ChildResourcePath} reads
 * segments).
 *
 * <p>A rule that gates a path prefix must test this form, not the raw path: the gateway routes on
 * the raw path ({@code /api/ai/**} matches {@code /api/ai/%63onfig}) and the service decodes it
 * ({@code /api/ai/config}), so a prefix test on the raw path is passed by percent-encoding one
 * letter. Decoding can only make a prefix rule match more requests, never fewer.
 */
final class MatchedPath {

    private MatchedPath() {}

    /** The matched form of {@code rawPath}; the raw path itself when it cannot be decoded. */
    static String of(String rawPath) {
        if (rawPath == null || rawPath.isEmpty()) {
            return rawPath;
        }
        StringBuilder matched = new StringBuilder(rawPath.length());
        try {
            for (PathContainer.Element element : PathContainer.parsePath(rawPath).elements()) {
                if (element instanceof PathContainer.PathSegment segment && !segment.valueToMatch().isEmpty()) {
                    matched.append('/').append(segment.valueToMatch());
                }
            }
        } catch (IllegalArgumentException e) {
            // Malformed percent-encoding: the service refuses such a path as well.
            return rawPath;
        }
        return matched.isEmpty() ? "/" : matched.toString();
    }
}
