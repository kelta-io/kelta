package io.kelta.runtime.router;

import io.kelta.runtime.model.CollectionDefinition;
import jakarta.servlet.http.HttpServletRequest;

import java.util.Objects;

/**
 * Authorizes a request to one of {@link DynamicCollectionRouter}'s sub-resource handlers
 * ({@code /api/{parent}/{parentId}/{child}[/{childId}]}), which read and write the <em>child</em>
 * collection's records through a parent record.
 *
 * <p>The router calls it once per request, after the parent/child relationship has resolved and
 * before any record is read or written. Runtime-core has no authorization model of its own, so
 * the implementation lives in the host service (the worker evaluates it against Cerbos). With no
 * implementation registered the handlers behave as before.
 *
 * <p>Calls that do not come through the HTTP handlers (flows, hooks and services that use the
 * {@code QueryEngine} directly) never reach this interface.
 *
 * @since 1.0.0
 */
@FunctionalInterface
public interface SubResourceAuthorizer {

    /**
     * Decides whether the request may run.
     *
     * @param access  what the request does: the parent and child collections, the parent record,
     *                the child record (absent on list/create) and the operation on the child
     * @param request the HTTP request (identity headers)
     * @return the decision; never {@code null}
     */
    Decision authorize(Access access, HttpServletRequest request);

    /** The operation a sub-resource handler performs on the child collection. */
    enum Operation {
        /** {@code GET} list or get. */
        READ,
        /** {@code POST}. */
        CREATE,
        /** {@code PUT} / {@code PATCH}. */
        EDIT,
        /** {@code DELETE}. */
        DELETE
    }

    /**
     * One sub-resource request.
     *
     * @param parent    the parent collection
     * @param parentId  the parent record id from the URL
     * @param child     the child collection whose records the handler serves
     * @param childId   the child record id from the URL, or {@code null} on list and create
     * @param operation the operation on the child
     */
    record Access(CollectionDefinition parent, String parentId,
                  CollectionDefinition child, String childId, Operation operation) {

        public Access {
            Objects.requireNonNull(parent, "parent cannot be null");
            Objects.requireNonNull(child, "child cannot be null");
            Objects.requireNonNull(operation, "operation cannot be null");
        }
    }

    /** How the router answers. */
    enum Outcome {
        /** Run the handler. */
        ALLOW,
        /** 403 with the decision's detail, in the standard JSON:API error envelope. */
        FORBIDDEN,
        /** 404, exactly as the handler answers an absent record. */
        NOT_FOUND
    }

    /**
     * A decision.
     *
     * @param outcome the outcome
     * @param detail  the error detail for {@link Outcome#FORBIDDEN}; ignored otherwise
     */
    record Decision(Outcome outcome, String detail) {

        private static final Decision ALLOWED = new Decision(Outcome.ALLOW, null);
        private static final Decision ABSENT = new Decision(Outcome.NOT_FOUND, null);

        public Decision {
            Objects.requireNonNull(outcome, "outcome cannot be null");
        }

        public static Decision allow() {
            return ALLOWED;
        }

        public static Decision forbidden(String detail) {
            return new Decision(Outcome.FORBIDDEN, detail);
        }

        public static Decision notFound() {
            return ABSENT;
        }

        public boolean allowed() {
            return outcome == Outcome.ALLOW;
        }
    }
}
