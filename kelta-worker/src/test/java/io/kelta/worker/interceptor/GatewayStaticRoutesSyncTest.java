package io.kelta.worker.interceptor;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins {@link GatewayStaticRoutes} to the gateway's own route table. The worker authorizes a
 * child-resource request with the decision a direct request to the child gets at the gateway, so
 * a static route added, removed or re-ruled there must be mirrored here — this test reads the
 * gateway sources (the repository is one checkout) and fails on any difference.
 */
@DisplayName("GatewayStaticRoutes mirrors the gateway's static route rules")
class GatewayStaticRoutesSyncTest {

    private static final Path GATEWAY = Path.of("../kelta-gateway/src/main/java/io/kelta/gateway");

    private static String read(String relative) throws IOException {
        return Files.readString(GATEWAY.resolve(relative));
    }

    @Test
    @DisplayName("every /api static route segment, and no other")
    void segments() throws IOException {
        String source = read("service/RouteConfigService.java");
        Matcher m = Pattern.compile("\\{\"[^\"]+\",\\s*\"/api/([^/\"]+)/\\*\\*\",\\s*\"[^\"]+\"}").matcher(source);
        Set<String> gateway = new TreeSet<>();
        while (m.find()) {
            gateway.add(m.group(1));
        }

        // Auxiliary-service routes (kelta-ai) are registered by RouteInitializer.
        Matcher aux = Pattern.compile("new RouteDefinition\\(\\s*\"static-[a-z-]+\",\\s*\"/api/([^/\"]+)/\\*\\*\"")
                .matcher(read("config/RouteInitializer.java"));
        while (aux.find()) {
            gateway.add(aux.group(1));
        }

        assertThat(gateway).isNotEmpty().contains("ai");
        assertThat(new TreeSet<>(GatewayStaticRoutes.SEGMENTS)).isEqualTo(gateway);
    }

    @Test
    @DisplayName("the collection-checked static routes and their override permissions")
    void collectionChecks() throws IOException {
        String source = read("service/RouteConfigService.java");
        int start = source.indexOf("COLLECTION_CHECKED_STATIC_ROUTES = Map.ofEntries(");
        assertThat(start).isPositive();
        String table = source.substring(start, source.indexOf(";", start));
        Matcher m = Pattern.compile(
                        "Map\\.entry\\(\"([a-z-]+)\",\\s*new StaticCollectionCheck\\((true|false),\\s*\"([A-Z_]+)\"")
                .matcher(table);
        Map<String, GatewayStaticRoutes.CollectionCheck> gateway = new TreeMap<>();
        while (m.find()) {
            gateway.put(m.group(1), new GatewayStaticRoutes.CollectionCheck(Boolean.parseBoolean(m.group(2)), m.group(3)));
        }

        assertThat(new TreeMap<>(GatewayStaticRoutes.COLLECTION_CHECKS)).isEqualTo(gateway);
        // Exempt sub-paths are at least two segments below the route — never a direct path to
        // a record, which is all a child-resource request is mirrored as.
        Matcher exempt = Pattern.compile("Pattern\\.compile\\(\"(\\^/api/([a-z-]+)/[^\"]+)\"\\)").matcher(table);
        Set<String> exemptRoutes = new TreeSet<>();
        while (exempt.find()) {
            Pattern exemptPaths = Pattern.compile(exempt.group(1).replace("\\\\", "\\"));
            String route = exempt.group(2);
            exemptRoutes.add(route);
            assertThat(exemptPaths.matcher("/api/" + route).matches()).as(exempt.group(1)).isFalse();
            assertThat(exemptPaths.matcher("/api/" + route + "/r-1").matches()).as(exempt.group(1)).isFalse();
        }
        assertThat(exemptRoutes).containsExactly("collections", "dashboards", "flows", "reports", "scripts");
    }

    @Test
    @DisplayName("the per-route special cases in RouteAuthorizationFilter are the two mirrored ones")
    void specialCases() throws IOException {
        String source = read("authz/RouteAuthorizationFilter.java");
        Matcher m = Pattern.compile("\"(static-[a-z-]+)\"\\.equals\\(").matcher(source);
        Set<String> specialCased = new TreeSet<>();
        while (m.find()) {
            specialCased.add(m.group(1));
        }

        // static-ai (/api/ai/config writes need MANAGE_TENANTS) is routed to kelta-ai, never to
        // the worker, so no child-resource request here can be authorized by it.
        specialCased.remove("static-ai");
        assertThat(specialCased).containsExactly(
                "static-" + GatewayStaticRoutes.MODULES, "static-" + GatewayStaticRoutes.TENANTS);
        assertThat(source)
                .contains("\"" + GatewayStaticRoutes.TENANTS_PERMISSION + "\"")
                .contains("\"/api/modules/" + GatewayStaticRoutes.MODULE_SIGNING_KEYS_PREFIX + "\"")
                .contains("\"" + GatewayStaticRoutes.MODULE_SIGNING_KEYS_PERMISSION + "\"");
    }
}
