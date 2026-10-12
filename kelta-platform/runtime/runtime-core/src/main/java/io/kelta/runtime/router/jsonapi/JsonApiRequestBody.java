package io.kelta.runtime.router.jsonapi;

import java.util.Map;

/**
 * Reads the parts of a JSON:API write body ({@code POST}/{@code PUT}/{@code PATCH}) that
 * {@code DynamicCollectionRouter} turns into a storage data map.
 *
 * <p>A body without a {@code data} member is treated as a flat attribute map (the non-JSON:API
 * form the admin UI and some integrations send).
 */
public final class JsonApiRequestBody {

    private JsonApiRequestBody() {
    }

    /**
     * Extracts attributes from a JSON:API formatted request body.
     *
     * @param requestBody the JSON:API request body
     * @return the attributes map, or the whole body when it is not in JSON:API form
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> attributes(Map<String, Object> requestBody) {
        if (requestBody.containsKey("data")) {
            Map<String, Object> data = (Map<String, Object>) requestBody.get("data");
            if (data != null && data.containsKey("attributes")) {
                Object attributes = data.get("attributes");
                if (attributes instanceof Map) {
                    return (Map<String, Object>) attributes;
                }
            }
        }
        // If not JSON:API format, assume the whole body is attributes
        return requestBody;
    }

    /**
     * Extracts relationships from a JSON:API formatted request body. Converts relationship
     * references to field values using the relationship name directly as the field name
     * (e.g., "category" → field "category").
     *
     * @param requestBody the JSON:API request body
     * @return the relationships as field values, or empty map if not found
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> relationshipFieldValues(Map<String, Object> requestBody) {
        Map<String, Object> fieldValues = new java.util.HashMap<>();

        if (requestBody.containsKey("data")) {
            Map<String, Object> data = (Map<String, Object>) requestBody.get("data");
            if (data != null && data.containsKey("relationships")) {
                Object relationships = data.get("relationships");
                if (relationships instanceof Map) {
                    Map<String, Object> relationshipsMap = (Map<String, Object>) relationships;

                    for (Map.Entry<String, Object> entry : relationshipsMap.entrySet()) {
                        String relationshipName = entry.getKey();
                        Object relationshipValue = entry.getValue();

                        if (relationshipValue instanceof Map) {
                            Map<String, Object> relationship = (Map<String, Object>) relationshipValue;
                            if (relationship.containsKey("data")) {
                                Object relationshipData = relationship.get("data");
                                if (relationshipData instanceof Map) {
                                    Map<String, Object> relData = (Map<String, Object>) relationshipData;
                                    if (relData.containsKey("id")) {
                                        // Use relationship name directly as field name
                                        fieldValues.put(relationshipName, relData.get("id"));
                                    }
                                } else if (relationshipData == null) {
                                    // Null relationship — clear the field
                                    fieldValues.put(relationshipName, null);
                                }
                            }
                        }
                    }
                }
            }
        }

        return fieldValues;
    }

    /**
     * Returns the raw JSON:API {@code relationships} block from a request body, preserving each
     * entry's {@code {data: {type, id}}} structure. Used to echo the caller's relationships back
     * into create/update responses even when the collection's field metadata doesn't surface them
     * automatically.
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> rawRelationships(Map<String, Object> requestBody) {
        if (requestBody == null) {
            return Map.of();
        }
        Object dataObj = requestBody.get("data");
        if (!(dataObj instanceof Map<?, ?> data)) {
            return Map.of();
        }
        Object rels = ((Map<String, Object>) data).get("relationships");
        if (!(rels instanceof Map<?, ?> relsMap) || relsMap.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> out = new java.util.HashMap<>();
        for (Map.Entry<?, ?> e : relsMap.entrySet()) {
            if (e.getKey() != null && e.getValue() != null) {
                out.put(e.getKey().toString(), e.getValue());
            }
        }
        return out;
    }
}
