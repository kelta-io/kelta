package io.kelta.gateway.authz;

import org.springframework.http.server.PathContainer;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A request path of the shape the worker's generic collection router serves as a child resource:
 * {@code /api/{parent}/{parentId}/{child}} (list, create) or
 * {@code /api/{parent}/{parentId}/{child}/{childId}} (get, update, delete) — exactly three or
 * four segments after {@code /api}, the depths of {@code DynamicCollectionRouter}'s
 * {@code {parentName}/{parentId}/{childName}[/{childId}]} mappings. Segments are read the way the
 * worker matches them ({@link PathContainer.PathSegment#valueToMatch()}: percent-decoded, path
 * parameters dropped, empty segments skipped), so the child named here is the collection the
 * worker would resolve.
 *
 * @param childName the child collection name, decoded
 * @param childId   the child record id as sent (raw), or null for the three-segment form
 */
record ChildResourcePath(String childName, String childId) {

    /** The child shape, if {@code requestPath} has it; empty for any other {@code /api} path. */
    static Optional<ChildResourcePath> parse(PathContainer requestPath) {
        List<PathContainer.PathSegment> segments = new ArrayList<>();
        for (PathContainer.Element element : requestPath.elements()) {
            if (element instanceof PathContainer.PathSegment segment) {
                segments.add(segment);
            }
        }
        if (segments.size() != 4 && segments.size() != 5) {
            return Optional.empty();
        }
        if (!"api".equals(segments.get(0).valueToMatch())) {
            return Optional.empty();
        }
        String childName = segments.get(3).valueToMatch();
        String childId = segments.size() == 5 ? segments.get(4).value() : null;
        return Optional.of(new ChildResourcePath(childName, childId));
    }

    /**
     * The direct request the child operation corresponds to — {@code /api/{child}} or
     * {@code /api/{child}/{childId}} — so it can be authorized exactly as that request would be.
     */
    String directPath() {
        return childId == null ? "/api/" + childName : "/api/" + childName + "/" + childId;
    }

    /**
     * False when the decoded child name cannot name a collection route on its own (empty, or an
     * encoded separator that would make {@link #directPath()} address something else).
     */
    boolean isAddressable() {
        return !childName.isEmpty() && childName.indexOf('/') < 0;
    }
}
