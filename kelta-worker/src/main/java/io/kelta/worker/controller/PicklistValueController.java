package io.kelta.worker.controller;

import io.kelta.runtime.router.DynamicCollectionRouter;
import io.kelta.worker.service.PicklistValueCreationService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * {@code POST /api/picklist-values}: the generic collection create, with a duplicate value in a
 * global picklist answered as a 409 that names the picklist and the value
 * (see {@link PicklistValueCreationService}).
 *
 * <p>The literal path outranks {@code DynamicCollectionRouter}'s {@code /api/{collectionName}};
 * the create itself is delegated to the router so its validation, audit and cache-eviction rules
 * apply unchanged, and every other {@code picklist-values} operation still goes to the router.
 */
@RestController
@RequestMapping("/api/picklist-values")
public class PicklistValueController {

    static final String COLLECTION = "picklist-values";

    private final PicklistValueCreationService creationService;
    private final DynamicCollectionRouter dynamicCollectionRouter;

    public PicklistValueController(PicklistValueCreationService creationService,
                                   DynamicCollectionRouter dynamicCollectionRouter) {
        this.creationService = creationService;
        this.dynamicCollectionRouter = dynamicCollectionRouter;
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> create(@RequestBody Map<String, Object> requestBody,
                                                      HttpServletRequest request) {
        return creationService.create(requestBody,
                () -> dynamicCollectionRouter.create(COLLECTION, requestBody, request));
    }
}
