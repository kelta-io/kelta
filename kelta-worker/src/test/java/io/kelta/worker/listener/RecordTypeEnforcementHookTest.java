package io.kelta.worker.listener;

import io.kelta.runtime.workflow.BeforeSaveResult;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@DisplayName("RecordTypeEnforcementHook")
class RecordTypeEnforcementHookTest {

    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private RecordTypeEnforcementHook hook;

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        objectMapper = JsonMapper.builder().build();
        hook = new RecordTypeEnforcementHook(jdbcTemplate, objectMapper);
    }

    @Test
    @DisplayName("Should use wildcard collection name")
    void shouldUseWildcard() {
        assertThat(hook.getCollectionName()).isEqualTo("*");
    }

    @Test
    @DisplayName("Should have order 100")
    void shouldHaveOrder100() {
        assertThat(hook.getOrder()).isEqualTo(100);
    }

    // -------------------------------------------------------------------------
    // beforeCreate tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should skip when no recordTypeId on create")
    void shouldSkipCreateWhenNoRecordTypeId() {
        var record = new HashMap<String, Object>();
        record.put("name", "Test Account");

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isTrue();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("Should skip system collections on create")
    void shouldSkipSystemCollectionsOnCreate() {
        var record = new HashMap<String, Object>();
        record.put("__collectionName", "collections");
        record.put("recordTypeId", "rt-1");

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isTrue();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("Should reject invalid record type on create")
    void shouldRejectInvalidRecordTypeOnCreate() {
        var record = new HashMap<String, Object>();
        record.put("recordTypeId", "rt-invalid");

        when(jdbcTemplate.queryForObject(contains("SELECT COUNT(*)"), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().getFirst().field()).isEqualTo("recordTypeId");
        assertThat(result.getErrors().getFirst().message()).contains("Invalid or inactive");
    }

    @Test
    @DisplayName("Should allow create with valid record type and no restrictions")
    void shouldAllowCreateWithValidTypeNoRestrictions() {
        var record = new HashMap<String, Object>();
        record.put("recordTypeId", "rt-1");

        when(jdbcTemplate.queryForObject(contains("SELECT COUNT(*)"), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.query(contains("record_type_picklist"), any(RowMapper.class), eq("rt-1")))
                .thenReturn(List.of());

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("Should reject disallowed picklist value on create")
    void shouldRejectDisallowedPicklistValueOnCreate() {
        var record = new HashMap<String, Object>();
        record.put("recordTypeId", "rt-1");
        record.put("status", "Closed");

        when(jdbcTemplate.queryForObject(contains("SELECT COUNT(*)"), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);

        var restriction = new RecordTypeEnforcementHook.PicklistRestriction(
                "status", java.util.Set.of("Open", "In Progress"), null);
        when(jdbcTemplate.query(contains("record_type_picklist"), any(RowMapper.class), eq("rt-1")))
                .thenReturn(List.of(restriction));

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().getFirst().field()).isEqualTo("status");
        assertThat(result.getErrors().getFirst().message()).contains("Closed");
        assertThat(result.getErrors().getFirst().message()).contains("not allowed");
    }

    @Test
    @DisplayName("Should allow valid picklist value on create")
    void shouldAllowValidPicklistValueOnCreate() {
        var record = new HashMap<String, Object>();
        record.put("recordTypeId", "rt-1");
        record.put("status", "Open");

        when(jdbcTemplate.queryForObject(contains("SELECT COUNT(*)"), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);

        var restriction = new RecordTypeEnforcementHook.PicklistRestriction(
                "status", java.util.Set.of("Open", "In Progress"), null);
        when(jdbcTemplate.query(contains("record_type_picklist"), any(RowMapper.class), eq("rt-1")))
                .thenReturn(List.of(restriction));

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("Should apply default value on create when field is empty")
    void shouldApplyDefaultValueOnCreate() {
        var record = new HashMap<String, Object>();
        record.put("recordTypeId", "rt-1");
        // status not provided

        when(jdbcTemplate.queryForObject(contains("SELECT COUNT(*)"), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);

        var restriction = new RecordTypeEnforcementHook.PicklistRestriction(
                "status", java.util.Set.of("Open", "In Progress"), "Open");
        when(jdbcTemplate.query(contains("record_type_picklist"), any(RowMapper.class), eq("rt-1")))
                .thenReturn(List.of(restriction));

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.hasFieldUpdates()).isTrue();
        assertThat(result.getFieldUpdates()).containsEntry("status", "Open");
    }

    // -------------------------------------------------------------------------
    // beforeUpdate tests
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Should skip when no recordTypeId on update")
    void shouldSkipUpdateWhenNoRecordTypeId() {
        var record = new HashMap<String, Object>();
        record.put("name", "Updated Name");
        var previous = new HashMap<String, Object>();

        BeforeSaveResult result = hook.beforeUpdate("rec-1", record, previous, "t1");

        assertThat(result.isSuccess()).isTrue();
        verifyNoInteractions(jdbcTemplate);
    }

    @Test
    @DisplayName("Should use previous recordTypeId when not in update data")
    void shouldUsePreviousRecordTypeIdOnUpdate() {
        var record = new HashMap<String, Object>();
        record.put("status", "Open");

        var previous = new HashMap<String, Object>();
        previous.put("recordTypeId", "rt-1");

        // No restrictions for the record type
        when(jdbcTemplate.query(contains("record_type_picklist"), any(RowMapper.class), eq("rt-1")))
                .thenReturn(List.of());

        BeforeSaveResult result = hook.beforeUpdate("rec-1", record, previous, "t1");

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("Should only validate fields being updated")
    void shouldOnlyValidateUpdatedFields() {
        var record = new HashMap<String, Object>();
        record.put("name", "Updated Name");
        // Not updating 'status'

        var previous = new HashMap<String, Object>();
        previous.put("recordTypeId", "rt-1");

        var restriction = new RecordTypeEnforcementHook.PicklistRestriction(
                "status", java.util.Set.of("Open", "In Progress"), null);
        when(jdbcTemplate.query(contains("record_type_picklist"), any(RowMapper.class), eq("rt-1")))
                .thenReturn(List.of(restriction));

        BeforeSaveResult result = hook.beforeUpdate("rec-1", record, previous, "t1");

        assertThat(result.isSuccess()).isTrue();
    }

    @Test
    @DisplayName("Should reject disallowed picklist value on update")
    void shouldRejectDisallowedPicklistValueOnUpdate() {
        var record = new HashMap<String, Object>();
        record.put("status", "Closed");

        var previous = new HashMap<String, Object>();
        previous.put("recordTypeId", "rt-1");

        var restriction = new RecordTypeEnforcementHook.PicklistRestriction(
                "status", java.util.Set.of("Open", "In Progress"), null);
        when(jdbcTemplate.query(contains("record_type_picklist"), any(RowMapper.class), eq("rt-1")))
                .thenReturn(List.of(restriction));

        BeforeSaveResult result = hook.beforeUpdate("rec-1", record, previous, "t1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().getFirst().field()).isEqualTo("status");
    }

    @Test
    @DisplayName("Should reject changing to inactive record type on update")
    void shouldRejectInactiveRecordTypeOnUpdate() {
        var record = new HashMap<String, Object>();
        record.put("recordTypeId", "rt-inactive");

        var previous = new HashMap<String, Object>();
        previous.put("recordTypeId", "rt-1");

        when(jdbcTemplate.queryForObject(contains("SELECT COUNT(*)"), eq(Integer.class), any(Object[].class)))
                .thenReturn(0);

        BeforeSaveResult result = hook.beforeUpdate("rec-1", record, previous, "t1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors().getFirst().field()).isEqualTo("recordTypeId");
    }

    @Test
    @DisplayName("Should validate multiple picklist fields and collect all errors")
    void shouldValidateMultiplePicklistFields() {
        var record = new HashMap<String, Object>();
        record.put("recordTypeId", "rt-1");
        record.put("status", "Invalid");
        record.put("priority", "Wrong");

        when(jdbcTemplate.queryForObject(contains("SELECT COUNT(*)"), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);

        var statusRestriction = new RecordTypeEnforcementHook.PicklistRestriction(
                "status", java.util.Set.of("Open", "Closed"), null);
        var priorityRestriction = new RecordTypeEnforcementHook.PicklistRestriction(
                "priority", java.util.Set.of("High", "Medium", "Low"), null);
        when(jdbcTemplate.query(contains("record_type_picklist"), any(RowMapper.class), eq("rt-1")))
                .thenReturn(List.of(statusRestriction, priorityRestriction));

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).hasSize(2);
    }

    // -------------------------------------------------------------------------
    // multi_picklist tests
    // -------------------------------------------------------------------------

    private static final Set<String> TAGS_ALLOWED = Set.of("red", "green", "blue");

    private void givenActiveTypeWith(RecordTypeEnforcementHook.PicklistRestriction restriction) {
        when(jdbcTemplate.queryForObject(contains("SELECT COUNT(*)"), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);
        when(jdbcTemplate.query(contains("record_type_picklist"), any(RowMapper.class), eq("rt-1")))
                .thenReturn(List.of(restriction));
    }

    private static RecordTypeEnforcementHook.PicklistRestriction tagsRestriction(String defaultValue) {
        return new RecordTypeEnforcementHook.PicklistRestriction("tags", TAGS_ALLOWED, defaultValue, true);
    }

    @Test
    @DisplayName("Should allow a multi-picklist list whose every value is allowed on create")
    void shouldAllowAllowedMultiPicklistListOnCreate() {
        var record = new HashMap<String, Object>();
        record.put("recordTypeId", "rt-1");
        record.put("tags", List.of("red", "blue"));
        givenActiveTypeWith(tagsRestriction(null));

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.hasFieldUpdates()).isFalse();
    }

    @Test
    @DisplayName("Should reject a partially-allowed multi-picklist list, naming only the disallowed value")
    void shouldRejectPartiallyAllowedMultiPicklistOnCreate() {
        var record = new HashMap<String, Object>();
        record.put("recordTypeId", "rt-1");
        record.put("tags", List.of("red", "purple"));
        givenActiveTypeWith(tagsRestriction(null));

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().getFirst().field()).isEqualTo("tags");
        assertThat(result.getErrors().getFirst().message())
                .startsWith("Value 'purple' is not allowed")
                .doesNotContain("'red'");
    }

    @Test
    @DisplayName("Should name every disallowed multi-picklist value once")
    void shouldNameEveryDisallowedMultiPicklistValue() {
        var record = new HashMap<String, Object>();
        record.put("recordTypeId", "rt-1");
        record.put("tags", List.of("purple", "green", "orange", "purple"));
        givenActiveTypeWith(tagsRestriction(null));

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors()).hasSize(1);
        assertThat(result.getErrors().getFirst().message())
                .startsWith("Values 'purple', 'orange' are not allowed for record type");
    }

    @Test
    @DisplayName("Should validate a multi-picklist String[] value per element")
    void shouldValidateMultiPicklistArrayValue() {
        var record = new HashMap<String, Object>();
        record.put("recordTypeId", "rt-1");
        record.put("tags", new String[] {"green", "purple"});
        givenActiveTypeWith(tagsRestriction(null));

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors().getFirst().message()).contains("'purple'");
    }

    @ParameterizedTest
    @ValueSource(strings = {"[\"red\",\"blue\"]", "red, blue", "red"})
    @DisplayName("Should accept allowed multi-picklist values in JSON-array and comma-separated string form")
    void shouldAcceptAllowedMultiPicklistStringEncodings(String encoded) {
        var record = new HashMap<String, Object>();
        record.put("recordTypeId", "rt-1");
        record.put("tags", encoded);
        givenActiveTypeWith(tagsRestriction(null));

        assertThat(hook.beforeCreate(record, "t1").isSuccess()).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[\"red\",\"purple\"]", "red,purple"})
    @DisplayName("Should reject a disallowed multi-picklist value in string form")
    void shouldRejectDisallowedMultiPicklistStringEncodings(String encoded) {
        var record = new HashMap<String, Object>();
        record.put("recordTypeId", "rt-1");
        record.put("tags", encoded);
        givenActiveTypeWith(tagsRestriction(null));

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors().getFirst().message()).startsWith("Value 'purple' is not allowed");
    }

    @Test
    @DisplayName("Should validate a list value per element even when the field type was not detected")
    void shouldValidateListValueOnUndetectedField() {
        var record = new HashMap<String, Object>();
        record.put("recordTypeId", "rt-1");
        record.put("tags", List.of("red"));
        givenActiveTypeWith(new RecordTypeEnforcementHook.PicklistRestriction("tags", TAGS_ALLOWED, null));

        assertThat(hook.beforeCreate(record, "t1").isSuccess()).isTrue();
    }

    @Test
    @DisplayName("Should apply a multi-picklist default as a single-element list when the field is absent")
    void shouldApplyMultiPicklistDefaultAsListWhenAbsent() {
        var record = new HashMap<String, Object>();
        record.put("recordTypeId", "rt-1");
        givenActiveTypeWith(tagsRestriction("green"));

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFieldUpdates()).containsEntry("tags", List.of("green"));
    }

    @Test
    @DisplayName("Should apply a multi-picklist default when the value is null, empty string or an empty list")
    void shouldApplyMultiPicklistDefaultWhenEmpty() {
        for (Object empty : new Object[] {null, "", List.of(), new String[0]}) {
            var record = new HashMap<String, Object>();
            record.put("recordTypeId", "rt-1");
            record.put("tags", empty);
            givenActiveTypeWith(tagsRestriction("green"));

            BeforeSaveResult result = hook.beforeCreate(record, "t1");

            assertThat(result.isSuccess()).as("empty value %s", empty).isTrue();
            assertThat(result.getFieldUpdates()).as("empty value %s", empty)
                    .containsEntry("tags", List.of("green"));
        }
    }

    @Test
    @DisplayName("Should apply a JSON-array multi-picklist default as that list")
    void shouldApplyJsonArrayMultiPicklistDefault() {
        var record = new HashMap<String, Object>();
        record.put("recordTypeId", "rt-1");
        givenActiveTypeWith(tagsRestriction("[\"red\",\"blue\"]"));

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.getFieldUpdates()).containsEntry("tags", List.of("red", "blue"));
    }

    @Test
    @DisplayName("Should not apply a multi-picklist default when values are selected")
    void shouldNotApplyMultiPicklistDefaultWhenProvided() {
        var record = new HashMap<String, Object>();
        record.put("recordTypeId", "rt-1");
        record.put("tags", List.of("blue"));
        givenActiveTypeWith(tagsRestriction("green"));

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.hasFieldUpdates()).isFalse();
    }

    @Test
    @DisplayName("Should keep applying a single-picklist default as a plain string")
    void shouldApplySinglePicklistDefaultAsString() {
        var record = new HashMap<String, Object>();
        record.put("recordTypeId", "rt-1");
        record.put("status", "");
        givenActiveTypeWith(new RecordTypeEnforcementHook.PicklistRestriction(
                "status", Set.of("Open", "Closed"), "Open", false));

        BeforeSaveResult result = hook.beforeCreate(record, "t1");

        assertThat(result.getFieldUpdates()).containsEntry("status", "Open");
    }

    @Test
    @DisplayName("Should reject a disallowed multi-picklist value on update")
    void shouldRejectDisallowedMultiPicklistOnUpdate() {
        var record = new HashMap<String, Object>();
        record.put("tags", List.of("blue", "purple"));
        var previous = new HashMap<String, Object>();
        previous.put("recordTypeId", "rt-1");
        when(jdbcTemplate.query(contains("record_type_picklist"), any(RowMapper.class), eq("rt-1")))
                .thenReturn(List.of(tagsRestriction(null)));

        BeforeSaveResult result = hook.beforeUpdate("rec-1", record, previous, "t1");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrors().getFirst().field()).isEqualTo("tags");
        assertThat(result.getErrors().getFirst().message()).contains("'purple'");
    }

    @Test
    @DisplayName("Should allow clearing a multi-picklist (empty list or null) on update")
    void shouldAllowClearingMultiPicklistOnUpdate() {
        var previous = new HashMap<String, Object>();
        previous.put("recordTypeId", "rt-1");
        when(jdbcTemplate.query(contains("record_type_picklist"), any(RowMapper.class), eq("rt-1")))
                .thenReturn(List.of(tagsRestriction("green")));

        var cleared = new HashMap<String, Object>();
        cleared.put("tags", List.of());
        var nulled = new HashMap<String, Object>();
        nulled.put("tags", null);

        BeforeSaveResult clearedResult = hook.beforeUpdate("rec-1", cleared, previous, "t1");
        BeforeSaveResult nulledResult = hook.beforeUpdate("rec-1", nulled, previous, "t1");

        assertThat(clearedResult.isSuccess()).isTrue();
        assertThat(clearedResult.hasFieldUpdates()).isFalse();
        assertThat(nulledResult.isSuccess()).isTrue();
        assertThat(nulledResult.hasFieldUpdates()).isFalse();
    }

    @Test
    @DisplayName("Should read the field type to mark multi_picklist restrictions")
    @SuppressWarnings("unchecked")
    void shouldDetectMultiPicklistFromFieldType() throws Exception {
        var record = new HashMap<String, Object>();
        record.put("recordTypeId", "rt-1");
        when(jdbcTemplate.queryForObject(contains("SELECT COUNT(*)"), eq(Integer.class), any(Object[].class)))
                .thenReturn(1);
        ArgumentCaptor<RowMapper<RecordTypeEnforcementHook.PicklistRestriction>> mapper =
                ArgumentCaptor.forClass(RowMapper.class);
        when(jdbcTemplate.query(contains("f.type AS field_type"), mapper.capture(), eq("rt-1")))
                .thenReturn(List.of());

        hook.beforeCreate(record, "t1");

        ResultSet multi = mock(ResultSet.class);
        when(multi.getString("field_name")).thenReturn("tags");
        when(multi.getString("default_value")).thenReturn("green");
        when(multi.getString("available_values")).thenReturn("[\"red\",\"green\"]");
        when(multi.getString("field_type")).thenReturn("MULTI_PICKLIST");
        ResultSet single = mock(ResultSet.class);
        when(single.getString("field_name")).thenReturn("status");
        when(single.getString("available_values")).thenReturn("[\"Open\"]");
        when(single.getString("field_type")).thenReturn("picklist");

        var multiRestriction = mapper.getValue().mapRow(multi, 0);
        var singleRestriction = mapper.getValue().mapRow(single, 1);

        assertThat(multiRestriction.multiValued()).isTrue();
        assertThat(multiRestriction.availableValues()).containsExactly("red", "green");
        assertThat(multiRestriction.defaultValue()).isEqualTo("green");
        assertThat(singleRestriction.multiValued()).isFalse();
    }
}
