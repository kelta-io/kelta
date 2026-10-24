package io.kelta.runtime.module.core;

import io.kelta.runtime.flow.FlowEngine;
import io.kelta.runtime.module.core.handlers.*;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.module.KeltaModule;
import io.kelta.runtime.workflow.module.ModuleContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Kelta module that provides core action handlers for workflow execution.
 *
 * <p>This module registers the following action handlers:
 * <ul>
 *   <li><b>FIELD_UPDATE</b> — Updates fields on the triggering record</li>
 *   <li><b>CREATE_RECORD</b> — Creates a new record in a target collection</li>
 *   <li><b>UPDATE_RECORD</b> — Updates a record in any collection (cross-collection)</li>
 *   <li><b>DELETE_RECORD</b> — Deletes a record from a collection</li>
 *   <li><b>CREATE_TASK</b> — Retired: fails, pointing at CREATE_RECORD</li>
 *   <li><b>LOG_MESSAGE</b> — Writes a message to the execution log</li>
 *   <li><b>DECISION</b> — Implements conditional branching (if/else)</li>
 *   <li><b>TRIGGER_FLOW</b> — Starts another saved flow as a new execution (needs the
 *       {@link FlowEngine} extension)</li>
 * </ul>
 *
 * <p>Handlers requiring runtime services (CollectionRegistry, FormulaEvaluator,
 * ActionHandlerRegistry) are constructed lazily in {@link #onStartup(ModuleContext)}.
 *
 * @since 1.0.0
 */
public class CoreActionsModule implements KeltaModule {

    private final List<ActionHandler> handlers = new ArrayList<>();

    @Override
    public String getId() {
        return "kelta-core-actions";
    }

    @Override
    public String getName() {
        return "Core Actions Module";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public void onStartup(ModuleContext context) {
        var objectMapper = context.objectMapper();
        var collectionRegistry = context.collectionRegistry();
        var queryEngine = context.queryEngine();

        // Simple handlers (ObjectMapper only)
        handlers.add(new FieldUpdateActionHandler(objectMapper));
        handlers.add(new CreateTaskActionHandler());
        handlers.add(new LogMessageActionHandler(objectMapper));

        // TriggerFlow handler — starts another saved flow; fails at run time without an engine
        handlers.add(new TriggerFlowActionHandler(objectMapper, context.getExtension(FlowEngine.class)));

        // Handlers needing CollectionRegistry
        handlers.add(new CreateRecordActionHandler(objectMapper, collectionRegistry, queryEngine));
        handlers.add(new UpdateRecordActionHandler(objectMapper, collectionRegistry, queryEngine));
        handlers.add(new DeleteRecordActionHandler(objectMapper, collectionRegistry));

        // Query handler — queries records with optional aggregations
        handlers.add(new QueryRecordsActionHandler(
            objectMapper, collectionRegistry, queryEngine));

        // Handler needing FormulaEvaluator + ActionHandlerRegistry
        handlers.add(new DecisionActionHandler(
            objectMapper,
            context.formulaEvaluator(),
            context.actionHandlerRegistry()
        ));
    }

    @Override
    public List<ActionHandler> getActionHandlers() {
        return handlers;
    }
}
