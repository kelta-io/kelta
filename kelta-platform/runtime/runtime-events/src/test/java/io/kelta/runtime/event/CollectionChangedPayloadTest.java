package io.kelta.runtime.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("CollectionChangedPayload Tests")
class CollectionChangedPayloadTest {

    @Nested
    @DisplayName("Getters and Setters")
    class GettersSetters {

        @Test
        void shouldSetAndGetAllFields() {
            var payload = new CollectionChangedPayload();
            var now = Instant.now();

            payload.setId("col-1");
            payload.setName("contacts");
            payload.setDisplayName("Contacts");
            payload.setDescription("Contact collection");
            payload.setActive(true);
            payload.setCurrentVersion(3);
            payload.setCreatedAt(now);
            payload.setUpdatedAt(now);
            payload.setChangeType(ChangeType.CREATED);

            assertEquals("col-1", payload.getId());
            assertEquals("contacts", payload.getName());
            assertEquals("Contacts", payload.getDisplayName());
            assertEquals("Contact collection", payload.getDescription());
            assertEquals(Boolean.TRUE, payload.getActive());
            assertEquals(3, payload.getCurrentVersion());
            assertEquals(now, payload.getCreatedAt());
            assertEquals(now, payload.getUpdatedAt());
            assertEquals(ChangeType.CREATED, payload.getChangeType());
        }

        @Test
        void shouldSupportNullFields() {
            var payload = new CollectionChangedPayload();
            assertNull(payload.getId());
            assertNull(payload.getName());
            assertNull(payload.getDisplayName());
            assertNull(payload.getDescription());
            assertNull(payload.getActive());
            assertNull(payload.getCurrentVersion());
            assertNull(payload.getFields());
            assertNull(payload.getCreatedAt());
            assertNull(payload.getUpdatedAt());
            assertNull(payload.getChangeType());
        }
    }

    @Nested
    @DisplayName("JSON active flag")
    class ActiveJson {

        private final ObjectMapper mapper = new ObjectMapper();

        @Test
        void missingActiveKeyDeserialisesToNull() {
            var payload = mapper.readValue(
                    "{\"id\":\"col-1\",\"name\":\"contacts\",\"changeType\":\"UPDATED\"}",
                    CollectionChangedPayload.class);

            assertNull(payload.getActive());
        }

        @Test
        void explicitActiveFalseDeserialisesToFalse() {
            var payload = mapper.readValue(
                    "{\"id\":\"col-1\",\"active\":false,\"changeType\":\"UPDATED\"}",
                    CollectionChangedPayload.class);

            assertEquals(Boolean.FALSE, payload.getActive());
        }

        @Test
        void nullActiveIsOmittedFromJson() {
            var payload = new CollectionChangedPayload();
            payload.setId("col-1");
            payload.setChangeType(ChangeType.UPDATED);

            var tree = mapper.readTree(mapper.writeValueAsString(payload));

            assertFalse(tree.has("active"));
        }

        @Test
        void setActiveIsSerialised() {
            var payload = new CollectionChangedPayload();
            payload.setActive(false);

            var tree = mapper.readTree(mapper.writeValueAsString(payload));

            assertTrue(tree.has("active"));
            assertFalse(tree.get("active").asBoolean());
        }
    }

    @Nested
    @DisplayName("FieldPayload")
    class FieldPayloadTests {

        @Test
        void shouldSetAndGetFieldPayloadFields() {
            var field = new CollectionChangedPayload.FieldPayload();
            field.setId("f-1");
            field.setName("email");
            field.setType("string");
            field.setRequired(true);
            field.setUnique(true);
            field.setDescription("User email");
            field.setConstraints("{\"maxLength\": 255}");
            field.setFieldTypeConfig("{\"format\": \"email\"}");
            field.setReferenceTarget("users");
            field.setRelationshipType("belongsTo");
            field.setRelationshipName("owner");
            field.setCascadeDelete(true);

            assertEquals("f-1", field.getId());
            assertEquals("email", field.getName());
            assertEquals("string", field.getType());
            assertTrue(field.isRequired());
            assertTrue(field.isUnique());
            assertEquals("User email", field.getDescription());
            assertEquals("{\"maxLength\": 255}", field.getConstraints());
            assertEquals("{\"format\": \"email\"}", field.getFieldTypeConfig());
            assertEquals("users", field.getReferenceTarget());
            assertEquals("belongsTo", field.getRelationshipType());
            assertEquals("owner", field.getRelationshipName());
            assertTrue(field.isCascadeDelete());
        }

        @Test
        void shouldDefaultBooleanFieldsToFalse() {
            var field = new CollectionChangedPayload.FieldPayload();
            assertFalse(field.isRequired());
            assertFalse(field.isUnique());
            assertFalse(field.isCascadeDelete());
        }
    }

    @Nested
    @DisplayName("Fields List")
    class FieldsList {

        @Test
        void shouldSetAndGetFieldsList() {
            var payload = new CollectionChangedPayload();
            var field = new CollectionChangedPayload.FieldPayload();
            field.setName("name");
            field.setType("string");
            payload.setFields(List.of(field));

            assertEquals(1, payload.getFields().size());
            assertEquals("name", payload.getFields().get(0).getName());
        }
    }

    @Nested
    @DisplayName("Equality")
    class Equality {

        @Test
        void shouldBeEqualWhenKeyFieldsMatch() {
            var p1 = new CollectionChangedPayload();
            p1.setId("col-1");
            p1.setName("contacts");
            p1.setDescription("desc");
            p1.setActive(true);
            p1.setCurrentVersion(1);
            p1.setChangeType(ChangeType.CREATED);

            var p2 = new CollectionChangedPayload();
            p2.setId("col-1");
            p2.setName("contacts");
            p2.setDescription("desc");
            p2.setActive(true);
            p2.setCurrentVersion(1);
            p2.setChangeType(ChangeType.CREATED);

            assertEquals(p1, p2);
            assertEquals(p1.hashCode(), p2.hashCode());
        }

        @Test
        void shouldNotBeEqualWhenChangeTypeDiffers() {
            var p1 = new CollectionChangedPayload();
            p1.setId("col-1");
            p1.setName("contacts");
            p1.setChangeType(ChangeType.CREATED);

            var p2 = new CollectionChangedPayload();
            p2.setId("col-1");
            p2.setName("contacts");
            p2.setChangeType(ChangeType.DELETED);

            assertNotEquals(p1, p2);
        }

        @Test
        void shouldNotBeEqualWhenActiveIsUnsetOnOneSide() {
            var p1 = new CollectionChangedPayload();
            p1.setId("col-1");
            p1.setActive(false);

            var p2 = new CollectionChangedPayload();
            p2.setId("col-1");

            assertNotEquals(p1, p2);
        }

        @Test
        void shouldNotBeEqualToNull() {
            var p1 = new CollectionChangedPayload();
            p1.setId("col-1");
            assertNotEquals(p1, null);
        }

        @Test
        void shouldBeEqualToSelf() {
            var p1 = new CollectionChangedPayload();
            p1.setId("col-1");
            assertEquals(p1, p1);
        }

        @Test
        void shouldNotBeEqualToDifferentType() {
            var p1 = new CollectionChangedPayload();
            assertNotEquals(p1, "string");
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        void shouldContainKeyFields() {
            var payload = new CollectionChangedPayload();
            payload.setId("col-1");
            payload.setName("contacts");
            payload.setChangeType(ChangeType.UPDATED);
            payload.setCurrentVersion(2);
            payload.setActive(true);

            String str = payload.toString();
            assertTrue(str.contains("col-1"));
            assertTrue(str.contains("contacts"));
            assertTrue(str.contains("UPDATED"));
        }
    }
}
