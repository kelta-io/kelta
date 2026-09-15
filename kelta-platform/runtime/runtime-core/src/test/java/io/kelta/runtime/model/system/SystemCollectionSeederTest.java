package io.kelta.runtime.model.system;

import io.kelta.runtime.model.FieldType;
import io.kelta.runtime.model.ValidationRules;
import tools.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class SystemCollectionSeederTest {

    private JdbcTemplate jdbcTemplate;
    private ObjectMapper objectMapper;
    private SystemCollectionSeeder seeder;

    private static final String SYSTEM_TENANT_ID = "00000000-0000-0000-0000-000000000001";

    @BeforeEach
    void setUp() {
        jdbcTemplate = mock(JdbcTemplate.class);
        objectMapper = new ObjectMapper();
        seeder = new SystemCollectionSeeder(jdbcTemplate, objectMapper);
    }

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        void shouldRejectNullJdbcTemplate() {
            assertThatThrownBy(() -> new SystemCollectionSeeder(null, objectMapper))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("jdbcTemplate");
        }

        @Test
        void shouldRejectNullObjectMapper() {
            assertThatThrownBy(() -> new SystemCollectionSeeder(jdbcTemplate, null))
                    .isInstanceOf(NullPointerException.class)
                    .hasMessageContaining("objectMapper");
        }
    }

    @Nested
    @DisplayName("mapFieldType")
    class MapFieldTypeTests {

        @Test
        void shouldMapStringTypes() {
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.STRING)).isEqualTo("string");
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.PICKLIST)).isEqualTo("string");
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.AUTO_NUMBER)).isEqualTo("string");
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.PHONE)).isEqualTo("string");
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.EMAIL)).isEqualTo("string");
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.URL)).isEqualTo("string");
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.ENCRYPTED)).isEqualTo("string");
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.EXTERNAL_ID)).isEqualTo("string");
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.FORMULA)).isEqualTo("string");
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.ROLLUP_SUMMARY)).isEqualTo("string");
        }

        @Test
        void shouldMapRelationshipTypesToTheirOwnNames() {
            // Relationship fields are stored with their proper logical type so the
            // frontend picker can recognize them as drillable. Reverse mapping in
            // CollectionLifecycleManager.reverseMapFieldType() consumes these.
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.REFERENCE)).isEqualTo("reference");
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.LOOKUP)).isEqualTo("lookup");
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.MASTER_DETAIL)).isEqualTo("master_detail");
        }

        @Test
        void shouldMapNumberTypes() {
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.INTEGER)).isEqualTo("number");
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.LONG)).isEqualTo("number");
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.DOUBLE)).isEqualTo("number");
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.CURRENCY)).isEqualTo("number");
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.PERCENT)).isEqualTo("number");
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.GEOLOCATION)).isEqualTo("number");
        }

        @Test
        void shouldMapBooleanType() {
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.BOOLEAN)).isEqualTo("boolean");
        }

        @Test
        void shouldMapDateTypes() {
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.DATE)).isEqualTo("date");
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.DATETIME)).isEqualTo("datetime");
        }

        @Test
        void shouldMapObjectAndArrayTypes() {
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.JSON)).isEqualTo("object");
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.ARRAY)).isEqualTo("array");
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.MULTI_PICKLIST)).isEqualTo("array");
        }

        @Test
        void shouldMapLongFormTextAndVectorToTheirOwnNames() {
            // TEXT and RICH_TEXT share Postgres TEXT storage but stay distinct
            // here so the admin UI can pick between plain textarea and rich-text
            // editor. VECTOR stays distinct so the admin UI hides it from the
            // generic value renderer.
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.TEXT)).isEqualTo("text");
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.RICH_TEXT)).isEqualTo("rich_text");
            assertThat(SystemCollectionSeeder.mapFieldType(FieldType.VECTOR)).isEqualTo("vector");
        }

        @Test
        void shouldCoverAllFieldTypes() {
            // Ensure every FieldType has a mapping (no exceptions)
            for (FieldType type : FieldType.values()) {
                String result = SystemCollectionSeeder.mapFieldType(type);
                assertThat(result)
                        .as("FieldType.%s should have a mapping", type)
                        .isNotNull()
                        .isNotEmpty();
            }
        }
    }

    @Nested
    @DisplayName("serializeConstraints")
    class SerializeConstraintsTests {

        @Test
        void shouldReturnNullForNoConstraints() {
            assertThat(seeder.serializeConstraints(null, null)).isNull();
        }

        @Test
        void shouldReturnNullForEmptyEnumValues() {
            assertThat(seeder.serializeConstraints(null, List.of())).isNull();
        }

        @Test
        void shouldSerializeMaxLength() {
            ValidationRules rules = ValidationRules.forString(null, 100);
            String json = seeder.serializeConstraints(rules, null);
            assertThat(json).contains("\"maxLength\":100");
        }

        @Test
        void shouldSerializeMinAndMaxLength() {
            ValidationRules rules = ValidationRules.forString(5, 100);
            String json = seeder.serializeConstraints(rules, null);
            assertThat(json).contains("\"minLength\":5");
            assertThat(json).contains("\"maxLength\":100");
        }

        @Test
        void shouldSerializePattern() {
            ValidationRules rules = ValidationRules.forString(null, null, "^[a-z]+$");
            String json = seeder.serializeConstraints(rules, null);
            assertThat(json).contains("\"pattern\":\"^[a-z]+$\"");
        }

        @Test
        void shouldSerializeEnumValues() {
            String json = seeder.serializeConstraints(null, List.of("A", "B", "C"));
            assertThat(json).contains("\"enumValues\":[\"A\",\"B\",\"C\"]");
        }

        @Test
        void shouldCombineRulesAndEnumValues() {
            ValidationRules rules = ValidationRules.forString(null, 50);
            String json = seeder.serializeConstraints(rules, List.of("X", "Y"));
            assertThat(json).contains("\"maxLength\":50");
            assertThat(json).contains("\"enumValues\":[\"X\",\"Y\"]");
        }
    }

    @Nested
    @DisplayName("serializeToJson")
    class SerializeToJsonTests {

        @Test
        void shouldSerializeString() {
            assertThat(seeder.serializeToJson("hello")).isEqualTo("\"hello\"");
        }

        @Test
        void shouldSerializeNumber() {
            assertThat(seeder.serializeToJson(42)).isEqualTo("42");
        }

        @Test
        void shouldSerializeBoolean() {
            assertThat(seeder.serializeToJson(true)).isEqualTo("true");
        }

        @Test
        void shouldSerializeList() {
            assertThat(seeder.serializeToJson(List.of("a", "b"))).isEqualTo("[\"a\",\"b\"]");
        }

        @Test
        void shouldSerializeMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("key", "value");
            assertThat(seeder.serializeToJson(map)).isEqualTo("{\"key\":\"value\"}");
        }
    }

    @Nested
    @DisplayName("seedCollection — create new")
    class SeedCollectionCreateTests {

        @Test
        void shouldCreateCollectionWhenNotExists() {
            // Given: collection does not exist in DB
            when(jdbcTemplate.queryForList(contains("SELECT id"), eq(String.class), anyString()))
                    .thenReturn(List.of());
            when(jdbcTemplate.queryForList(contains("SELECT id"), anyString(), anyString()))
                    .thenReturn(List.of());

            var definition = SystemCollectionDefinitions.tenants();

            // When
            var result = seeder.seedCollection(definition);

            // Then
            assertThat(result).isEqualTo(SystemCollectionSeeder.SeedResult.CREATED);

            // Verify collection INSERT was called
            ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate, atLeastOnce()).update(contains("INSERT INTO collection"), captor.capture());

            // The args should contain the collection name "tenants"
            Object[] args = captor.getValue();
            // args[2] = name
            assertThat(args[2]).isEqualTo("tenants");
            // args[3] = display_name
            assertThat(args[3]).isEqualTo("Tenants");
            // args[7] = current_version = 1
            assertThat(args[7]).isEqualTo(1);
            // args[8] = system_collection = true
            assertThat(args[8]).isEqualTo(true);
        }

        @Test
        void shouldInsertFieldsForNewCollection() {
            // Given: collection does not exist
            when(jdbcTemplate.queryForList(contains("SELECT id"), anyString(), anyString()))
                    .thenReturn(List.of());

            var definition = SystemCollectionDefinitions.profiles();
            // profiles has 3 fields: name, description, isSystem

            // When
            seeder.seedCollection(definition);

            // Then: should have 1 collection INSERT + N field INSERTs
            // Count the field inserts
            verify(jdbcTemplate, times(definition.fields().size())).update(
                    contains("INSERT INTO field"),
                    any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }
    }

    @Nested
    @DisplayName("seedCollection — sync existing")
    class SeedCollectionSyncTests {

        @Test
        void shouldReturnUnchangedWhenAllFieldsExist() {
            // Given: collection exists with system_collection=true
            Map<String, Object> row = new HashMap<>();
            row.put("id", "coll-123");
            row.put("system_collection", true);
            when(jdbcTemplate.queryForList(contains("SELECT id"), anyString(), anyString()))
                    .thenReturn(List.of(row));

            // And all fields already exist
            var definition = SystemCollectionDefinitions.profiles();
            List<String> existingFieldNames = definition.fields().stream()
                    .map(f -> f.name())
                    .toList();
            when(jdbcTemplate.queryForList(contains("SELECT name"), eq(String.class), eq("coll-123")))
                    .thenReturn(existingFieldNames);

            // When
            var result = seeder.seedCollection(definition);

            // Then
            assertThat(result).isEqualTo(SystemCollectionSeeder.SeedResult.UNCHANGED);
            // No field INSERTs
            verify(jdbcTemplate, never()).update(contains("INSERT INTO field"),
                    any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        void shouldAddMissingFields() {
            // Given: collection exists
            Map<String, Object> row = new HashMap<>();
            row.put("id", "coll-456");
            row.put("system_collection", true);
            when(jdbcTemplate.queryForList(contains("SELECT id"), anyString(), anyString()))
                    .thenReturn(List.of(row));

            // Only first field exists, rest are missing
            var definition = SystemCollectionDefinitions.profiles();
            when(jdbcTemplate.queryForList(contains("SELECT name"), eq(String.class), eq("coll-456")))
                    .thenReturn(List.of(definition.fields().get(0).name()));

            // When
            var result = seeder.seedCollection(definition);

            // Then
            assertThat(result).isEqualTo(SystemCollectionSeeder.SeedResult.UPDATED);
            // Should insert the missing fields (total - 1)
            int missingCount = definition.fields().size() - 1;
            verify(jdbcTemplate, times(missingCount)).update(contains("INSERT INTO field"),
                    any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any(), any(), any(), any(), any(), any(), any());
        }

        @Test
        void shouldMarkAsSystemCollectionIfNotAlready() {
            // Given: collection exists but system_collection=false
            Map<String, Object> row = new HashMap<>();
            row.put("id", "coll-789");
            row.put("system_collection", false);
            when(jdbcTemplate.queryForList(contains("SELECT id"), anyString(), anyString()))
                    .thenReturn(List.of(row));

            // All fields exist
            var definition = SystemCollectionDefinitions.profiles();
            List<String> existingFieldNames = definition.fields().stream()
                    .map(f -> f.name())
                    .toList();
            when(jdbcTemplate.queryForList(contains("SELECT name"), eq(String.class), eq("coll-789")))
                    .thenReturn(existingFieldNames);

            // When
            seeder.seedCollection(definition);

            // Then: should update system_collection flag
            verify(jdbcTemplate).update(contains("system_collection = true"), any(), eq("coll-789"));
        }

        @Test
        void shouldMarkAsSystemCollectionIfNull() {
            // Given: collection exists but system_collection=null
            Map<String, Object> row = new HashMap<>();
            row.put("id", "coll-null");
            row.put("system_collection", null);
            when(jdbcTemplate.queryForList(contains("SELECT id"), anyString(), anyString()))
                    .thenReturn(List.of(row));

            // All fields exist
            var definition = SystemCollectionDefinitions.profiles();
            List<String> existingFieldNames = definition.fields().stream()
                    .map(f -> f.name())
                    .toList();
            when(jdbcTemplate.queryForList(contains("SELECT name"), eq(String.class), eq("coll-null")))
                    .thenReturn(existingFieldNames);

            // When
            seeder.seedCollection(definition);

            // Then: should update system_collection flag
            verify(jdbcTemplate).update(contains("system_collection = true"), any(), eq("coll-null"));
        }
    }

    @Nested
    @DisplayName("seed — full run")
    class SeedFullTests {

        @Test
        void shouldSeedAllSystemCollections() {
            // Given: no collections exist yet
            when(jdbcTemplate.queryForList(contains("SELECT id"), anyString(), anyString()))
                    .thenReturn(List.of());

            // When
            seeder.seed();

            // Then: should create all system collections
            int expectedCollections = SystemCollectionDefinitions.all().size();
            verify(jdbcTemplate, times(expectedCollections)).update(
                    contains("INSERT INTO collection"),
                    any(), any(), any(), any(), any(), any(), any(), any(),
                    any(), any(), any());
        }

        @Test
        void shouldSeedCorrectCollectionCount() {
            // Verify we know the count
            assertThat(SystemCollectionDefinitions.all().size()).isGreaterThanOrEqualTo(56);
        }
    }

    @Nested
    @DisplayName("Field INSERT correctness")
    class FieldInsertCorrectnessTests {

        @Test
        void shouldSetReferenceConfigFields() {
            // Given: a collection with a lookup field
            when(jdbcTemplate.queryForList(contains("SELECT id"), anyString(), anyString()))
                    .thenReturn(List.of());

            // Users collection has a lookup field "profileId" -> "profiles"
            var definition = SystemCollectionDefinitions.users();

            // When
            seeder.seedCollection(definition);

            // Then: verify field inserts include reference config
            // We check that at least one field INSERT contains non-null reference target
            ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate, atLeastOnce()).update(contains("INSERT INTO field"), captor.capture());

            boolean foundReferenceField = false;
            for (Object[] args : captor.getAllValues()) {
                // args[11] = reference_target
                if ("profiles".equals(args[11])) {
                    foundReferenceField = true;
                    // args[12] = reference_collection_id (null in this mocked test —
                    // no real `collection` rows exist, so the lookup returns null)
                    assertThat(args[12]).isNull();
                    // args[13] = relationship_type should be "LOOKUP"
                    assertThat(args[13]).isEqualTo("LOOKUP");
                    // args[14] = relationship_name should be "Profile"
                    assertThat(args[14]).isEqualTo("Profile");
                    break;
                }
            }
            assertThat(foundReferenceField).as("Should find profileId field with reference to profiles").isTrue();
        }

        @Test
        void shouldSetColumnNameForMappedFields() {
            // Given: a collection with column name mappings
            when(jdbcTemplate.queryForList(contains("SELECT id"), anyString(), anyString()))
                    .thenReturn(List.of());

            // Users collection has firstName -> first_name column mapping
            var definition = SystemCollectionDefinitions.users();

            // When
            seeder.seedCollection(definition);

            // Then
            ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate, atLeastOnce()).update(contains("INSERT INTO field"), captor.capture());

            boolean foundMappedField = false;
            for (Object[] args : captor.getAllValues()) {
                // args[2] = name, args[18] = column_name (shifted +1 by new
                // reference_collection_id column at index 12)
                if ("firstName".equals(args[2])) {
                    foundMappedField = true;
                    assertThat(args[18]).isEqualTo("first_name");
                    break;
                }
            }
            assertThat(foundMappedField).as("Should find firstName field with column_name=first_name").isTrue();
        }

        @Test
        void shouldSetDefaultValueAsJson() {
            // Given: a collection with default values
            when(jdbcTemplate.queryForList(contains("SELECT id"), anyString(), anyString()))
                    .thenReturn(List.of());

            // Tenants collection has edition defaulting to "PROFESSIONAL"
            var definition = SystemCollectionDefinitions.tenants();

            // When
            seeder.seedCollection(definition);

            // Then
            ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate, atLeastOnce()).update(contains("INSERT INTO field"), captor.capture());

            boolean foundDefaultField = false;
            for (Object[] args : captor.getAllValues()) {
                // args[2] = name, args[8] = default_value
                if ("edition".equals(args[2])) {
                    foundDefaultField = true;
                    assertThat((String) args[8]).isEqualTo("\"PROFESSIONAL\"");
                    break;
                }
            }
            assertThat(foundDefaultField).as("Should find edition field with default PROFESSIONAL").isTrue();
        }

        @Test
        void shouldSetConstraintsWithEnumValues() {
            // Given: a collection with enum values
            when(jdbcTemplate.queryForList(contains("SELECT id"), anyString(), anyString()))
                    .thenReturn(List.of());

            // Tenants collection has edition with enum values
            var definition = SystemCollectionDefinitions.tenants();

            // When
            seeder.seedCollection(definition);

            // Then
            ArgumentCaptor<Object[]> captor = ArgumentCaptor.forClass(Object[].class);
            verify(jdbcTemplate, atLeastOnce()).update(contains("INSERT INTO field"), captor.capture());

            boolean foundConstraintField = false;
            for (Object[] args : captor.getAllValues()) {
                // args[2] = name, args[9] = constraints
                if ("edition".equals(args[2])) {
                    foundConstraintField = true;
                    String constraintsJson = (String) args[9];
                    assertThat(constraintsJson).contains("enumValues");
                    assertThat(constraintsJson).contains("PROFESSIONAL");
                    assertThat(constraintsJson).contains("ENTERPRISE");
                    break;
                }
            }
            assertThat(foundConstraintField).as("Should find edition field with enum constraints").isTrue();
        }
    }
}
