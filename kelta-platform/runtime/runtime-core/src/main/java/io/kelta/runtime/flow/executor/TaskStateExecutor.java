package io.kelta.runtime.flow.executor;

import io.kelta.runtime.flow.*;
import io.kelta.runtime.workflow.ActionContext;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.ActionHandlerRegistry;
import io.kelta.runtime.workflow.ActionResult;
import tools.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Executes Task states by dispatching to the registered {@link ActionHandler}.
 * <p>
 * Applies the data flow pipeline: InputPath → execute → ResultPath → OutputPath.
 * Handles retry and catch policies for error recovery.
 * <p>
 * A handler called with {@link ActionContext#awaitable()} may answer
 * {@link ActionResult#awaiting}: the step parks WAITING, and the engine runs it again later with
 * the delivered outcome ({@link FlowExecutionContext#takeAwaitedOutcome()}) instead of calling the
 * handler — the same ResultPath/OutputPath on success, the same Retry and Catch on failure.
 *
 * @since 1.0.0
 */
public class TaskStateExecutor implements StateExecutor {

    private static final Logger log = LoggerFactory.getLogger(TaskStateExecutor.class);

    /**
     * Flow error code when a handler answers {@code ActionResult.awaiting} to a call that cannot
     * wait ({@code ActionContext.awaitable()} false: a branch of Parallel, Map or InvokeFlow).
     */
    public static final String AWAIT_NOT_SUPPORTED = "States.AwaitNotSupported";

    private final ActionHandlerRegistry handlerRegistry;
    private final StateDataResolver dataResolver;
    private final ObjectMapper objectMapper;

    public TaskStateExecutor(ActionHandlerRegistry handlerRegistry,
                             StateDataResolver dataResolver,
                             ObjectMapper objectMapper) {
        this.handlerRegistry = handlerRegistry;
        this.dataResolver = dataResolver;
        this.objectMapper = objectMapper;
    }

    @Override
    public String stateType() {
        return "Task";
    }

    @Override
    public StateExecutionResult execute(StateDefinition state, FlowExecutionContext context) {
        StateDefinition.TaskState task = (StateDefinition.TaskState) state;

        // 1. Apply InputPath
        Map<String, Object> input = dataResolver.applyInputPath(context.stateData(), task.inputPath());

        int maxAttempts = 1;
        for (RetryPolicy retry : task.retry()) {
            if (maxAttempts < retry.maxAttempts()) {
                maxAttempts = retry.maxAttempts();
            }
        }

        // A Task parked on awaited work resumes with what was delivered for it, in place of a
        // handler call: success flows on as if the handler had returned it; a failure takes the
        // Task's Retry (a new handler call, continuing the attempt count) and then its Catch.
        int firstAttempt = 1;
        AwaitedTaskOutcomes.TaskOutcome delivered = context.takeAwaitedOutcome();
        if (delivered != null) {
            if (delivered.success()) {
                return handleSuccess(task, context, delivered.output());
            }
            RetryPolicy retry = delivered.attempt() < maxAttempts
                ? findMatchingRetry(task, delivered.errorCode()) : null;
            if (retry == null) {
                return handleError(task, context, delivered.errorCode(), delivered.errorMessage());
            }
            long delay = retry.delayMillis(delivered.attempt());
            log.debug("Retrying awaited task '{}' (attempt {}/{}) after {}ms — error: {}",
                task.name(), delivered.attempt() + 1, maxAttempts, delay, delivered.errorCode());
            sleep(delay);
            firstAttempt = delivered.attempt() + 1;
        }

        // 2. Resolve handler.
        // Tenant-scoped first, then global. Runtime-loaded modules register their
        // handlers with ActionHandlerRegistry#registerTenantHandler, so resolving
        // against the global map alone made every module-contributed step fail
        // ResourceNotFound no matter how cleanly the module installed — the whole
        // runtime-module feature was unreachable from a flow.
        Optional<ActionHandler> handler =
            handlerRegistry.getHandler(context.tenantId(), task.resource());
        if (handler.isEmpty()) {
            return handleError(task, context, "ResourceNotFound",
                "No handler found for resource: " + task.resource());
        }

        // 3. Execute with retry
        ActionResult actionResult = null;
        String lastErrorCode = null;
        String lastErrorMessage = null;

        for (int attempt = firstAttempt; attempt <= maxAttempts; attempt++) {
            try {
                // Build ActionContext from flow state data.
                // When Parameters are defined, resolve template expressions against
                // the full state data and use the result as the handler config.
                // Otherwise, fall back to serializing the post-InputPath data.
                // Keys the handler declares unresolved keep their ${...} text; the handler
                // resolves them from stateData itself.
                String configJson;
                if (task.parameters() != null && !task.parameters().isEmpty()) {
                    Object resolved = resolveParameters(task.parameters(),
                        handler.get().unresolvedParameterKeys(), context.stateData());
                    configJson = resolveConfigJson(resolved);
                } else {
                    configJson = resolveConfigJson(input);
                }
                ActionContext actionContext = ActionContext.builder()
                    .tenantId(context.tenantId())
                    .userId(context.userId())
                    .workflowRuleId(context.flowId())
                    .executionLogId(context.executionId())
                    .actionConfigJson(configJson)
                    .data(input)
                    .resolvedData(input)
                    .triggerDepth(context.triggerDepth())
                    .stateData(context.stateData())
                    .stepName(task.name())
                    .attempt(attempt)
                    .awaitable(context.awaitable())
                    .build();

                actionResult = handler.get().execute(actionContext);

                if (actionResult.isAwaiting()) {
                    if (context.awaitable()) {
                        // Park here; the engine resumes this state with the delivered outcome.
                        log.debug("Task '{}' awaits work under '{}' until {}", task.name(),
                            actionResult.awaiting().resumeKey(), actionResult.awaiting().expiresAt());
                        return StateExecutionResult.awaiting(context.stateData(), actionResult.awaiting());
                    }
                    lastErrorCode = AWAIT_NOT_SUPPORTED;
                    lastErrorMessage = "Resource " + task.resource()
                        + " answered with work that completes later, which this step cannot wait for";
                    actionResult = null;
                } else if (actionResult.successful()) {
                    break; // Success — exit retry loop
                } else {
                    lastErrorCode = "ActionFailed";
                    lastErrorMessage = actionResult.errorMessage();
                }

            } catch (Exception e) {
                lastErrorCode = e.getClass().getSimpleName();
                lastErrorMessage = e.getMessage();
                actionResult = null;
            }

            // Check if we should retry
            if (attempt < maxAttempts) {
                RetryPolicy matchingRetry = findMatchingRetry(task, lastErrorCode);
                if (matchingRetry != null) {
                    long delay = matchingRetry.delayMillis(attempt);
                    log.debug("Retrying task '{}' (attempt {}/{}) after {}ms — error: {}",
                        task.name(), attempt, maxAttempts, delay, lastErrorCode);
                    sleep(delay);
                    continue;
                }
            }
            break; // No retry or no matching retry policy
        }

        // 4. Handle success
        if (actionResult != null && actionResult.successful()) {
            return handleSuccess(task, context, actionResult.outputData());
        }

        // 5. Handle failure — try Catch policies
        return handleError(task, context, lastErrorCode, lastErrorMessage);
    }

    /** Applies ResultPath → OutputPath to a successful result and transitions. */
    private StateExecutionResult handleSuccess(StateDefinition.TaskState task, FlowExecutionContext context,
                                               Map<String, Object> result) {
        Map<String, Object> afterResult = dataResolver.applyResultPath(context.stateData(), result, task.resultPath());
        Map<String, Object> output = dataResolver.applyOutputPath(afterResult, task.outputPath());

        String nextState = task.end() ? null : task.next();
        if (nextState == null && task.end()) {
            return StateExecutionResult.terminalSuccess(output);
        }
        return StateExecutionResult.success(nextState, output);
    }

    private StateExecutionResult handleError(StateDefinition.TaskState task,
                                             FlowExecutionContext context,
                                             String errorCode, String errorMessage) {
        // Check Catch policies
        for (CatchPolicy catchPolicy : task.catchPolicies()) {
            if (catchPolicy.matches(errorCode)) {
                Map<String, Object> errorData = new LinkedHashMap<>();
                errorData.put("Error", errorCode);
                errorData.put("Cause", errorMessage);

                Map<String, Object> stateAfterCatch = dataResolver.applyResultPath(
                    context.stateData(), errorData, catchPolicy.resultPath());

                log.debug("Task '{}' caught error '{}' — transitioning to '{}'",
                    task.name(), errorCode, catchPolicy.next());
                context.recordCaughtError(task.name(), errorCode, errorMessage);
                return StateExecutionResult.success(catchPolicy.next(), stateAfterCatch);
            }
        }

        // No catch — propagate failure
        return StateExecutionResult.failure(errorCode, errorMessage, context.stateData());
    }

    private Object resolveParameters(Map<String, Object> parameters, Set<String> unresolvedKeys,
                                     Map<String, Object> stateData) {
        if (unresolvedKeys == null || unresolvedKeys.isEmpty()) {
            return dataResolver.resolveDeep(parameters, stateData);
        }
        Map<String, Object> resolved = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : parameters.entrySet()) {
            resolved.put(entry.getKey(), unresolvedKeys.contains(entry.getKey())
                ? entry.getValue()
                : dataResolver.resolveDeep(entry.getValue(), stateData));
        }
        return resolved;
    }

    private RetryPolicy findMatchingRetry(StateDefinition.TaskState task, String errorCode) {
        for (RetryPolicy retry : task.retry()) {
            if (retry.matches(errorCode)) {
                return retry;
            }
        }
        return null;
    }

    private String resolveConfigJson(Object input) {
        try {
            return objectMapper.writeValueAsString(input);
        } catch (Exception e) {
            return "{}";
        }
    }

    private void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
