package io.kelta.runtime.event;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("ModuleChangedPayload Tests")
class ModuleChangedPayloadTest {

    @Nested
    @DisplayName("Constructor")
    class ConstructorTests {

        @Test
        void shouldCreateWithFullConstructor() {
            var payload = new ModuleChangedPayload(
                    "id-1", "tenant-1", "mod-1", "Analytics Module",
                    "1.0.0", "s3://modules/analytics.jar", "io.kelta.analytics.AnalyticsModule",
                    "{\"name\":\"analytics\"}", ModuleChangeType.INSTALLED);

            assertEquals("id-1", payload.getId());
            assertEquals("tenant-1", payload.getTenantId());
            assertEquals("mod-1", payload.getModuleId());
            assertEquals("Analytics Module", payload.getName());
            assertEquals("1.0.0", payload.getVersion());
            assertEquals("s3://modules/analytics.jar", payload.getS3Key());
            assertEquals("io.kelta.analytics.AnalyticsModule", payload.getModuleClass());
            assertEquals("{\"name\":\"analytics\"}", payload.getManifest());
            assertEquals(ModuleChangeType.INSTALLED, payload.getChangeType());
        }

        @Test
        void shouldCreateWithNoArgConstructor() {
            var payload = new ModuleChangedPayload();
            assertNull(payload.getId());
            assertNull(payload.getTenantId());
            assertNull(payload.getModuleId());
            assertNull(payload.getName());
            assertNull(payload.getVersion());
            assertNull(payload.getS3Key());
            assertNull(payload.getModuleClass());
            assertNull(payload.getManifest());
            assertNull(payload.getChangeType());
        }
    }

    @Nested
    @DisplayName("Setters")
    class SetterTests {

        @Test
        void shouldSetAndGetAllFields() {
            var payload = new ModuleChangedPayload();
            payload.setId("id-2");
            payload.setTenantId("tenant-2");
            payload.setModuleId("mod-2");
            payload.setName("Reporting");
            payload.setVersion("2.0.0");
            payload.setS3Key("s3://modules/reporting.jar");
            payload.setModuleClass("io.kelta.reporting.ReportModule");
            payload.setManifest("{\"name\":\"reporting\"}");
            payload.setChangeType(ModuleChangeType.ENABLED);

            assertEquals("id-2", payload.getId());
            assertEquals("tenant-2", payload.getTenantId());
            assertEquals("mod-2", payload.getModuleId());
            assertEquals("Reporting", payload.getName());
            assertEquals("2.0.0", payload.getVersion());
            assertEquals("s3://modules/reporting.jar", payload.getS3Key());
            assertEquals("io.kelta.reporting.ReportModule", payload.getModuleClass());
            assertEquals("{\"name\":\"reporting\"}", payload.getManifest());
            assertEquals(ModuleChangeType.ENABLED, payload.getChangeType());
        }
    }

    @Nested
    @DisplayName("toString")
    class ToStringTests {

        @Test
        void shouldContainKeyFields() {
            var payload = new ModuleChangedPayload(
                    "id-1", "tenant-1", "mod-1", "Analytics",
                    "1.0.0", "s3://key", "io.Mod", "{}", ModuleChangeType.DISABLED);

            String str = payload.toString();
            assertTrue(str.contains("tenant-1"));
            assertTrue(str.contains("mod-1"));
            assertTrue(str.contains("Analytics"));
            assertTrue(str.contains("1.0.0"));
            assertTrue(str.contains("DISABLED"));
        }
    }

    @Nested
    @DisplayName("ModuleChangeType Enum")
    class ModuleChangeTypeTests {

        @Test
        void shouldHaveAllExpectedValues() {
            assertEquals(5, ModuleChangeType.values().length);
            assertNotNull(ModuleChangeType.valueOf("INSTALLED"));
            assertNotNull(ModuleChangeType.valueOf("ENABLED"));
            assertNotNull(ModuleChangeType.valueOf("DISABLED"));
            assertNotNull(ModuleChangeType.valueOf("UNINSTALLED"));
            assertNotNull(ModuleChangeType.valueOf("UPGRADED"));
        }
    }

    @Nested
    @DisplayName("ChangeType Enum")
    class ChangeTypeTests {

        @Test
        void shouldHaveAllExpectedValues() {
            assertEquals(3, ChangeType.values().length);
            assertNotNull(ChangeType.valueOf("CREATED"));
            assertNotNull(ChangeType.valueOf("UPDATED"));
            assertNotNull(ChangeType.valueOf("DELETED"));
        }
    }
}
