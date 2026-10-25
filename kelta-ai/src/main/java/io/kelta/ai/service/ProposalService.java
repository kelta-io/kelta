package io.kelta.ai.service;

import com.anthropic.models.messages.*;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import io.kelta.ai.model.AiProposal;
import io.kelta.ai.model.ChatMessage;
import io.kelta.ai.repository.ChatMessageRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Manages AI proposals - storing, retrieving, and applying them
 * via the worker API.
 */
@Service
public class ProposalService {

    private static final Logger log = LoggerFactory.getLogger(ProposalService.class);

    private final ChatMessageRepository messageRepository;
    private final WorkerApiClient workerApiClient;
    private final AnthropicService anthropicService;
    private final ObjectMapper objectMapper;

    public ProposalService(ChatMessageRepository messageRepository, WorkerApiClient workerApiClient,
                            AnthropicService anthropicService, ObjectMapper objectMapper) {
        this.messageRepository = messageRepository;
        this.workerApiClient = workerApiClient;
        this.anthropicService = anthropicService;
        this.objectMapper = objectMapper;
    }

    public AiProposal createProposal(String type, Map<String, Object> data) {
        return AiProposal.pending(type, data);
    }

    /**
     * Reconstruct an AiProposal from a persisted message. New messages store
     * the proposal info inside a {@code tool_use} content block (with
     * {@code proposalId}); legacy messages store it in the {@code proposalJson}
     * column.
     */
    @SuppressWarnings("unchecked")
    private AiProposal extractProposal(ChatMessage message, UUID proposalId) {
        if (message.contentBlocks() != null) {
            for (Map<String, Object> block : message.contentBlocks()) {
                if (!"tool_use".equals(block.get("type"))) continue;
                Object pid = block.get("proposalId");
                if (pid == null || !proposalId.toString().equals(String.valueOf(pid))) continue;
                String toolName = String.valueOf(block.get("name"));
                String type = proposalTypeFromToolName(toolName);
                Map<String, Object> data = block.get("input") instanceof Map<?, ?> m
                        ? new java.util.LinkedHashMap<>((Map<String, Object>) m)
                        : Map.of();
                return new AiProposal(proposalId, type, "pending", data, message.createdAt());
            }
        }

        if (message.legacyProposalJson() != null) {
            try {
                return objectMapper.readValue(message.legacyProposalJson(), AiProposal.class);
            } catch (JacksonException e) {
                throw new RuntimeException("Failed to deserialize proposal", e);
            }
        }
        throw new IllegalArgumentException("Proposal not found in message: " + proposalId);
    }

    private static String proposalTypeFromToolName(String toolName) {
        return switch (toolName) {
            case "propose_collection" -> "collection";
            case "propose_layout" -> "layout";
            case "propose_add_fields" -> "add_fields";
            case "propose_update_field" -> "update_field";
            case "propose_remove_field" -> "remove_field";
            case "propose_picklist" -> "picklist";
            case "propose_ui_page" -> "ui_page";
            default -> "unknown";
        };
    }

    public String serializeProposal(AiProposal proposal) {
        try {
            return objectMapper.writeValueAsString(proposal);
        } catch (JacksonException e) {
            log.error("Failed to serialize proposal: {}", e.getMessage());
            throw new RuntimeException("Failed to serialize proposal", e);
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> applyProposal(UUID proposalId, String tenantId, String userId) {
        log.info("Applying proposal {} for tenant {}", proposalId, tenantId);

        // Find the message containing this proposal
        ChatMessage message = messageRepository.findByProposalId(proposalId, tenantId)
                .orElseThrow(() -> new IllegalArgumentException("Proposal not found: " + proposalId));

        AiProposal proposal = extractProposal(message, proposalId);

        if (!"pending".equals(proposal.status())) {
            throw new IllegalStateException("Proposal has already been " + proposal.status());
        }

        return switch (proposal.type()) {
            case "collection" -> applyCollectionProposal(tenantId, userId, proposal.data());
            case "layout" -> applyLayoutProposal(tenantId, userId, proposal.data());
            case "add_fields" -> applyAddFieldsProposal(tenantId, userId, proposal.data());
            case "update_field" -> applyUpdateFieldProposal(tenantId, userId, proposal.data());
            case "remove_field" -> applyRemoveFieldProposal(tenantId, userId, proposal.data());
            case "picklist" -> applyPicklistProposal(tenantId, userId, proposal.data());
            case "ui_page" -> applyUiPageProposal(tenantId, userId, proposal.data());
            default -> throw new IllegalArgumentException("Unknown proposal type: " + proposal.type());
        };
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyAddFieldsProposal(String tenantId, String userId, Map<String, Object> data) {
        String collectionName = String.valueOf(data.get("collectionName"));
        Map<String, Object> collection = workerApiClient.getCollectionByName(tenantId, collectionName)
                .orElseThrow(() -> new IllegalArgumentException("Collection not found: " + collectionName));
        String collectionId = String.valueOf(collection.get("id"));

        List<Map<String, Object>> fields = (List<Map<String, Object>>) data.getOrDefault("fields", List.of());
        if (fields.isEmpty()) {
            throw new IllegalArgumentException("No fields specified to add");
        }

        List<String> warnings = new java.util.ArrayList<>();
        warnings.addAll(createPicklistsForFields(tenantId, userId, fields));
        warnings.addAll(workerApiClient.createFields(tenantId, userId, collectionId, fields));

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("collectionId", collectionId);
        result.put("collectionName", collectionName);
        result.put("fieldsAdded", fields.size() - warnings.size());
        if (!warnings.isEmpty()) result.put("_warnings", warnings);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyUpdateFieldProposal(String tenantId, String userId, Map<String, Object> data) {
        String collectionName = String.valueOf(data.get("collectionName"));
        String fieldName = String.valueOf(data.get("fieldName"));
        Map<String, Object> changes = (Map<String, Object>) data.getOrDefault("changes", Map.of());

        if (changes.containsKey("type")) {
            throw new IllegalArgumentException("Type changes are not supported via propose_update_field. " +
                    "Use propose_remove_field then propose_add_fields.");
        }

        String fieldId = findFieldId(tenantId, collectionName, fieldName);
        return workerApiClient.updateField(tenantId, userId, fieldId, changes);
    }

    private Map<String, Object> applyRemoveFieldProposal(String tenantId, String userId, Map<String, Object> data) {
        String collectionName = String.valueOf(data.get("collectionName"));
        String fieldName = String.valueOf(data.get("fieldName"));
        String fieldId = findFieldId(tenantId, collectionName, fieldName);

        workerApiClient.deleteField(tenantId, userId, fieldId);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("status", "deleted");
        result.put("collectionName", collectionName);
        result.put("fieldName", fieldName);
        return result;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyPicklistProposal(String tenantId, String userId, Map<String, Object> data) {
        Map<String, Object> picklistData = new java.util.LinkedHashMap<>();
        picklistData.put("name", data.get("name"));
        picklistData.put("description", data.get("description"));
        picklistData.put("restricted", data.getOrDefault("restricted", false));
        picklistData.put("sorted", false);
        picklistData.entrySet().removeIf(e -> e.getValue() == null);

        Map<String, Object> created = workerApiClient.createGlobalPicklist(tenantId, userId, picklistData);
        Object plData = created.get("data");
        String picklistId = (plData instanceof Map)
                ? String.valueOf(((Map<String, Object>) plData).get("id"))
                : null;

        if (picklistId == null) {
            throw new IllegalStateException("Could not extract picklist ID from worker response");
        }

        List<Map<String, Object>> values = (List<Map<String, Object>>) data.getOrDefault("values", List.of());
        List<Map<String, Object>> formatted = new java.util.ArrayList<>();
        for (int i = 0; i < values.size(); i++) {
            Map<String, Object> raw = values.get(i);
            Map<String, Object> v = new java.util.LinkedHashMap<>();
            v.put("value", raw.get("value"));
            v.put("label", raw.getOrDefault("label", raw.get("value")));
            v.put("sortOrder", raw.getOrDefault("sortOrder", i));
            v.put("isActive", true);
            v.put("isDefault", i == 0);
            formatted.add(v);
        }
        workerApiClient.createPicklistValues(tenantId, userId, picklistId, formatted);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("picklistId", picklistId);
        result.put("name", data.get("name"));
        result.put("valuesCreated", formatted.size());
        return result;
    }

    @SuppressWarnings("unchecked")
    private String findFieldId(String tenantId, String collectionName, String fieldName) {
        Map<String, Object> collection = workerApiClient.getCollectionByName(tenantId, collectionName)
                .orElseThrow(() -> new IllegalArgumentException("Collection not found: " + collectionName));
        String collectionId = String.valueOf(collection.get("id"));

        for (Map<String, Object> field : workerApiClient.listFields(tenantId, collectionId)) {
            Map<String, Object> attrs = (Map<String, Object>) field.getOrDefault("attributes", field);
            if (fieldName.equals(attrs.get("name"))) {
                return String.valueOf(field.get("id"));
            }
        }
        throw new IllegalArgumentException("Field '" + fieldName + "' not found on collection " + collectionName);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> applyCollectionProposal(String tenantId, String userId, Map<String, Object> data) {
        List<String> warnings = new java.util.ArrayList<>();

        // Extract fields from the proposal data
        List<Map<String, Object>> fields = (List<Map<String, Object>>) data.get("fields");

        // Create the collection first (without fields)
        Map<String, Object> collectionData = new java.util.LinkedHashMap<>(data);
        collectionData.remove("fields");

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.putAll(workerApiClient.createCollection(tenantId, userId, collectionData));
        log.info("Collection created successfully");

        // Then create the fields if any
        if (fields != null && !fields.isEmpty()) {
            String collectionId = null;
            Object dataObj = result.get("data");
            if (dataObj instanceof Map) {
                Map<String, Object> resultData = (Map<String, Object>) dataObj;
                collectionId = resultData.get("id") != null ? String.valueOf(resultData.get("id")) : null;
            }
            log.info("Extracted collection ID: {} from response", collectionId);

            if (collectionId != null) {
                // Pre-create global picklists for PICKLIST/MULTI_PICKLIST fields
                List<String> picklistErrors = createPicklistsForFields(tenantId, userId, fields);
                warnings.addAll(picklistErrors);

                // Create fields
                List<String> fieldErrors = workerApiClient.createFields(tenantId, userId, collectionId, fields);
                warnings.addAll(fieldErrors);
                log.info("Field creation completed with {} warnings", fieldErrors.size());

                // Ask AI to generate an intelligent layout for the collection
                try {
                    generateAiLayout(tenantId, userId, collectionId,
                            (String) data.get("name"),
                            (String) data.getOrDefault("displayName", data.get("name")),
                            fields);
                } catch (Exception e) {
                    String msg = "Failed to create layout: " + e.getMessage();
                    log.error(msg);
                    warnings.add(msg);
                }

                // Add collection to the navigation menu
                try {
                    addToNavigationMenu(tenantId, userId,
                            (String) data.get("name"),
                            (String) data.getOrDefault("displayName", data.get("name")));
                } catch (Exception e) {
                    String msg = "Failed to add menu item: " + e.getMessage();
                    log.error(msg);
                    warnings.add(msg);
                }
            } else {
                warnings.add("Could not extract collection ID from response");
                log.error("Could not extract collection ID from response: {}", result);
            }
        }

        if (!warnings.isEmpty()) {
            result.put("_warnings", warnings);
        }

        return result;
    }

    /**
     * Ask Claude to generate an intelligent DETAIL layout for a collection,
     * then apply it via the worker API.
     */
    @SuppressWarnings("unchecked")
    private void generateAiLayout(String tenantId, String userId, String collectionId,
                                   String collectionName, String displayName,
                                   List<Map<String, Object>> proposedFields) {
        log.info("Asking AI to generate layout for collection {}", collectionName);

        // 1. Fetch the actual created fields (with IDs)
        List<Map<String, Object>> createdFields = workerApiClient.listFields(tenantId, collectionId);
        if (createdFields.isEmpty()) {
            log.warn("No fields found for collection {}, skipping layout generation", collectionId);
            return;
        }

        // 2. Build a field summary for the AI prompt
        StringBuilder fieldSummary = new StringBuilder();
        Map<String, String> fieldNameToId = new java.util.LinkedHashMap<>();
        for (Map<String, Object> field : createdFields) {
            Map<String, Object> attrs = (Map<String, Object>) field.getOrDefault("attributes", field);
            String name = String.valueOf(attrs.getOrDefault("name", ""));
            String type = String.valueOf(attrs.getOrDefault("type", ""));
            String fieldId = String.valueOf(field.get("id"));
            fieldNameToId.put(name, fieldId);
            fieldSummary.append("- ").append(name).append(" (").append(type).append(")\n");
        }

        // 3. Ask Claude to generate a layout
        String layoutPrompt = "Generate a DETAIL page layout for the '" + displayName + "' collection. " +
                "The collection has these fields:\n" + fieldSummary +
                "\nCreate a well-organized layout with multiple sections. " +
                "Group related fields logically (e.g., key info at top in 2 columns, " +
                "long content like descriptions/instructions in 1-column sections, " +
                "metadata/dates in a collapsible section at the bottom). " +
                "Use the propose_layout tool with fieldPlacements using the exact field names listed above.";

        try {
            MessageCreateParams params = anthropicService.buildRequest(tenantId,
                            "You are a UI layout designer. Generate a page layout using the propose_layout tool. " +
                            "Use sections with headings. Put key identifying fields at the top in a 2-column section. " +
                            "Put long text fields (RICH_TEXT, JSON) in their own 1-column section. " +
                            "Put date/boolean/status fields in a collapsible section at the bottom. " +
                            "Use styles: DEFAULT for main sections, COLLAPSIBLE for secondary sections.",
                            List.of(MessageParam.ofUser(layoutPrompt)))
                    .build();

            Message response = anthropicService.sendMessage(params);

            // 4. Extract the layout proposal from the tool use response
            for (ContentBlock block : response.content()) {
                block.toolUse().ifPresent(toolUse -> {
                    if ("propose_layout".equals(toolUse.name())) {
                        Map<String, Object> layoutInput = objectMapper.convertValue(toolUse._input(), Map.class);
                        log.info("AI generated layout with {} sections",
                                ((List<?>) layoutInput.getOrDefault("sections", List.of())).size());

                        // 5. Apply the AI-generated layout
                        applyGeneratedLayout(tenantId, userId, collectionId, displayName,
                                layoutInput, fieldNameToId);
                    }
                });
            }
        } catch (Exception e) {
            log.error("AI layout generation failed, falling back to simple layout: {}", e.getMessage());
            // Fallback: create a simple single-section layout
            createSimpleFallbackLayout(tenantId, userId, collectionId, displayName, createdFields);
        }
    }

    @SuppressWarnings("unchecked")
    private void applyGeneratedLayout(String tenantId, String userId, String collectionId,
                                       String displayName, Map<String, Object> layoutInput,
                                       Map<String, String> fieldNameToId) {
        // Create the page layout
        Map<String, Object> layoutData = new java.util.LinkedHashMap<>();
        layoutData.put("collectionId", collectionId);
        layoutData.put("name", layoutInput.getOrDefault("name", displayName + " Detail"));
        layoutData.put("layoutType", layoutInput.getOrDefault("layoutType", "DETAIL"));
        layoutData.put("isDefault", true);

        Map<String, Object> layoutResult = workerApiClient.createPageLayout(tenantId, userId, layoutData);
        String layoutId = extractId(layoutResult);
        if (layoutId == null) {
            log.error("Could not extract layout ID");
            return;
        }

        // Create sections and place fields
        List<Map<String, Object>> sections = (List<Map<String, Object>>) layoutInput.getOrDefault("sections", List.of());
        int sectionOrder = 0;
        for (Map<String, Object> section : sections) {
            String heading = String.valueOf(section.getOrDefault("heading", "Section " + (sectionOrder + 1)));
            int columns = section.containsKey("columns") ? ((Number) section.get("columns")).intValue() : 2;
            String style = String.valueOf(section.getOrDefault("style", "DEFAULT"));

            String sectionId = createSection(tenantId, userId, layoutId, heading, columns, sectionOrder++, style);
            if (sectionId == null) continue;

            List<Map<String, Object>> placements = (List<Map<String, Object>>) section.getOrDefault("fieldPlacements", List.of());
            int fieldOrder = 0;
            for (Map<String, Object> placement : placements) {
                String fieldName = String.valueOf(placement.get("fieldName"));
                String fieldId = fieldNameToId.get(fieldName);
                if (fieldId == null) {
                    log.warn("Field '{}' not found in collection, skipping placement", fieldName);
                    continue;
                }
                try {
                    Map<String, Object> fieldPlacement = new java.util.LinkedHashMap<>();
                    fieldPlacement.put("sectionId", sectionId);
                    fieldPlacement.put("fieldId", fieldId);
                    fieldPlacement.put("sortOrder", fieldOrder++);
                    workerApiClient.createLayoutField(tenantId, userId, fieldPlacement);
                } catch (Exception e) {
                    log.warn("Failed to place field '{}': {}", fieldName, e.getMessage());
                }
            }
        }
        log.info("Applied AI-generated layout with {} sections", sectionOrder);
    }

    private void createSimpleFallbackLayout(String tenantId, String userId, String collectionId,
                                             String displayName, List<Map<String, Object>> fields) {
        log.info("Creating simple fallback layout for collection {}", collectionId);
        Map<String, Object> layoutData = new java.util.LinkedHashMap<>();
        layoutData.put("collectionId", collectionId);
        layoutData.put("name", displayName + " Detail");
        layoutData.put("layoutType", "DETAIL");
        layoutData.put("isDefault", true);

        Map<String, Object> layoutResult = workerApiClient.createPageLayout(tenantId, userId, layoutData);
        String layoutId = extractId(layoutResult);
        if (layoutId == null) return;

        String sectionId = createSection(tenantId, userId, layoutId, "Details", 2, 0, "DEFAULT");
        if (sectionId != null) {
            placeFieldsInSection(tenantId, userId, sectionId, fields);
        }
    }

    @SuppressWarnings("unchecked")
    private String createSection(String tenantId, String userId, String layoutId,
                                  String heading, int columns, int sortOrder, String style) {
        Map<String, Object> sectionData = new java.util.LinkedHashMap<>();
        sectionData.put("layoutId", layoutId);
        sectionData.put("heading", heading);
        sectionData.put("columns", columns);
        sectionData.put("sortOrder", sortOrder);
        sectionData.put("style", style);

        Map<String, Object> result = workerApiClient.createLayoutSection(tenantId, userId, sectionData);
        return extractId(result);
    }

    private void placeFieldsInSection(String tenantId, String userId, String sectionId,
                                       List<Map<String, Object>> fields) {
        int sortOrder = 0;
        for (Map<String, Object> field : fields) {
            String fieldId = String.valueOf(field.get("id"));
            try {
                Map<String, Object> placement = new java.util.LinkedHashMap<>();
                placement.put("sectionId", sectionId);
                placement.put("fieldId", fieldId);
                placement.put("sortOrder", sortOrder++);
                workerApiClient.createLayoutField(tenantId, userId, placement);
            } catch (Exception e) {
                log.warn("Failed to place field {} in layout: {}", fieldId, e.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private String extractId(Map<String, Object> response) {
        Object dataObj = response.get("data");
        if (dataObj instanceof Map) {
            Object id = ((Map<String, Object>) dataObj).get("id");
            return id != null ? String.valueOf(id) : null;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private void addToNavigationMenu(String tenantId, String userId, String collectionName, String displayName) {
        log.info("Adding {} to navigation menu", collectionName);

        // Find existing menus
        List<Map<String, Object>> menus = workerApiClient.listMenus(tenantId);
        String menuId;

        if (menus.isEmpty()) {
            // Create a default menu
            Map<String, Object> menuData = new java.util.LinkedHashMap<>();
            menuData.put("name", "Main");
            menuData.put("description", "Main navigation menu");
            menuData.put("displayOrder", 0);
            Map<String, Object> menuResult = workerApiClient.createMenu(tenantId, userId, menuData);
            Object menuDataObj = menuResult.get("data");
            menuId = (menuDataObj instanceof Map) ? String.valueOf(((Map<String, Object>) menuDataObj).get("id")) : null;
            log.info("Created default menu with ID {}", menuId);
        } else {
            // Use the first existing menu
            Map<String, Object> firstMenu = menus.getFirst();
            menuId = String.valueOf(firstMenu.get("id"));
            log.info("Using existing menu {}", menuId);
        }

        if (menuId == null) {
            log.error("Could not determine menu ID");
            return;
        }

        // Create menu item
        Map<String, Object> itemData = new java.util.LinkedHashMap<>();
        itemData.put("menuId", menuId);
        itemData.put("label", displayName);
        itemData.put("path", "/resources/" + collectionName);
        itemData.put("icon", "database");
        itemData.put("displayOrder", menus.isEmpty() ? 0 : 100); // Add at end
        itemData.put("active", true);

        workerApiClient.createMenuItem(tenantId, userId, itemData);
        log.info("Added menu item '{}' -> /resources/{}", displayName, collectionName);
    }

    /**
     * For PICKLIST and MULTI_PICKLIST fields, create global picklists from
     * the enumValues array and set the fieldTypeConfig.globalPicklistId.
     */
    @SuppressWarnings("unchecked")
    private List<String> createPicklistsForFields(String tenantId, String userId, List<Map<String, Object>> fields) {
        List<String> errors = new java.util.ArrayList<>();
        for (Map<String, Object> field : fields) {
            String type = String.valueOf(field.get("type")).toUpperCase();
            if (!"PICKLIST".equals(type) && !"MULTI_PICKLIST".equals(type)) continue;

            List<String> enumValues = (List<String>) field.get("enumValues");
            if (enumValues == null || enumValues.isEmpty()) continue;

            String fieldName = String.valueOf(field.get("name"));
            try {
                // Create the global picklist
                Map<String, Object> picklistData = new java.util.LinkedHashMap<>();
                picklistData.put("name", fieldName);
                picklistData.put("sorted", false);
                picklistData.put("restricted", false);

                Map<String, Object> picklistResult = workerApiClient.createGlobalPicklist(tenantId, userId, picklistData);
                Object plData = picklistResult.get("data");
                String picklistId = (plData instanceof Map) ? String.valueOf(((Map<String, Object>) plData).get("id")) : null;

                if (picklistId == null) {
                    log.warn("Could not extract picklist ID for field '{}'", fieldName);
                    continue;
                }

                // Create the picklist values
                List<Map<String, Object>> values = new java.util.ArrayList<>();
                for (int i = 0; i < enumValues.size(); i++) {
                    String val = enumValues.get(i);
                    values.add(Map.of(
                            "value", val,
                            "label", val,
                            "isDefault", i == 0,
                            "isActive", true,
                            "sortOrder", i
                    ));
                }
                workerApiClient.createPicklistValues(tenantId, userId, picklistId, values);
                log.info("Created global picklist '{}' with {} values", fieldName, values.size());

                // Set the globalPicklistId on the field's fieldTypeConfig
                Map<String, Object> fieldTypeConfig = (Map<String, Object>) field.get("fieldTypeConfig");
                if (fieldTypeConfig == null) {
                    fieldTypeConfig = new java.util.LinkedHashMap<>();
                    field.put("fieldTypeConfig", fieldTypeConfig);
                }
                fieldTypeConfig.put("globalPicklistId", picklistId);

                // Remove enumValues since the picklist is now managed globally
                field.remove("enumValues");

            } catch (Exception e) {
                String msg = "Failed to create picklist for '" + fieldName + "': " + e.getMessage();
                log.error(msg);
                errors.add(msg);
            }
        }
        return errors;
    }

    private Map<String, Object> applyLayoutProposal(String tenantId, String userId, Map<String, Object> data) {
        return workerApiClient.createPageLayout(tenantId, userId, data);
    }

    // =========================================================================
    // UI page proposals (app-intelligence slice 2)
    // =========================================================================

    /**
     * Widget types the page-builder registry ships (kelta-ui `widgets/builtins`).
     * KEEP IN SYNC: adding a widget there means adding it here (and in
     * ProposeUiPageHandler's description) or the AI cannot propose it.
     */
    static final java.util.Set<String> ALLOWED_WIDGET_TYPES = java.util.Set.of(
            "heading", "text", "button", "image", "card", "container", "table", "form",
            "grid", "row", "column", "divider", "field-value", "list", "repeater",
            "chart", "tabs", "tab-panel", "nav", "icon", "link", "metric",
            "text-input", "number-input", "checkbox", "dropdown", "datepicker",
            "lookup", "multi-picklist", "rich-text");

    static final int MAX_UI_PAGE_NODES = 200;
    static final int MAX_UI_PAGE_DEPTH = 8;

    /**
     * Validate the proposed widget tree and create the ui-page as an UNPUBLISHED
     * draft — publishing stays a human act in the page builder. The tree persists
     * inside the {@code config} JSON column (the only column the worker keeps),
     * stamped {@code schemaVersion: 2} to match the builder's save path.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> applyUiPageProposal(String tenantId, String userId, Map<String, Object> data) {
        String name = String.valueOf(data.get("name"));
        if (name == null || name.isBlank() || "null".equals(name)) {
            throw new IllegalArgumentException("ui_page proposal requires a name");
        }

        List<Map<String, Object>> components =
                (List<Map<String, Object>>) data.getOrDefault("components", List.of());
        if (components.isEmpty()) {
            throw new IllegalArgumentException("ui_page proposal requires at least one component");
        }

        java.util.List<String> unknownTypes = new java.util.ArrayList<>();
        int nodeCount = validateTree(components, 1, 0, unknownTypes);
        if (!unknownTypes.isEmpty()) {
            throw new IllegalArgumentException("Unknown widget types: " + String.join(", ", unknownTypes));
        }

        Map<String, Object> config = new java.util.LinkedHashMap<>();
        config.put("components", components);
        if (data.get("variables") instanceof List<?> vars && !vars.isEmpty()) {
            config.put("variables", vars);
        }
        if (data.get("dataSources") instanceof List<?> sources && !sources.isEmpty()) {
            config.put("dataSources", sources);
        }
        config.put("schemaVersion", 2);

        Map<String, Object> attributes = new java.util.LinkedHashMap<>();
        attributes.put("name", name);
        if (data.get("title") != null) attributes.put("title", data.get("title"));
        if (data.get("description") != null) attributes.put("description", data.get("description"));
        attributes.put("published", false); // draft — never published by the AI
        attributes.put("active", true);
        attributes.put("config", config);

        Map<String, Object> created = workerApiClient.createUiPage(tenantId, userId, attributes);
        String pageId = extractId(created);

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("pageId", pageId);
        result.put("name", name);
        result.put("componentCount", nodeCount);
        result.put("published", false);
        return result;
    }

    /**
     * Walk the tree: enforce the widget-type allow-list, node + depth caps, and
     * assign missing node ids ({@code ai-<n>}). Returns the total node count.
     */
    @SuppressWarnings("unchecked")
    private int validateTree(List<Map<String, Object>> nodes, int depth, int countSoFar,
                              List<String> unknownTypes) {
        if (depth > MAX_UI_PAGE_DEPTH) {
            throw new IllegalArgumentException(
                    "ui_page tree exceeds the maximum depth of " + MAX_UI_PAGE_DEPTH);
        }
        int count = countSoFar;
        for (Map<String, Object> node : nodes) {
            count++;
            if (count > MAX_UI_PAGE_NODES) {
                throw new IllegalArgumentException(
                        "ui_page tree exceeds the maximum of " + MAX_UI_PAGE_NODES + " nodes");
            }
            String type = String.valueOf(node.get("type"));
            if (!ALLOWED_WIDGET_TYPES.contains(type)) {
                unknownTypes.add(type);
            }
            if (node.get("id") == null || String.valueOf(node.get("id")).isBlank()) {
                node.put("id", "ai-" + count);
            }
            if (node.get("props") == null) {
                node.put("props", new java.util.LinkedHashMap<String, Object>());
            }
            if (node.get("children") instanceof List<?> children && !children.isEmpty()) {
                count = validateTree((List<Map<String, Object>>) children, depth + 1, count, unknownTypes);
            }
        }
        return count;
    }
}
