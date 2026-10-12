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

        assertThat(gateway).isNotEmpty();
        assertThat(new TreeSet<>(GatewayStaticRoutes.SEGMENTS)).isEqualTo(gateway);
    }

    @Test
    @DisplayName("the collection-checked static routes and their override permissions")
    void collectionChecks() throws IOException {
        String source = read("service/RouteConfigService.java");
        int start = source.indexOf("COLLECTION_CHECKED_STATIC_ROUTES = Map.of(");
        assertThat(start).isPositive();
        String table = source.substring(start, source.indexOf(");", start));
        Matcher m = Pattern.compile("\"([a-z-]+)\",\\s*new StaticCollectionCheck\\((true|false),\\s*\"([A-Z_]+)\"")
                .matcher(table);
        Map<String, GatewayStaticRoutes.CollectionCheck> gateway = new TreeMap<>();
        while (m.find()) {
            gateway.put(m.group(1), new GatewayStaticRoutes.CollectionCheck(Boolean.parseBoolean(m.group(2)), m.group(3)));
        }

        assertThat(new TreeMap<>(GatewayStaticRoutes.COLLECTION_CHECKS)).isEqualTo(gateway);
        // The only exempt sub-paths are three segments below /api/collections — never a direct path.
        assertThat(table).contains("^/api/collections/[^/]+/(import|duplicates|merge)(/.*)?$");
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

        assertThat(specialCased).containsExactly(
                "static-" + GatewayStaticRoutes.MODULES, "static-" + GatewayStaticRoutes.TENANTS);
        assertThat(source)
                .contains("\"" + GatewayStaticRoutes.TENANTS_PERMISSION + "\"")
                .contains("\"/api/modules/" + GatewayStaticRoutes.MODULE_SIGNING_KEYS_PREFIX + "\"")
                .contains("\"" + GatewayStaticRoutes.MODULE_SIGNING_KEYS_PERMISSION + "\"");
    }
}
