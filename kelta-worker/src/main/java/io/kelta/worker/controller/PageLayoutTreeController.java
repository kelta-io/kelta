package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.PageLayoutTreeService;
import io.kelta.worker.service.PageLayoutTreeService.ApplyResult;
import io.kelta.worker.service.PageLayoutTreeService.LayoutNotFoundException;
import io.kelta.worker.service.PageLayoutTreeService.TreeError;
import io.kelta.worker.service.PageLayoutTreeService.TreeValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Whole-layout read/write: one call applies a page layout's sections, field placements,
 * related lists and header config, addressed by names instead of ids.
 *
 * <ul>
 *   <li>{@code GET  /api/page-layouts/{layoutId}/tree} — the layout as a name-addressed document</li>
 *   <li>{@code PUT  /api/page-layouts/{layoutId}/tree} — apply that document to an existing layout</li>
 *   <li>{@code PUT  /api/collections/{collectionName}/layouts/{layoutName}/tree} — apply it to the
 *       named layout, creating the layout row if it does not exist yet</li>
 * </ul>
 *
 * <p>Both prefixes ride existing gateway static routes ({@code /api/page-layouts/**} and
 * {@code /api/collections/**} in {@code RouteConfigService.STATIC_ROUTES}), which carry only the
 * blanket {@code API_ACCESS} check — so authoring rights are enforced here, in-controller, on
 * {@code CUSTOMIZE_APPLICATION}.
 *
 * <p>The mappings spell out every segment deliberately: {@code DynamicCollectionRouter} maps
 * all-variable GETs three and four segments deep under {@code /api}, and a catch-all or
 * less-specific pattern would lose {@code /tree} to a record read (see {@code concerns.md} →
 * Fragile Areas).
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api")
public class PageLayoutTreeController {

    /** Metadata authoring permission — a layout tree rewrites tenant UI configuration. */
    private static final String PERMISSION = "CUSTOMIZE_APPLICATION";

    private final PageLayoutTreeService treeService;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;

    public PageLayoutTreeController(PageLayoutTreeService treeService,
                                    CerbosPermissionResolver permissionResolver,
                                    BootstrapRepository bootstrapRepository) {
        this.treeService = treeService;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
    }

    @GetMapping("/page-layouts/{layoutId}/tree")
    public ResponseEntity<?> getTree(@PathVariable("layoutId") String layoutId,
                                     HttpServletRequest request) {
        requirePermission(request);
        try {
            return ResponseEntity.ok(treeService.readTree(layoutId));
        } catch (LayoutNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @PutMapping("/page-layouts/{layoutId}/tree")
    @Transactional
    public ResponseEntity<?> putTree(@PathVariable("layoutId") String layoutId,
                                     @RequestBody(required = false) Map<String, Object> body,
                                     HttpServletRequest request) {
        requirePermission(request);
        try {
            return ResponseEntity.ok(response(treeService.applyTree(layoutId, body)));
        } catch (TreeValidationException e) {
            return badRequest(e.errors());
        } catch (LayoutNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    @PutMapping("/collections/{collectionName}/layouts/{layoutName}/tree")
    @Transactional
    public ResponseEntity<?> putTreeByName(@PathVariable("collectionName") String collectionName,
                                           @PathVariable("layoutName") String layoutName,
                                           @RequestBody(required = false) Map<String, Object> body,
                                           HttpServletRequest request) {
        requirePermission(request);
        try {
            return ResponseEntity.ok(
                    response(treeService.applyTreeByName(collectionName, layoutName, body)));
        } catch (TreeValidationException e) {
            return badRequest(e.errors());
        } catch (LayoutNotFoundException e) {
            return notFound(e.getMessage());
        }
    }

    // --- helpers ------------------------------------------------------------

    private static Map<String, Object> response(ApplyResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("layoutId", result.layoutId());
        body.put("collection", result.collection());
        body.put("name", result.name());
        body.put("created", result.counts().created());
        body.put("updated", result.counts().updated());
        body.put("deleted", result.counts().deleted());
        body.put("unchanged", result.counts().unchanged());
        return body;
    }

    private static ResponseEntity<?> badRequest(List<TreeError> errors) {
        List<Map<String, Object>> payload = errors.stream()
                .map(error -> {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("status", "400");
                    entry.put("code", "VALIDATION_FAILED");
                    entry.put("title", "Validation Error");
                    entry.put("detail", error.detail());
                    entry.put("source", Map.of("pointer", error.pointer()));
                    return entry;
                })
                .toList();
        return ResponseEntity.badRequest().body(Map.of("errors", payload));
    }

    private static ResponseEntity<?> notFound(String detail) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("status", "404");
        error.put("code", "NOT_FOUND");
        error.put("title", "Not Found");
        error.put("detail", detail);
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("errors", List.of(error)));
    }

    private void requirePermission(HttpServletRequest request) {
        if (TenantContext.get() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No tenant context");
        }
        String profileId = permissionResolver.getProfileId(request);
        if (profileId == null || profileId.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "No identity");
        }
        boolean granted = bootstrapRepository.findProfileSystemPermissions(profileId).stream()
                .anyMatch(p -> PERMISSION.equals(p.get("permission_name"))
                        && Boolean.TRUE.equals(p.get("granted")));
        if (!granted) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, PERMISSION + " permission required");
        }
    }
}
