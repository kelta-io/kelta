package io.kelta.worker.module;

import io.kelta.runtime.module.ExternalModuleManifest;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.worker.repository.ScheduledJobRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

import static io.kelta.worker.module.ExternalModuleFixtures.row;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("ModuleScheduleSync")
class ModuleScheduleSyncTest {

    private final ScheduledJobRepository repository = mock(ScheduledJobRepository.class);
    private final ModuleScheduleSync sync = new ModuleScheduleSync(repository, new ObjectMapper());
    private final TenantModuleData install = row(TenantModuleData.STATUS_INSTALLING);

    private static ExternalModuleManifest withSchedules(ExternalModuleManifest.Schedule... schedules) {
        ExternalModuleManifest base = ExternalModuleFixtures.manifest();
        return new ExternalModuleManifest(base.manifestVersion(), base.protocolVersion(), base.id(), base.name(),
                base.version(), null, null, null, base.permissions(), null, null, base.actions(), List.of(),
                List.of(), List.of(), List.of(), List.of(schedules), false, null);
    }

    @Test
    @DisplayName("a new schedule becomes a UTC MODULE_ACTION job keyed by the install, with moduleId/action config")
    void insertsNewSchedules() {
        when(repository.findModuleSchedules(ExternalModuleFixtures.INSTALL, ExternalModuleFixtures.TENANT))
                .thenReturn(List.of());

        sync.sync(install, withSchedules(new ExternalModuleManifest.Schedule("ping", "0 */5 * * * *")));

        verify(repository).insertModuleSchedule(eq(ExternalModuleFixtures.TENANT), eq(ExternalModuleFixtures.INSTALL),
                eq("module:stub:ping@0 */5 * * * *"), eq("0 */5 * * * *"),
                eq("{\"moduleId\":\"stub\",\"action\":\"ping\"}"), any());
        verify(repository, never()).deleteModuleSchedules(anyString(), anyString(), any());
    }

    @Test
    @DisplayName("an unchanged schedule is kept; one the manifest dropped (or whose cron changed) is deleted")
    void keepsAndDeletes() {
        when(repository.findModuleSchedules(ExternalModuleFixtures.INSTALL, ExternalModuleFixtures.TENANT))
                .thenReturn(List.of(
                        Map.of("id", "job-keep", "name", "module:stub:ping@0 0 * * * *"),
                        Map.of("id", "job-old", "name", "module:stub:ping@0 30 * * * *")));

        sync.sync(install, withSchedules(new ExternalModuleManifest.Schedule("ping", "0 0 * * * *")));

        verify(repository, never()).insertModuleSchedule(anyString(), anyString(), anyString(), anyString(),
                anyString(), any());
        verify(repository).deleteModuleSchedules(ExternalModuleFixtures.INSTALL, ExternalModuleFixtures.TENANT,
                List.of("job-old"));
    }

    @Test
    @DisplayName("uninstall deletes every job of the install")
    void deleteAll() {
        sync.delete(install);

        verify(repository).deleteModuleSchedules(eq(ExternalModuleFixtures.INSTALL), eq(ExternalModuleFixtures.TENANT),
                isNull());
    }

    @Test
    @DisplayName("names fit the 200-character column")
    void namesFit() {
        String cron = "0 0 " + "1,".repeat(120) + "2 * * *";
        assertThat(ModuleScheduleSync.name("stub", new ExternalModuleManifest.Schedule("ping", cron)))
                .hasSize(ModuleScheduleSync.MAX_NAME_LENGTH);
    }
}
