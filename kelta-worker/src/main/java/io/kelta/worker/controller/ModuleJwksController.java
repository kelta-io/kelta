package io.kelta.worker.controller;

import io.kelta.worker.module.ModuleAssertionSigner;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;

/**
 * Serves the public keys external modules verify platform request assertions with
 * ({@code specs/module-platform/9-external-modules.md} §3.2):
 * {@code GET /api/modules/.well-known/jwks.json}, where {@code /api/modules} is the assertions'
 * {@code iss}.
 *
 * <p><b>Unauthenticated by design</b> — the gateway lists {@code /api/modules/.well-known} in its
 * {@code unauthenticated-paths}, and a module fetches this with no credential. It returns public
 * key material only and no tenant data, so it is the same for every caller and needs no tenant.
 *
 * <p>Mapped as a literal path, never {@code /**}: {@code DynamicCollectionRouter}'s all-variable
 * GET mappings under {@code /api} would otherwise win and answer 404 as a record read
 * ({@code ModuleJwksRouteMappingTest}). Not conditional on module runtime support, so a module
 * verifying an assertion never meets a 404 for a reason it cannot see.
 */
@RestController
@RequestMapping("/api/modules/.well-known")
public class ModuleJwksController {

    /**
     * How long a module may cache the document. Short, because rotation is "publish the new key
     * first": a module that has not seen it refetches on an unknown {@code kid} anyway.
     */
    static final Duration MAX_AGE = Duration.ofMinutes(5);

    private final ModuleAssertionSigner signer;

    public ModuleJwksController(ModuleAssertionSigner signer) {
        this.signer = signer;
    }

    @GetMapping(value = "/jwks.json", produces = "application/jwk-set+json")
    public ResponseEntity<Map<String, Object>> jwks() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType("application/jwk-set+json"))
                .cacheControl(CacheControl.maxAge(MAX_AGE).cachePublic())
                .body(signer.jwks());
    }
}
