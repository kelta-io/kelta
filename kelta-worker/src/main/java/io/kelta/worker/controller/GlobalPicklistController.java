package io.kelta.worker.controller;

import io.kelta.runtime.router.DynamicCollectionRouter;
import io.kelta.worker.service.GlobalPicklistDeletionService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code DELETE /api/global-picklists/{id}}: the generic collection delete, plus the picklist's
 * {@code picklist-values} rows in the same transaction (see {@link GlobalPicklistDeletionService}).
 *
 * <p>The literal path outranks {@code DynamicCollectionRouter}'s {@code /api/{collectionName}/{id}};
 * every other {@code global-picklists} operation still goes to the router, and the picklist delete
 * itself is delegated to it so its read-only, {@code If-Match} and cache-eviction rules apply
 * unchanged.
 */
@RestController
@RequestMapping("/api/global-picklists")
public class GlobalPicklistController {

    static final String COLLECTION = "global-picklists";

    private final GlobalPicklistDeletionService deletionService;
    private final DynamicCollectionRouter dynamicCollectionRouter;

    public GlobalPicklistController(GlobalPicklistDeletionService deletionService,
                                    DynamicCollectionRouter dynamicCollectionRouter) {
        this.deletionService = deletionService;
        this.dynamicCollectionRouter = dynamicCollectionRouter;
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable("id") String id, HttpServletRequest request) {
        return deletionService.delete(id, () -> dynamicCollectionRouter.delete(COLLECTION, id, request));
    }
}
