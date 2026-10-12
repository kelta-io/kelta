package io.kelta.worker.listener;

import io.kelta.crypto.EncryptionService;
import io.kelta.runtime.credential.CredentialType;
import io.kelta.runtime.credential.CredentialTypeRegistry;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Encrypts credential secret fields before they are persisted to the
 * {@code credential} table.
 *
 * <p>The UI sends a flat plaintext record (e.g.,
 * {@code {type:"api_key", name:"...", headerName:"X-API-Key", value:"..."}}).
 * This hook:
 * <ol>
 *   <li>Looks up the {@link CredentialType} from the {@code type} field.</li>
 *   <li>Validates the plaintext input.</li>
 *   <li>Splits the input into <em>secret</em> fields (per
 *       {@link CredentialType#getSecretFields()}) and <em>metadata</em> fields
 *       (per {@link CredentialType#getMetadataFields()}).</li>
 *   <li>Serializes the secret fields, encrypts the JSON via AES-256-GCM, and
 *       writes the result into {@code dataEnc}.</li>
 *   <li>Writes the metadata map into the plaintext {@code metadata} JSONB.</li>
 *   <li>Removes the original plaintext fields so they never reach storage.</li>
 * </ol>
 *
 * <p>On re-saves where the UI returns a record without secret material (e.g.,
 * the user only edited the description), {@code dataEnc} is left untouched and
 * {@code metadata} is recomputed from whatever metadata fields are present.
 */
@Component
@ConditionalOnProperty(name = "kelta.encryption.key")
public class CredentialEncryptionHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(CredentialEncryptionHook.class);
    private static final String COLLECTION = "credentials";
    private static final String DATA_ENC_FIELD = "dataEnc";
    private static final String METADATA_FIELD = "metadata";
    private static final String TYPE_FIELD = "type";

    private final EncryptionService encryptionService;
    private final CredentialTypeRegistry typeRegistry;
    private final ObjectMapper objectMapper;

    public CredentialEncryptionHook(EncryptionService encryptionService,
                                    CredentialTypeRegistry typeRegistry,
                                    ObjectMapper objectMapper) {
        this.encryptionService = encryptionService;
        this.typeRegistry = typeRegistry;
        this.objectMapper = objectMapper;
    }

    @Override public String getCollectionName() { return COLLECTION; }
    @Override public int getOrder() { return -100; }   // before the audit hook

    @Override
    public BeforeSaveResult beforeCreate(Map<String, Object> record, String tenantId) {
        return process(record, /*previous*/ null);
    }

    @Override
    public BeforeSaveResult beforeUpdate(String id, Map<String, Object> record,
                                          Map<String, Object> previous, String tenantId) {
        return process(record, previous);
    }

    private BeforeSaveResult process(Map<String, Object> record, Map<String, Object> previous) {
        String typeKey = stringFromRecord(record, TYPE_FIELD);
        if (typeKey == null && previous != null) {
            typeKey = stringFromRecord(previous, TYPE_FIELD);
        }
        if (typeKey == null) {
            return BeforeSaveResult.error(TYPE_FIELD, "type is required");
        }
        CredentialType type = typeRegistry.find(typeKey).orElse(null);
        if (type == null) {
            return BeforeSaveResult.error(TYPE_FIELD, "Unknown credential type: " + typeKey);
        }

        Set<String> secretFields = type.getSecretFields();
        Set<String> metadataFields = type.getMetadataFields();

        boolean secretsPresent = secretFields.stream().anyMatch(record::containsKey);
        boolean metadataPresent = metadataFields.stream().anyMatch(record::containsKey);

        // Build a merged plaintext view (secret + metadata fields) for type-side validation
        ObjectNode plaintext = objectMapper.createObjectNode();
        for (String field : secretFields) {
            if (record.containsKey(field)) {
                plaintext.set(field, objectMapper.valueToTree(record.get(field)));
            }
        }
        for (String field : metadataFields) {
            if (record.containsKey(field)) {
                plaintext.set(field, objectMapper.valueToTree(record.get(field)));
            }
        }

        if (secretsPresent || (previous == null && (record.containsKey(DATA_ENC_FIELD) == false))) {
            // On create or any update that touches secret fields, validate fully.
            List<String> errors = type.validate(plaintext);
            if (!errors.isEmpty()) {
                return BeforeSaveResult.error(TYPE_FIELD,
                    "Invalid credential input: " + String.join("; ", errors));
            }
        }

        Map<String, Object> updates = new HashMap<>();

        if (secretsPresent) {
            ObjectNode secretBlob = objectMapper.createObjectNode();
            for (String field : secretFields) {
                if (record.containsKey(field)) {
                    secretBlob.set(field, objectMapper.valueToTree(record.get(field)));
                }
            }
            String json;
            try {
                json = objectMapper.writeValueAsString(secretBlob);
            } catch (Exception e) {
                return BeforeSaveResult.error(TYPE_FIELD,
                    "Failed to serialize credential payload: " + e.getMessage());
            }
            updates.put(DATA_ENC_FIELD, encryptionService.encrypt(json));
            log.debug("Encrypted credential secret blob ({} fields)", secretFields.size());
        } else if (previous == null) {
            // Create with no secret fields supplied — the type accepts that
            // (e.g., custom type with empty data or auth_code which gets
            // tokens later) — but we still need a non-null data_enc for the
            // NOT NULL constraint. Encrypt an empty JSON object.
            try {
                updates.put(DATA_ENC_FIELD,
                    encryptionService.encrypt(objectMapper.writeValueAsString(
                        objectMapper.createObjectNode())));
            } catch (Exception e) {
                return BeforeSaveResult.error(TYPE_FIELD,
                    "Failed to seed credential payload: " + e.getMessage());
            }
        }

        if (metadataPresent || previous == null) {
            Map<String, Object> metadata = new LinkedHashMap<>();
            for (String field : metadataFields) {
                if (record.containsKey(field)) {
                    metadata.put(field, record.get(field));
                }
            }
            updates.put(METADATA_FIELD, metadata);
        }

        // Strip plaintext input fields so they never reach storage.
        for (String field : secretFields) {
            record.remove(field);
        }
        for (String field : metadataFields) {
            record.remove(field);
        }

        return updates.isEmpty()
            ? BeforeSaveResult.ok()
            : BeforeSaveResult.withFieldUpdates(updates);
    }

    private static String stringFromRecord(Map<String, Object> record, String field) {
        Object v = record == null ? null : record.get(field);
        if (v == null) return null;
        String s = v.toString();
        return s.isBlank() ? null : s;
    }
}
