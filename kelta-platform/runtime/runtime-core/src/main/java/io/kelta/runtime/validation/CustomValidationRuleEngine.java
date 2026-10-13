package io.kelta.runtime.validation;

import io.kelta.runtime.formula.FormulaAst;
import io.kelta.runtime.formula.FormulaException;
import io.kelta.runtime.formula.FormulaParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Evaluates custom (formula-based) validation rules against record data.
 *
 * <p>This engine reads rules from the {@link ValidationRuleRegistry} and
 * evaluates each rule's error condition formula using the
 * {@link ValidationRuleEvaluator}. If any rule's formula evaluates to
 * {@code true}, the record is considered invalid and a
 * {@link RecordValidationException} is thrown.
 *
 * <p>This is called by {@code DefaultQueryEngine} after field-level
 * validation and before persistence.
 */
public class CustomValidationRuleEngine {

    private static final Logger log = LoggerFactory.getLogger(CustomValidationRuleEngine.class);

    private static final int FIELD_REFERENCE_CACHE_MAX_SIZE = 1000;

    private final Map<String, Set<String>> fieldReferenceCache = new ConcurrentHashMap<>();

    private final ValidationRuleRegistry ruleRegistry;
    private final ValidationRuleEvaluator ruleEvaluator;

    public CustomValidationRuleEngine(ValidationRuleRegistry ruleRegistry,
                                       ValidationRuleEvaluator ruleEvaluator) {
        this.ruleRegistry = ruleRegistry;
        this.ruleEvaluator = ruleEvaluator;
    }

    /**
     * Returns the field names referenced by the error-condition formulas of the
     * active rules that apply to the given collection and operation. Lets the
     * caller compute only the derived values (rollup summaries) a rule will read.
     * A formula that does not parse contributes nothing; {@link #evaluate} skips
     * such a rule anyway.
     *
     * @param collectionName the collection name
     * @param operationType  the operation type (CREATE or UPDATE)
     * @return the referenced field names, empty when no rule applies
     */
    public Set<String> referencedFields(String collectionName, OperationType operationType) {
        List<ValidationRuleDefinition> rules = ruleRegistry.getActiveRules(collectionName);
        if (rules.isEmpty()) {
            return Set.of();
        }
        String opType = operationType.name();
        Set<String> fields = new HashSet<>();
        for (ValidationRuleDefinition rule : rules) {
            if (rule.appliesTo(opType)) {
                fields.addAll(fieldReferences(rule.errorConditionFormula()));
            }
        }
        return fields;
    }

    private Set<String> fieldReferences(String formula) {
        if (formula == null || formula.isBlank()) {
            return Set.of();
        }
        Set<String> cached = fieldReferenceCache.get(formula);
        if (cached != null) {
            return cached;
        }
        Set<String> fields = new HashSet<>();
        try {
            // FormulaParser keeps per-parse state, so each parse gets its own instance
            collectFieldReferences(new FormulaParser().parse(formula), fields);
        } catch (FormulaException e) {
            log.debug("Validation rule formula does not parse; no field references: {}", e.getMessage());
        }
        if (fieldReferenceCache.size() >= FIELD_REFERENCE_CACHE_MAX_SIZE) {
            fieldReferenceCache.clear();
        }
        Set<String> result = Set.copyOf(fields);
        fieldReferenceCache.put(formula, result);
        return result;
    }

    private static void collectFieldReferences(FormulaAst node, Set<String> fields) {
        switch (node) {
            case FormulaAst.FieldRef ref -> fields.add(ref.fieldName());
            case FormulaAst.BinaryOp op -> {
                collectFieldReferences(op.left(), fields);
                collectFieldReferences(op.right(), fields);
            }
            case FormulaAst.UnaryOp op -> collectFieldReferences(op.operand(), fields);
            case FormulaAst.FunctionCall call -> call.arguments().forEach(arg -> collectFieldReferences(arg, fields));
            case FormulaAst.Literal literal -> { }
        }
    }

    /**
     * Evaluates all active validation rules for the given collection and
     * operation type against the record data.
     *
     * @param collectionName the collection name
     * @param recordData     the record's field values
     * @param operationType  the operation type (CREATE or UPDATE)
     * @throws RecordValidationException if any rules are violated
     */
    public void evaluate(String collectionName, Map<String, Object> recordData,
                         OperationType operationType) {
        List<ValidationRuleDefinition> rules = ruleRegistry.getActiveRules(collectionName);
        if (rules.isEmpty()) {
            return;
        }

        String opType = operationType.name();
        List<ValidationError> errors = new ArrayList<>();

        for (ValidationRuleDefinition rule : rules) {
            if (!rule.appliesTo(opType)) {
                continue;
            }

            try {
                if (ruleEvaluator.isInvalid(rule.errorConditionFormula(), recordData)) {
                    if (rule.isBlocking()) {
                        errors.add(new ValidationError(
                                rule.name(),
                                rule.errorMessage(),
                                rule.errorField()));
                        log.debug("Validation rule '{}' failed for collection '{}'",
                                rule.name(), collectionName);
                    } else {
                        // WARNING severity — logged only, does not block the save
                        log.info("Validation warning '{}' triggered for collection '{}': {}",
                                rule.name(), collectionName, rule.errorMessage());
                    }
                }
            } catch (Exception e) {
                // Don't block saves for broken formulas — log and skip
                log.warn("Error evaluating validation rule '{}' for collection '{}': {}",
                        rule.name(), collectionName, e.getMessage());
            }
        }

        if (!errors.isEmpty()) {
            throw new RecordValidationException(errors);
        }
    }
}
