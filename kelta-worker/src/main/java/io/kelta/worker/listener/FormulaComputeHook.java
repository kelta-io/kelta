package io.kelta.worker.listener;

import io.kelta.runtime.formula.FormulaAst;
import io.kelta.runtime.formula.FormulaEvaluator;
import io.kelta.runtime.formula.FormulaParser;
import io.kelta.runtime.model.CollectionDefinition;
import io.kelta.runtime.model.FieldDefinition;
import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.registry.CollectionRegistry;
import io.kelta.runtime.workflow.BeforeSaveHook;
import io.kelta.runtime.workflow.BeforeSaveHookRegistry;
import io.kelta.runtime.workflow.BeforeSaveResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Wildcard before-save hook that evaluates {@link FieldType#FORMULA} fields
 * eagerly at write time so the computed values are visible to downstream
 * hooks, event subscribers, and read-back responses without re-parsing the
 * expression on every read.
 *
 * <p>Formula values are not persisted to physical columns — the storage
 * adapter skips fields whose type returns {@code !hasPhysicalColumn()} — but
 * they are merged into the in-flight record map so the rest of the write
 * pipeline (and the response payload that includes the merged record) sees
 * the computed result.
 *
 * <p>Formulas may reference other formula fields on the same collection.
 * Dependencies are extracted from the parsed AST and resolved by topological
 * sort so a dependency is always evaluated before its dependents. Any
 * formulas that participate in a dependency cycle are short-circuited to the
 * configured error value (see {@link #errorValueFor(FieldDefinition)}) —
 * evaluating them would either loop or expose stale values.
 *
 * <p>Per-field error semantics: an evaluation failure (parse error, missing
 * function, runtime error, division by zero, circular reference) writes
 * {@code "#ERROR"} for {@code returnType = TEXT} formulas and {@code null}
 * for {@code NUMBER}/{@code BOOLEAN} formulas — matching the read-path
 * fallback in {@code DefaultQueryEngine.computeFormulaValue}.
 *
 * <p>Order {@code 250} — runs after record-type defaults (100), embed-on-write
 * (120), and other transforms, so the formula context reflects the final
 * pre-persist field values.
 *
 * @since 1.0.0
 */
@Component
public class FormulaComputeHook implements BeforeSaveHook {

    private static final Logger log = LoggerFactory.getLogger(FormulaComputeHook.class);

    /** Key in {@code fieldTypeConfig} carrying the formula expression. */
    static final String EXPRESSION_CONFIG_KEY = "expression";

    /** Key in {@code fieldTypeConfig} carrying the formula return type ("TEXT" / "NUMBER" / "BOOLEAN"). */
    static final String RETURN_TYPE_CONFIG_KEY = "returnType";

    /** Sentinel value written for TEXT formulas that fail to evaluate. */
    static final String ERROR_VALUE = "#ERROR";

    private final CollectionRegistry collectionRegistry;
    private final FormulaEvaluator formulaEvaluator;

    public FormulaComputeHook(CollectionRegistry collectionRegistry,
                              FormulaEvaluator formulaEvaluator) {
        this.collectionRegistry = collectionRegistry;
        this.formulaEvaluator = formulaEvaluator;
    }

    @Override
    public String getCollectionName() {
        return BeforeSaveHookRegistry.WILDCARD;
    }

    /**
     * Directly after {@code RecordTombstoneHook} (250). Hooks are Spring-discovered and discovery
     * order is unspecified, so no two hooks on one collection may share an order — see
     * {@code BeforeSaveHookWiringTest}.
     */
    @Override
    public int getOrder() {
        return 251;
    }

    @Override
    public BeforeSaveResult beforeCreate(String collectionName, Map<String, Object> record,
                                         String tenantId) {
        return compute(collectionName, record, null);
    }

    @Override
    public BeforeSaveResult beforeUpdate(String collectionName, String id,
                                         Map<String, Object> record,
                                         Map<String, Object> previous, String tenantId) {
        return compute(collectionName, record, previous);
    }

    private BeforeSaveResult compute(String collectionName, Map<String, Object> record,
                                     Map<String, Object> previous) {
        if (collectionName == null || record == null) {
            return BeforeSaveResult.ok();
        }
        CollectionDefinition definition = collectionRegistry.get(collectionName);
        if (definition == null) {
            return BeforeSaveResult.ok();
        }

        Map<String, FieldDefinition> formulaFields = new LinkedHashMap<>();
        for (FieldDefinition field : definition.fields()) {
            if (field.type() == FieldType.FORMULA) {
                formulaFields.put(field.name(), field);
            }
        }
        if (formulaFields.isEmpty()) {
            return BeforeSaveResult.ok();
        }

        Map<String, String> expressions = new HashMap<>();
        Map<String, Set<String>> dependencies = new HashMap<>();
        for (FieldDefinition field : formulaFields.values()) {
            String expression = expressionOf(field);
            expressions.put(field.name(), expression);
            dependencies.put(field.name(), parseDependencies(expression, formulaFields.keySet()));
        }

        TopologicalOrder topo = topoSort(formulaFields.keySet(), dependencies);

        Map<String, Object> context = new HashMap<>();
        if (previous != null) {
            context.putAll(previous);
        }
        context.putAll(record);

        Map<String, Object> updates = new LinkedHashMap<>(formulaFields.size());
        for (String name : topo.order()) {
            FieldDefinition field = formulaFields.get(name);
            String expression = expressions.get(name);
            Object value;
            if (expression == null || expression.isBlank()) {
                value = null;
            } else {
                try {
                    value = formulaEvaluator.evaluate(expression, context);
                } catch (RuntimeException e) {
                    log.debug("Formula evaluation failed for {}.{}: {}",
                            collectionName, name, e.getMessage());
                    value = errorValueFor(field);
                }
            }
            updates.put(name, value);
            context.put(name, value);
        }
        for (String name : topo.circular()) {
            FieldDefinition field = formulaFields.get(name);
            Object value = errorValueFor(field);
            log.warn("Formula field {}.{} participates in a circular reference — using fallback {}",
                    collectionName, name, value);
            updates.put(name, value);
            context.put(name, value);
        }

        return BeforeSaveResult.withFieldUpdates(updates);
    }

    private static String expressionOf(FieldDefinition field) {
        Object configured = field.getConfigValue(EXPRESSION_CONFIG_KEY);
        return configured instanceof String s ? s : null;
    }

    private static Object errorValueFor(FieldDefinition field) {
        Object returnType = field.getConfigValue(RETURN_TYPE_CONFIG_KEY);
        if (returnType instanceof String s) {
            return "TEXT".equalsIgnoreCase(s) ? ERROR_VALUE : null;
        }
        return ERROR_VALUE;
    }

    /**
     * Parses {@code expression} and returns the set of field references it
     * makes onto other FORMULA fields on the same collection. References to
     * non-formula fields are not dependency edges because their values are
     * already present in {@code record} / {@code previous} when this hook
     * runs. If the expression is null, blank, or fails to parse, no edges
     * are produced — the evaluator will surface the error later.
     */
    private static Set<String> parseDependencies(String expression, Set<String> formulaFieldNames) {
        if (expression == null || expression.isBlank()) {
            return Set.of();
        }
        FormulaAst ast;
        try {
            ast = new FormulaParser().parse(expression);
        } catch (RuntimeException e) {
            return Set.of();
        }
        Set<String> refs = new HashSet<>();
        collectFieldRefs(ast, refs);
        refs.retainAll(formulaFieldNames);
        return refs;
    }

    private static void collectFieldRefs(FormulaAst ast, Set<String> refs) {
        switch (ast) {
            case FormulaAst.FieldRef fr -> refs.add(fr.fieldName());
            case FormulaAst.BinaryOp b -> {
                collectFieldRefs(b.left(), refs);
                collectFieldRefs(b.right(), refs);
            }
            case FormulaAst.UnaryOp u -> collectFieldRefs(u.operand(), refs);
            case FormulaAst.FunctionCall fc -> {
                for (FormulaAst arg : fc.arguments()) {
                    collectFieldRefs(arg, refs);
                }
            }
            case FormulaAst.Literal ignored -> {
                // Literals carry no references.
            }
        }
    }

    /**
     * Kahn's algorithm with deterministic tie-breaking (alphabetical) so that
     * sibling formulas resolve in a stable order. Anything still carrying a
     * non-zero in-degree at the end is part of a cycle.
     */
    private static TopologicalOrder topoSort(Set<String> nodes,
                                             Map<String, Set<String>> dependencies) {
        Map<String, Integer> indegree = new HashMap<>();
        Map<String, Set<String>> dependents = new HashMap<>();
        for (String node : nodes) {
            indegree.put(node, 0);
            dependents.put(node, new HashSet<>());
        }
        for (Map.Entry<String, Set<String>> entry : dependencies.entrySet()) {
            String dependent = entry.getKey();
            for (String dep : entry.getValue()) {
                if (!nodes.contains(dep) || dep.equals(dependent)) {
                    if (dep.equals(dependent)) {
                        // Self-reference is a cycle of length 1.
                        indegree.merge(dependent, 1, Integer::sum);
                    }
                    continue;
                }
                if (dependents.get(dep).add(dependent)) {
                    indegree.merge(dependent, 1, Integer::sum);
                }
            }
        }

        Comparator<String> alphabetical = Comparator.naturalOrder();
        TreeSet<String> ready = new TreeSet<>(alphabetical);
        for (Map.Entry<String, Integer> e : indegree.entrySet()) {
            if (e.getValue() == 0) {
                ready.add(e.getKey());
            }
        }
        List<String> order = new ArrayList<>(nodes.size());
        Deque<String> queue = new ArrayDeque<>(ready);
        while (!queue.isEmpty()) {
            String n = queue.poll();
            order.add(n);
            TreeSet<String> newlyReady = new TreeSet<>(alphabetical);
            for (String d : dependents.get(n)) {
                int next = indegree.merge(d, -1, Integer::sum);
                if (next == 0) {
                    newlyReady.add(d);
                }
            }
            queue.addAll(newlyReady);
        }
        Set<String> circular = new TreeSet<>(alphabetical);
        for (String node : nodes) {
            if (!order.contains(node)) {
                circular.add(node);
            }
        }
        return new TopologicalOrder(order, circular);
    }

    private record TopologicalOrder(List<String> order, Set<String> circular) {
    }
}
