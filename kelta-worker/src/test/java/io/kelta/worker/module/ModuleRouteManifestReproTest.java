package io.kelta.worker.module;

import io.kelta.runtime.module.ModuleManifestParser;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Resolves the billing module's real manifest through the real registry.
 *
 * <p>On prod, {@code POST /checkout-sessions} dispatched correctly while {@code GET /plans} and
 * {@code GET /me} returned 404, with all four routes reported registered. This pins the actual
 * manifest against the actual parser and registry so the difference is visible locally.
 */
@DisplayName("Billing manifest routes resolve")
class ModuleRouteManifestReproTest {

    @Test
    @DisplayName("every declared billing route resolves to its handler")
    void everyDeclaredRouteResolves() throws Exception {
        String json = Files.readString(
                Path.of("../kelta-modules/billing-jar/src/main/resources/kelta-module.json"));
        var manifest = new ModuleManifestParser(new ObjectMapper()).parse(json);

        var registry = new ModuleRouteRegistry();
        registry.register("t1", "kelta-billing", manifest.routes());

        assertThat(manifest.routes()).hasSize(4);
        assertThat(registry.resolve("t1", "kelta-billing", "GET", "/plans"))
                .contains("billing:list-plans");
        assertThat(registry.resolve("t1", "kelta-billing", "GET", "/me"))
                .contains("billing:me");
        assertThat(registry.resolve("t1", "kelta-billing", "POST", "/checkout-sessions"))
                .contains("billing:create-checkout-session");
        assertThat(registry.resolve("t1", "kelta-billing", "POST", "/portal-sessions"))
                .contains("billing:create-portal-session");
    }
}
