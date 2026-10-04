package io.kelta.gateway.route;

import org.springframework.http.HttpMethod;

import java.util.regex.Pattern;

/**
 * Declares that a {@code static-} route fronts a real system collection served by the generic
 * collection router, so the gateway must run the collection-level Cerbos check for it instead of
 * stopping at {@code API_ACCESS}. The check runs against the system collection's own route at the
 * same path (see {@link RouteRegistry#findCollectionRouteBehind(RouteDefinition)}).
 *
 * @param includeReads             true to check {@code read} on GET as well as writes; false to
 *                                 leave reads at {@code API_ACCESS} (metadata every page needs)
 * @param writeOverridePermission  system permission that admits a write without a collection
 *                                 grant (mirrors the worker's last-line guard), or null for none
 * @param exemptPaths              request paths under the route that are not this collection's
 *                                 CRUD and stay {@code API_ACCESS}-only, or null for none
 */
public record StaticCollectionCheck(boolean includeReads, String writeOverridePermission, Pattern exemptPaths) {

    public StaticCollectionCheck(boolean includeReads, String writeOverridePermission) {
        this(includeReads, writeOverridePermission, null);
    }

    public boolean appliesTo(HttpMethod method, String path) {
        if (exemptPaths != null && exemptPaths.matcher(path).matches()) {
            return false;
        }
        return includeReads || isWrite(method);
    }

    public static boolean isWrite(HttpMethod method) {
        return method == HttpMethod.POST || method == HttpMethod.PUT
                || method == HttpMethod.PATCH || method == HttpMethod.DELETE;
    }
}
