package io.kelta.worker.controller;

import io.kelta.runtime.context.TenantContext;
import io.kelta.worker.repository.BootstrapRepository;
import io.kelta.worker.service.CerbosPermissionResolver;
import io.kelta.worker.service.MenuTreeService;
import io.kelta.worker.service.MenuTreeService.ApplyResult;
import io.kelta.worker.service.MenuTreeService.TreeError;
import io.kelta.worker.service.MenuTreeService.TreeValidationException;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.EmptyResultDataAccessException;
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
 * Whole-menu read/write: one call applies a UI menu ("app") and its items/groups,
 * addressed by name instead of id.
 *
 * <ul>
 *   <li>{@code GET /api/ui-menus/{name}/tree} — the menu as a label-addressed document</li>
 *   <li>{@code PUT /api/ui-menus/{name}/tree} — apply that document, creating the menu row
 *       when it does not exist yet</li>
 * </ul>
 *
 * <p>Rides the existing {@code /api/ui-menus/**} gateway static route, which carries only
 * the blanket {@code API_ACCESS} check — authoring rights are enforced here, in-controller,
 * on {@code CUSTOMIZE_APPLICATION}, matching {@code PageLayoutTreeController} and {@code
 * DashboardTreeController}.
 *
 * <p>{@code /{name}/tree} is a 3-segment PUT path; {@code DynamicCollectionRouter} maps PUT
 * only at 2 segments and 4, so this depth is otherwise unclaimed.
 *
 * @since 1.0.0
 */
@RestController
@RequestMapping("/api/ui-menus")
public class MenuTreeController {

    /** Metadata authoring permission — a menu tree rewrites tenant UI configuration. */
    private static final String PERMISSION = "CUSTOMIZE_APPLICATION";

    private final MenuTreeService treeService;
    private final CerbosPermissionResolver permissionResolver;
    private final BootstrapRepository bootstrapRepository;

    public MenuTreeController(MenuTreeService treeService,
                              CerbosPermissionResolver permissionResolver,
                              BootstrapRepository bootstrapRepository) {
        this.treeService = treeService;
        this.permissionResolver = permissionResolver;
        this.bootstrapRepository = bootstrapRepository;
    }

    @GetMapping("/{name}/tree")
    public ResponseEntity<?> getTree(@PathVariable("name") String name, HttpServletRequest request) {
        requirePermission(request);
        try {
            return ResponseEntity.ok(treeService.readTree(name));
        } catch (IllegalArgumentException | EmptyResultDataAccessException e) {
            return notFound("Menu '" + name + "' not found");
        }
    }

    @PutMapping("/{name}/tree")
    @Transactional
    public ResponseEntity<?> putTree(@PathVariable("name") String name,
                                     @RequestBody(required = false) Map<String, Object> body,
                                     HttpServletRequest request) {
        requirePermission(request);
        try {
            return ResponseEntity.ok(response(treeService.applyTree(name, body)));
        } catch (TreeValidationException e) {
            return badRequest(e.errors());
        }
    }

    // --- helpers ------------------------------------------------------------

    private static Map<String, Object> response(ApplyResult result) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("menuId", result.menuId());
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
