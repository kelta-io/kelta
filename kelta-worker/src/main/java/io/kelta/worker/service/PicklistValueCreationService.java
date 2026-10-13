package io.kelta.worker.service;

import io.kelta.jsonapi.JsonApiError;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.router.jsonapi.JsonApiRequestBody;
import io.kelta.runtime.storage.UniqueConstraintViolationException;
import io.kelta.worker.repository.PicklistValueRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Creates a {@code picklist-values} row and, when it clashes with a value the same global
 * picklist already has, answers 409 naming the picklist and the value.
 *
 * <p>{@code uq_picklist_value} is a composite (source type, source id, value) constraint that the
 * generic unique-violation mapping cannot attribute to a field, so it reported the {@code id}
 * column and a UUID. Only a clash the database confirms as (picklist, value) is rewritten; any
 * other unique violation keeps the generic {@code UNIQUE_VIOLATION} response.
 */
@Service
public class PicklistValueCreationService {

    static final String GLOBAL = "GLOBAL";

    private final PicklistValueRepository valueRepository;

    public PicklistValueCreationService(PicklistValueRepository valueRepository) {
        this.valueRepository = valueRepository;
    }

    public ResponseEntity<Map<String, Object>> create(Map<String, Object> requestBody,
                                                      Supplier<ResponseEntity<Map<String, Object>>> createValue) {
        try {
            return createValue.get();
        } catch (UniqueConstraintViolationException e) {
            return duplicateGlobalValue(requestBody).orElseThrow(() -> e);
        }
    }

    private Optional<ResponseEntity<Map<String, Object>>> duplicateGlobalValue(Map<String, Object> requestBody) {
        Map<String, Object> attributes = JsonApiRequestBody.attributes(requestBody);
        if (!GLOBAL.equals(attributes.get("picklistSourceType"))
                || !(attributes.get("picklistSourceId") instanceof String picklistId)
                || !(attributes.get("value") instanceof String value)) {
            return Optional.empty();
        }
        String tenantId = TenantContext.get();
        if (!valueRepository.existsGlobalValue(tenantId, picklistId, value)) {
            return Optional.empty();
        }
        String picklistName = valueRepository.findGlobalPicklistName(tenantId, picklistId).orElse(picklistId);

        JsonApiError error = new JsonApiError("409", "UNIQUE_VIOLATION", "Conflict",
                String.format("Picklist '%s' already has the value '%s'", picklistName, value));
        String pointer = attributes == requestBody ? "/value" : "/data/attributes/value";
        error.setSource(Map.of("pointer", pointer));
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("requestId", UUID.randomUUID().toString().substring(0, 8));
        meta.put("picklist", picklistName);
        meta.put("value", value);
        error.setMeta(meta);
        return Optional.of(ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("errors", List.of(error.toMap()))));
    }
}
