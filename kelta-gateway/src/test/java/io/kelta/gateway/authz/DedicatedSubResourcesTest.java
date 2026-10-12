package io.kelta.gateway.authz;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.aot.hint.RuntimeHints;
import org.springframework.aot.hint.predicate.RuntimeHintsPredicates;
import org.springframework.context.annotation.ImportRuntimeHints;
import org.springframework.http.HttpMethod;
import org.springframework.http.server.PathContainer;

import java.net.URI;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("DedicatedSubResources")
class DedicatedSubResourcesTest {

    private static PathContainer path(String raw) {
        return PathContainer.parsePath(URI.create(raw).getRawPath());
    }

    @Test
    @DisplayName("The shipped list loads and is not empty")
    void shippedListLoads() {
        assertThat(DedicatedSubResources.loadDefault().size()).isGreaterThan(0);
    }

    @ParameterizedTest(name = "{0} {1}")
    @CsvSource({
            "GET, /api/page-layouts/l1/tree",
            "PUT, /api/page-layouts/l1/tree",
            "GET, /api/dashboards/sales/tree",
            "PUT, /api/dashboards/sales/tree",
            "POST, /api/dashboards/d1/data",
            "POST, /api/dashboards/d1/validate",
            "POST, /api/collections/accounts/duplicates",
            "POST, /api/collections/accounts/merge",
            "POST, /api/attachments/a1/finalize",
            "GET, /api/api-specs/s1/operations/op1",
            "DELETE, /api/connected-apps/app1/tokens/t1"
    })
    @DisplayName("The shipped list covers the known dedicated endpoints")
    void shippedListCoversKnownEndpoints(String method, String requestPath) {
        assertThat(DedicatedSubResources.loadDefault().matches(HttpMethod.valueOf(method), path(requestPath)))
                .isTrue();
    }

    @Test
    @DisplayName("A variable matches one segment, a literal only itself, and the method must match")
    void matching() {
        DedicatedSubResources list = DedicatedSubResources.parse(List.of(
                "# comment", "", "GET /api/things/{id}/tree", "POST /api/things/{id}/items/{itemId}"));

        assertThat(list.size()).isEqualTo(2);
        assertThat(list.matches(HttpMethod.GET, path("/api/things/t1/tree"))).isTrue();
        assertThat(list.matches(HttpMethod.PUT, path("/api/things/t1/tree"))).isFalse();
        assertThat(list.matches(HttpMethod.GET, path("/api/things/t1/trees"))).isFalse();
        assertThat(list.matches(HttpMethod.GET, path("/api/other/t1/tree"))).isFalse();
        assertThat(list.matches(HttpMethod.GET, path("/api/things/t1/tree/x"))).isFalse();
        assertThat(list.matches(HttpMethod.POST, path("/api/things/t1/items/i1"))).isTrue();
        assertThat(list.matches(HttpMethod.POST, path("/api/things/t1/items"))).isFalse();
        assertThat(list.matches(null, path("/api/things/t1/tree"))).isFalse();
    }

    @Test
    @DisplayName("Segments are compared decoded, the way the worker matches them")
    void decodedSegments() {
        DedicatedSubResources list = DedicatedSubResources.parse(List.of("GET /api/things/{id}/tree"));

        assertThat(list.matches(HttpMethod.GET, path("/api/things/t1/tr%65e"))).isTrue();
    }

    @Test
    @DisplayName("'*' matches any method")
    void anyMethod() {
        DedicatedSubResources list = DedicatedSubResources.parse(List.of("* /api/things/{id}/tree"));

        assertThat(list.matches(HttpMethod.DELETE, path("/api/things/t1/tree"))).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "GET",
            "GET /api/things/{id}/tree extra",
            "FETCH /api/things/{id}/tree",
            "GET /things/{id}/tree",
            "GET /api/things/{id}",
            "GET /api/things/{id}/a/b/c",
            "GET /api/{p}/{id}/tree",
            "GET /api/things/literal/tree",
            "GET /api/things/{id}/{child}",
            "GET /api/things/{id}/tr*",
            "GET /api/things/{id}//x"
    })
    @DisplayName("A malformed line fails loading, naming the line")
    void malformedLineFails(String line) {
        assertThatThrownBy(() -> DedicatedSubResources.parse(List.of("# header", line)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("line 2");
    }

    @Test
    @DisplayName("The resource is registered for the native image")
    void resourceIsRegisteredForNativeImage() {
        RuntimeHints hints = new RuntimeHints();
        new DedicatedSubResources.Hints().registerHints(hints, getClass().getClassLoader());

        assertThat(RuntimeHintsPredicates.resource().forResource(DedicatedSubResources.RESOURCE)).accepts(hints);
    }

    @Test
    @DisplayName("The filter bean imports the native hints")
    void filterImportsHints() {
        ImportRuntimeHints imported = RouteAuthorizationFilter.class.getAnnotation(ImportRuntimeHints.class);

        assertThat(imported).isNotNull();
        assertThat(imported.value()).contains(DedicatedSubResources.Hints.class);
    }
}
