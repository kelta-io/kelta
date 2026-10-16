package io.kelta.runtime.flow;

import io.kelta.runtime.event.ChangeType;
import io.kelta.runtime.event.EventFactory;
import io.kelta.runtime.event.PlatformEvent;
import io.kelta.runtime.event.RecordChangedPayload;
import io.kelta.runtime.formula.FormulaEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for {@link FlowTriggerEvaluator}.
 */
class FlowTriggerEvaluatorTest {

    private FormulaEvaluator formulaEvaluator;
    private FlowTriggerEvaluator evaluator;

    @BeforeEach
    void setUp() {
        formulaEvaluator = mock(FormulaEvaluator.class);
        evaluator = new FlowTriggerEvaluator(formulaEvaluator);
    }

    @Test
    void nullTriggerConfigReturnsFalse() {
        PlatformEvent<RecordChangedPayload> event = createEvent(ChangeType.CREATED, "orders");
        assertFalse(evaluator.matchesRecordTrigger(event, null));
    }

    @Test
    void emptyTriggerConfigMatchesAll() {
        PlatformEvent<RecordChangedPayload> event = createEvent(ChangeType.CREATED, "orders");
        assertTrue(evaluator.matchesRecordTrigger(event, Map.of()));
    }

    @Test
    void collectionMatchesExact() {
        PlatformEvent<RecordChangedPayload> event = createEvent(ChangeType.CREATED, "orders");

        assertTrue(evaluator.matchesRecordTrigger(event,
                Map.of("collection", "orders")));
    }

    @Test
    void collectionMismatchReturnsFalse() {
        PlatformEvent<RecordChangedPayload> event = createEvent(ChangeType.CREATED, "orders");

        assertFalse(evaluator.matchesRecordTrigger(event,
                Map.of("collection", "contacts")));
    }

    @Test
    void eventTypeMatchesSingleEvent() {
        PlatformEvent<RecordChangedPayload> event = createEvent(ChangeType.CREATED, "orders");

        assertTrue(evaluator.matchesRecordTrigger(event,
                Map.of("events", List.of("CREATED"))));
    }

    @Test
    void eventTypeMatchesMultipleEvents() {
        PlatformEvent<RecordChangedPayload> event = createEvent(ChangeType.UPDATED, "orders");

        assertTrue(evaluator.matchesRecordTrigger(event,
                Map.of("events", List.of("CREATED", "UPDATED"))));
    }

    @Test
    void eventTypeMismatchReturnsFalse() {
        PlatformEvent<RecordChangedPayload> event = createEvent(ChangeType.DELETED, "orders");

        assertFalse(evaluator.matchesRecordTrigger(event,
                Map.of("events", List.of("CREATED", "UPDATED"))));
    }

    @Test
    void emptyEventsMatchesAll() {
        PlatformEvent<RecordChangedPayload> event = createEvent(ChangeType.DELETED, "orders");

        assertTrue(evaluator.matchesRecordTrigger(event,
                Map.of("events", List.of())));
    }

    @Test
    void triggerFieldsMatchOnUpdate() {
        RecordChangedPayload payload = RecordChangedPayload.updated(
                "orders", "rec-1",
                Map.of("status", "ACTIVE", "amount", 100),
                Map.of("status", "DRAFT"),
                List.of("status"));
        PlatformEvent<RecordChangedPayload> event = EventFactory.createRecordEvent(
                "record.updated", "tenant-1", "user-1", payload);

        assertTrue(evaluator.matchesRecordTrigger(event,
                Map.of("triggerFields", List.of("status", "amount"))));
    }

    @Test
    void triggerFieldsMismatchOnUpdateReturnsFalse() {
        RecordChangedPayload payload = RecordChangedPayload.updated(
                "orders", "rec-1",
                Map.of("status", "ACTIVE", "amount", 100),
                Map.of("name", "old"),
                List.of("name"));
        PlatformEvent<RecordChangedPayload> event = EventFactory.createRecordEvent(
                "record.updated", "tenant-1", "user-1", payload);

        assertFalse(evaluator.matchesRecordTrigger(event,
                Map.of("triggerFields", List.of("status", "amount"))));
    }

    @Test
    void triggerFieldsIgnoredForNonUpdateEvents() {
        PlatformEvent<RecordChangedPayload> event = createEvent(ChangeType.CREATED, "orders");

        // triggerFields only apply to UPDATE events — CREATED should match regardless
        assertTrue(evaluator.matchesRecordTrigger(event,
                Map.of("triggerFields", List.of("status"))));
    }

    @Test
    void filterFormulaMatchesWhenTrue() {
        PlatformEvent<RecordChangedPayload> event = createEvent(ChangeType.CREATED, "orders");
        when(formulaEvaluator.evaluateBoolean(eq("status == 'ACTIVE'"), anyMap())).thenReturn(true);

        assertTrue(evaluator.matchesRecordTrigger(event,
                Map.of("filterFormula", "status == 'ACTIVE'")));
    }

    @Test
    void filterFormulaMismatchReturnsFalse() {
        PlatformEvent<RecordChangedPayload> event = createEvent(ChangeType.CREATED, "orders");
        when(formulaEvaluator.evaluateBoolean(eq("status == 'ACTIVE'"), anyMap())).thenReturn(false);

        assertFalse(evaluator.matchesRecordTrigger(event,
                Map.of("filterFormula", "status == 'ACTIVE'")));
    }

    @Test
    void filterFormulaExceptionReturnsFalse() {
        PlatformEvent<RecordChangedPayload> event = createEvent(ChangeType.CREATED, "orders");
        when(formulaEvaluator.evaluateBoolean(anyString(), anyMap()))
                .thenThrow(new RuntimeException("parse error"));

        assertFalse(evaluator.matchesRecordTrigger(event,
                Map.of("filterFormula", "invalid formula")));
    }

    @Test
    void blankFilterFormulaIsIgnored() {
        PlatformEvent<RecordChangedPayload> event = createEvent(ChangeType.CREATED, "orders");

        assertTrue(evaluator.matchesRecordTrigger(event,
                Map.of("filterFormula", "   ")));
    }

    @Test
    void combinedConditionsAllMatch() {
        RecordChangedPayload payload = RecordChangedPayload.updated(
                "orders", "rec-1",
                Map.of("status", "ACTIVE"),
                Map.of("status", "DRAFT"),
                List.of("status"));
        PlatformEvent<RecordChangedPayload> event = EventFactory.createRecordEvent(
                "record.updated", "tenant-1", "user-1", payload);

        when(formulaEvaluator.evaluateBoolean(eq("status == 'ACTIVE'"), anyMap())).thenReturn(true);

        Map<String, Object> triggerConfig = Map.of(
                "collection", "orders",
                "events", List.of("UPDATED"),
                "triggerFields", List.of("status"),
                "filterFormula", "status == 'ACTIVE'"
        );

        assertTrue(evaluator.matchesRecordTrigger(event, triggerConfig));
    }

    @Test
    void combinedConditionsFailOnCollection() {
        PlatformEvent<RecordChangedPayload> event = createEvent(ChangeType.UPDATED, "contacts");

        Map<String, Object> triggerConfig = Map.of(
                "collection", "orders",
                "events", List.of("UPDATED")
        );

        assertFalse(evaluator.matchesRecordTrigger(event, triggerConfig));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    @Test
    void omittedPayloadEvaluatesFilterFormulaOnReReadData() {
        Map<String, Object> reRead = Map.of("status", "SHIPPED");
        RecordChangedPayload payload = RecordChangedPayload.updated(
                        "orders", "rec-1", reRead, null, List.of("status"))
                .withPayloadOmitted(true);
        PlatformEvent<RecordChangedPayload> event = EventFactory.createRecordEvent(
                "record.updated", "tenant-1", "user-1", payload);
        when(formulaEvaluator.evaluateBoolean("status = 'SHIPPED'", reRead)).thenReturn(true);

        assertTrue(evaluator.matchesRecordTrigger(event,
                Map.of("filterFormula", "status = 'SHIPPED'")));
        verify(formulaEvaluator).evaluateBoolean("status = 'SHIPPED'", reRead);
    }

    @Test
    void omittedPayloadWithoutReReadDataDoesNotEvaluateFilterOnNull() {
        RecordChangedPayload payload = RecordChangedPayload.updated(
                        "orders", "rec-1", null, null, List.of("status"))
                .withPayloadOmitted(true);
        PlatformEvent<RecordChangedPayload> event = EventFactory.createRecordEvent(
                "record.updated", "tenant-1", "user-1", payload);

        assertFalse(evaluator.matchesRecordTrigger(event,
                Map.of("filterFormula", "status = 'SHIPPED'")));
        assertTrue(evaluator.matchesRecordTrigger(event, Map.of("triggerFields", List.of("status"))));
        verifyNoInteractions(formulaEvaluator);
    }

    private PlatformEvent<RecordChangedPayload> createEvent(ChangeType changeType, String collection) {
        RecordChangedPayload payload = switch (changeType) {
            case CREATED -> RecordChangedPayload.created(
                    collection, "rec-1",
                    Map.of("status", "ACTIVE"));
            case UPDATED -> RecordChangedPayload.updated(
                    collection, "rec-1",
                    Map.of("status", "ACTIVE"),
                    Map.of("status", "DRAFT"),
                    List.of("status"));
            case DELETED -> RecordChangedPayload.deleted(
                    collection, "rec-1",
                    Map.of("status", "ACTIVE"));
        };
        return EventFactory.createRecordEvent(
                "record." + changeType.name().toLowerCase(), "tenant-1", "user-1", payload);
    }
}
