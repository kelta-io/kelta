package io.kelta.runtime.model;

/**
 * Which callers a collection's owner scoping ({@link CollectionDefinition#ownerField()})
 * applies to. Owner scoping narrows access to the caller's own rows; it never grants access.
 *
 * <ul>
 *   <li>{@link #NONE} — nobody is scoped (the default).</li>
 *   <li>{@link #PORTAL} — PORTAL (member) callers are scoped; INTERNAL staff are not.</li>
 *   <li>{@link #ALL} — every caller is scoped, except INTERNAL callers whose profile grants
 *       {@code VIEW_ALL_DATA} (reads) or {@code MODIFY_ALL_DATA} (writes).</li>
 * </ul>
 *
 * <p>The internal tier (no bound {@code CallerContext}: flows, NATS listeners, schedulers) is
 * never scoped.
 */
public enum OwnerScope {
    NONE, PORTAL, ALL;

    /** Parses a stored value case-insensitively; null, blank or unknown is {@link #NONE}. */
    public static OwnerScope parse(Object value) {
        if (value == null) {
            return NONE;
        }
        String text = value.toString().trim();
        for (OwnerScope scope : values()) {
            if (scope.name().equalsIgnoreCase(text)) {
                return scope;
            }
        }
        return NONE;
    }
}
