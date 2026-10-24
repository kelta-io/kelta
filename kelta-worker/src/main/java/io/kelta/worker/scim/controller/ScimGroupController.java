package io.kelta.worker.scim.controller;

import io.kelta.worker.scim.ScimConstants;
import io.kelta.worker.scim.model.ScimGroup;
import io.kelta.worker.scim.model.ScimListResponse;
import io.kelta.worker.scim.model.ScimPatchOp;
import io.kelta.worker.scim.service.ScimGroupService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/scim/v2/Groups")
public class ScimGroupController {

    private final ScimGroupService scimGroupService;
    /** {@code kelta.scim.public-url}; blank falls back to the request's own address. */
    private final String publicUrl;

    public ScimGroupController(ScimGroupService scimGroupService,
                               @Value("${kelta.scim.public-url:}") String publicUrl) {
        this.scimGroupService = scimGroupService;
        this.publicUrl = publicUrl == null ? "" : publicUrl.strip().replaceAll("/+$", "");
    }

    @GetMapping(produces = ScimConstants.CONTENT_TYPE_SCIM)
    public ResponseEntity<ScimListResponse<ScimGroup>> listGroups(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestParam(value = "filter", required = false) String filter,
            @RequestParam(value = "startIndex", defaultValue = "1") int startIndex,
            @RequestParam(value = "count", defaultValue = "100") int count,
            HttpServletRequest request) {
        count = Math.min(count, ScimConstants.MAX_PAGE_SIZE);
        String baseUrl = getBaseUrl(request);
        return ResponseEntity.ok(scimGroupService.listGroups(tenantId, filter, startIndex, count, baseUrl));
    }

    @GetMapping(value = "/{id}", produces = ScimConstants.CONTENT_TYPE_SCIM)
    public ResponseEntity<ScimGroup> getGroup(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String id,
            HttpServletRequest request) {
        return ResponseEntity.ok(scimGroupService.getGroup(tenantId, id, getBaseUrl(request)));
    }

    @PostMapping(produces = ScimConstants.CONTENT_TYPE_SCIM, consumes = {ScimConstants.CONTENT_TYPE_SCIM, "application/json"})
    public ResponseEntity<ScimGroup> createGroup(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @RequestBody ScimGroup group,
            HttpServletRequest request) {
        ScimGroup created = scimGroupService.createGroup(tenantId, group, getBaseUrl(request));
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @PutMapping(value = "/{id}", produces = ScimConstants.CONTENT_TYPE_SCIM, consumes = {ScimConstants.CONTENT_TYPE_SCIM, "application/json"})
    public ResponseEntity<ScimGroup> replaceGroup(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String id,
            @RequestBody ScimGroup group,
            HttpServletRequest request) {
        return ResponseEntity.ok(scimGroupService.replaceGroup(tenantId, id, group, getBaseUrl(request)));
    }

    @PatchMapping(value = "/{id}", produces = ScimConstants.CONTENT_TYPE_SCIM, consumes = {ScimConstants.CONTENT_TYPE_SCIM, "application/json"})
    public ResponseEntity<ScimGroup> patchGroup(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String id,
            @RequestBody ScimPatchOp patchOp,
            HttpServletRequest request) {
        return ResponseEntity.ok(scimGroupService.patchGroup(tenantId, id, patchOp, getBaseUrl(request)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteGroup(
            @RequestHeader("X-Tenant-ID") String tenantId,
            @PathVariable String id) {
        scimGroupService.deleteGroup(tenantId, id);
        return ResponseEntity.noContent().build();
    }

    private String getBaseUrl(HttpServletRequest request) {
        if (!publicUrl.isEmpty()) {
            return publicUrl;
        }
        // Local dev only: X-Forwarded-Proto/-Host are never read (client-controlled, and the
        // gateway does not forward them).
        return request.getScheme() + "://" + request.getServerName() + ":" + request.getServerPort();
    }
}
