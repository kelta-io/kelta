package io.kelta.worker.service;

import tools.jackson.databind.ObjectMapper;
import io.kelta.runtime.context.TenantContext;
import io.kelta.runtime.flow.FlowEngine;
import io.kelta.runtime.flow.InitialStateBuilder;
import io.kelta.runtime.module.integration.spi.ScriptExecutor;
import io.kelta.runtime.module.integration.spi.ScriptExecutor.ScriptExecutionRequest;
import io.kelta.runtime.module.integration.spi.ScriptExecutor.ScriptExecutionResult;
import io.kelta.worker.repository.DataExportRepository;
import io.kelta.worker.repository.ScheduledJobRepository;
import org.junit.jupiter.api.*;

import java.io.Writer;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@DisplayName("ScheduledJobExecutorService")
class ScheduledJobExecutorServiceTest {

    private ScheduledJobRepository repository;
    private FlowEngine flowEngine;
    private InitialStateBuilder initialStateBuilder;
    private ObjectMapper objectMapper;
    private ScriptExecutor scriptExecutor;
    private ReportExecutionService reportExecutionService;
    private DataExportService dataExportService;
    private DataExportRepository dataExportRepository;
    private TenantSlugResolver tenantSlugResolver;
    private io.kelta.worker.service.email.DefaultEmailService emailService;
    private ScheduledJobExecutorService executor;

    @BeforeEach
    void setUp() {
        repository = mock(ScheduledJobRepository.class);
        flowEngine = mock(FlowEngine.class);
        initialStateBuilder = new InitialStateBuilder();
        objectMapper = new ObjectMapper();
        scriptExecutor = mock(ScriptExecutor.class);
        reportExecutionService = mock(ReportExecutionService.class);
        dataExportService = mock(DataExportService.class);
        dataExportRepository = mock(DataExportRepository.class);
        tenantSlugResolver = mock(TenantSlugResolver.class);
        emailService = mock(io.kelta.worker.service.email.DefaultEmailService.class);
        when(tenantSlugResolver.resolveSlug(anyString())).thenReturn(Optional.of("acme-corp"));
        executor = buildExecutor(emailService);
    }

    /** Builds the service with an optional DefaultEmailService (null = email disabled, harness mode). */
    private ScheduledJobExecutorService buildExecutor(
            io.kelta.worker.service.email.DefaultEmailService email) {
        @SuppressWarnings("unchecked")
        org.springframework.beans.factory.ObjectProvider<io.kelta.worker.service.email.DefaultEmailService> provider =
                mock(org.springframework.beans.factory.ObjectProvider.class);
        lenient().when(provider.getIfAvailable()).thenReturn(email);
        return new ScheduledJobExecutorService(
                repository, flowEngine, org.mockito.Mockito.mock(FlowActorResolver.class),
                initialStateBuilder, objectMapper,
                scriptExecutor, reportExecutionService, dataExportService, dataExportRepository,
                tenantSlugResolver, provider);
    }

    @Nested
    @DisplayName("executeAll — FLOW jobs")
    class FlowJobs {

        @Test
        @DisplayName("Should execute due FLOW job via FlowEngine")
        void shouldExecuteDueFlowJob() {
            Map<String, Object> job = Map.of(
                    "id", "job-1", "tenant_id", "t1", "job_type", "FLOW",
                    "job_reference_id", "flow-1", "cron_expression", "0 0 * * * *", "timezone", "UTC");
            when(repository.findDueJobs()).thenReturn(List.of(job));
            when(repository.findFlowById("flow-1")).thenReturn(Optional.of(Map.of(
                    "id", "flow-1", "tenant_id", "t1", "definition", "{\"StartAt\":\"Start\"}",
                    "trigger_config", "{}", "active", true)));
            when(flowEngine.startExecution(anyString(), anyString(), anyString(), any(), any(), any(), anyBoolean()))
                    .thenReturn("exec-1");

            executor.executeAll();

            verify(flowEngine).startExecution(eq("t1"), eq("flow-1"), anyString(), any(), isNull(), isNull(), eq(false));
            verify(repository).updateAfterExecution(eq("job-1"), eq("SUCCESS"), any(), any());
            verify(repository).insertExecutionLog(eq("job-1"), eq("SUCCESS"), isNull(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("Should bind tenant slug so flow execution sees correct schema")
        void shouldBindTenantSlugForFlowExecution() {
            Map<String, Object> job = Map.of(
                    "id", "job-1", "tenant_id", "t1", "job_type", "FLOW",
                    "job_reference_id", "flow-1", "cron_expression", "0 0 * * * *", "timezone", "UTC");
            when(repository.findDueJobs()).thenReturn(List.of(job));
            when(repository.findFlowById("flow-1")).thenReturn(Optional.of(Map.of(
                    "id", "flow-1", "tenant_id", "t1", "definition", "{\"StartAt\":\"Start\"}",
                    "trigger_config", "{}", "active", true)));
            when(tenantSlugResolver.resolveSlug("t1")).thenReturn(Optional.of("threadline-clothing"));

            // Capture the slug visible to TenantContext at the moment startExecution is invoked.
            java.util.concurrent.atomic.AtomicReference<String> seenSlug = new java.util.concurrent.atomic.AtomicReference<>();
            when(flowEngine.startExecution(any(), any(), any(), any(), any(), any(), anyBoolean()))
                    .thenAnswer(invocation -> {
                        seenSlug.set(TenantContext.getSlug());
                        return "exec-1";
                    });

            executor.executeAll();

            verify(tenantSlugResolver).resolveSlug("t1");
            assertThat(seenSlug.get()).isEqualTo("threadline-clothing");
        }

        @Test
        @DisplayName("Should mark FAILED when flow not found")
        void shouldFailWhenFlowNotFound() {
            Map<String, Object> job = Map.of(
                    "id", "job-2", "tenant_id", "t1", "job_type", "FLOW",
                    "job_reference_id", "missing-flow", "cron_expression", "0 0 * * * *", "timezone", "UTC");
            when(repository.findDueJobs()).thenReturn(List.of(job));
            when(repository.findFlowById("missing-flow")).thenReturn(Optional.empty());

            executor.executeAll();

            verify(flowEngine, never()).startExecution(any(), any(), any(), any(), any(), any(), anyBoolean());
            verify(repository).updateAfterExecution(eq("job-2"), eq("FAILED"), any(), any());
            verify(repository).insertExecutionLog(eq("job-2"), eq("FAILED"), contains("not found"), any(), any(), anyLong());
        }

        @Test
        @DisplayName("Should mark FAILED when flow is inactive")
        void shouldFailWhenFlowInactive() {
            Map<String, Object> job = Map.of(
                    "id", "job-3", "tenant_id", "t1", "job_type", "FLOW",
                    "job_reference_id", "flow-2", "cron_expression", "0 0 * * * *", "timezone", "UTC");
            when(repository.findDueJobs()).thenReturn(List.of(job));
            when(repository.findFlowById("flow-2")).thenReturn(Optional.of(Map.of(
                    "id", "flow-2", "active", false, "definition", "{}", "trigger_config", "{}")));

            executor.executeAll();

            verify(flowEngine, never()).startExecution(any(), any(), any(), any(), any(), any(), anyBoolean());
            verify(repository).updateAfterExecution(eq("job-3"), eq("FAILED"), any(), any());
        }

        @Test
        @DisplayName("Should process all jobs even when one fails")
        void shouldContinueOnFailure() {
            Map<String, Object> job1 = Map.of(
                    "id", "j1", "tenant_id", "t1", "job_type", "FLOW",
                    "job_reference_id", "missing", "cron_expression", "0 0 * * * *", "timezone", "UTC");
            Map<String, Object> job2 = Map.of(
                    "id", "j2", "tenant_id", "t1", "job_type", "FLOW",
                    "job_reference_id", "flow-ok", "cron_expression", "0 0 * * * *", "timezone", "UTC");
            when(repository.findDueJobs()).thenReturn(List.of(job1, job2));
            when(repository.findFlowById("missing")).thenReturn(Optional.empty());
            when(repository.findFlowById("flow-ok")).thenReturn(Optional.of(Map.of(
                    "id", "flow-ok", "active", true, "definition", "{}", "trigger_config", "{}")));
            when(flowEngine.startExecution(any(), any(), any(), any(), any(), any(), anyBoolean())).thenReturn("exec-2");

            executor.executeAll();

            verify(repository).updateAfterExecution(eq("j1"), eq("FAILED"), any(), any());
            verify(repository).updateAfterExecution(eq("j2"), eq("SUCCESS"), any(), any());
        }
    }

    @Nested
    @DisplayName("executeAll — SCRIPT jobs")
    class ScriptJobs {

        @Test
        @DisplayName("Should execute SCRIPT job via ScriptExecutor")
        void shouldExecuteScriptJob() {
            Map<String, Object> job = Map.of(
                    "id", "job-s1", "tenant_id", "t1", "job_type", "SCRIPT",
                    "job_reference_id", "script-1", "cron_expression", "0 0 * * * *", "timezone", "UTC");
            when(repository.findDueJobs()).thenReturn(List.of(job));
            when(repository.findScriptById("script-1")).thenReturn(Optional.of(Map.of(
                    "id", "script-1", "name", "test-script", "active", true,
                    "source_code", "var x = 1 + 2; x;", "language", "javascript")));
            when(scriptExecutor.execute(any(ScriptExecutionRequest.class)))
                    .thenReturn(ScriptExecutionResult.success(Map.of("result", 3), 50L));

            executor.executeAll();

            verify(scriptExecutor).execute(any(ScriptExecutionRequest.class));
            verify(repository).updateAfterExecution(eq("job-s1"), eq("SUCCESS"), any(), any());
            verify(repository).insertExecutionLog(eq("job-s1"), eq("SUCCESS"), isNull(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("Should mark FAILED when script not found")
        void shouldFailWhenScriptNotFound() {
            Map<String, Object> job = Map.of(
                    "id", "job-s2", "tenant_id", "t1", "job_type", "SCRIPT",
                    "job_reference_id", "missing-script", "cron_expression", "0 0 * * * *", "timezone", "UTC");
            when(repository.findDueJobs()).thenReturn(List.of(job));
            when(repository.findScriptById("missing-script")).thenReturn(Optional.empty());

            executor.executeAll();

            verify(scriptExecutor, never()).execute(any());
            verify(repository).updateAfterExecution(eq("job-s2"), eq("FAILED"), any(), any());
            verify(repository).insertExecutionLog(eq("job-s2"), eq("FAILED"), contains("not found"), any(), any(), anyLong());
        }

        @Test
        @DisplayName("Should mark FAILED when script is inactive")
        void shouldFailWhenScriptInactive() {
            Map<String, Object> job = Map.of(
                    "id", "job-s3", "tenant_id", "t1", "job_type", "SCRIPT",
                    "job_reference_id", "script-2", "cron_expression", "0 0 * * * *", "timezone", "UTC");
            when(repository.findDueJobs()).thenReturn(List.of(job));
            when(repository.findScriptById("script-2")).thenReturn(Optional.of(Map.of(
                    "id", "script-2", "name", "inactive-script", "active", false,
                    "source_code", "1+1;", "language", "javascript")));

            executor.executeAll();

            verify(scriptExecutor, never()).execute(any());
            verify(repository).updateAfterExecution(eq("job-s3"), eq("FAILED"), any(), any());
        }

        @Test
        @DisplayName("Should mark FAILED when script has no source code")
        void shouldFailWhenScriptHasNoSource() {
            Map<String, Object> job = Map.of(
                    "id", "job-s4", "tenant_id", "t1", "job_type", "SCRIPT",
                    "job_reference_id", "script-3", "cron_expression", "0 0 * * * *", "timezone", "UTC");
            when(repository.findDueJobs()).thenReturn(List.of(job));
            when(repository.findScriptById("script-3")).thenReturn(Optional.of(Map.of(
                    "id", "script-3", "name", "empty-script", "active", true,
                    "source_code", "", "language", "javascript")));

            executor.executeAll();

            verify(scriptExecutor, never()).execute(any());
            verify(repository).updateAfterExecution(eq("job-s4"), eq("FAILED"), any(), any());
            verify(repository).insertExecutionLog(eq("job-s4"), eq("FAILED"), contains("no source code"), any(), any(), anyLong());
        }

        @Test
        @DisplayName("Should mark FAILED when script execution fails")
        void shouldFailWhenScriptExecutionFails() {
            Map<String, Object> job = Map.of(
                    "id", "job-s5", "tenant_id", "t1", "job_type", "SCRIPT",
                    "job_reference_id", "script-4", "cron_expression", "0 0 * * * *", "timezone", "UTC");
            when(repository.findDueJobs()).thenReturn(List.of(job));
            when(repository.findScriptById("script-4")).thenReturn(Optional.of(Map.of(
                    "id", "script-4", "name", "error-script", "active", true,
                    "source_code", "throw new Error('oops');", "language", "javascript")));
            when(scriptExecutor.execute(any(ScriptExecutionRequest.class)))
                    .thenReturn(ScriptExecutionResult.failure("Error: oops", 10L));

            executor.executeAll();

            verify(repository).updateAfterExecution(eq("job-s5"), eq("FAILED"), any(), any());
            verify(repository).insertExecutionLog(eq("job-s5"), eq("FAILED"), eq("Error: oops"), any(), any(), anyLong());
        }
    }

    @Nested
    @DisplayName("executeAll — REPORT_EXPORT jobs")
    class ReportExportJobs {

        @Test
        @DisplayName("Should execute REPORT_EXPORT job via ReportExecutionService")
        void shouldExecuteReportExportJob() throws Exception {
            Map<String, Object> job = Map.of(
                    "id", "job-r1", "tenant_id", "t1", "job_type", "REPORT_EXPORT",
                    "job_reference_id", "report-1", "cron_expression", "0 0 * * * *", "timezone", "UTC");
            when(repository.findDueJobs()).thenReturn(List.of(job));
            when(repository.findReportById("report-1")).thenReturn(Optional.of(Map.of(
                    "id", "report-1", "name", "Test Report",
                    "primaryCollectionId", "col-1", "columns", "[]")));

            // Mock exportCsv to write some CSV content
            doAnswer(inv -> {
                Writer w = inv.getArgument(1);
                w.write("Name,Amount\n");
                w.write("Alice,100\n");
                w.write("Bob,200\n");
                return null;
            }).when(reportExecutionService).exportCsv(any(), any());

            executor.executeAll();

            verify(reportExecutionService).exportCsv(any(), any());
            verify(repository).updateAfterExecution(eq("job-r1"), eq("SUCCESS"), any(), any());
            verify(repository).insertExecutionLog(eq("job-r1"), eq("SUCCESS"), isNull(), any(), any(), anyLong());
        }

        @Test
        @DisplayName("Should mark FAILED when report not found")
        void shouldFailWhenReportNotFound() {
            Map<String, Object> job = Map.of(
                    "id", "job-r2", "tenant_id", "t1", "job_type", "REPORT_EXPORT",
                    "job_reference_id", "missing-report", "cron_expression", "0 0 * * * *", "timezone", "UTC");
            when(repository.findDueJobs()).thenReturn(List.of(job));
            when(repository.findReportById("missing-report")).thenReturn(Optional.empty());

            executor.executeAll();

            verify(repository).updateAfterExecution(eq("job-r2"), eq("FAILED"), any(), any());
            verify(repository).insertExecutionLog(eq("job-r2"), eq("FAILED"), contains("not found"), any(), any(), anyLong());
        }

        @Test
        @DisplayName("Should mark FAILED when report export throws exception")
        void shouldFailWhenReportExportThrows() throws Exception {
            Map<String, Object> job = Map.of(
                    "id", "job-r3", "tenant_id", "t1", "job_type", "REPORT_EXPORT",
                    "job_reference_id", "report-2", "cron_expression", "0 0 * * * *", "timezone", "UTC");
            when(repository.findDueJobs()).thenReturn(List.of(job));
            when(repository.findReportById("report-2")).thenReturn(Optional.of(Map.of(
                    "id", "report-2", "name", "Broken Report",
                    "primaryCollectionId", "col-2", "columns", "[]")));
            doThrow(new RuntimeException("Collection not found"))
                    .when(reportExecutionService).exportCsv(any(), any());

            executor.executeAll();

            verify(repository).updateAfterExecution(eq("job-r3"), eq("FAILED"), any(), any());
            verify(repository).insertExecutionLog(eq("job-r3"), eq("FAILED"), contains("Collection not found"), any(), any(), anyLong());
        }

        @Test
        @DisplayName("config.recipients → CSV emailed as attachment to each recipient")
        void deliversCsvToRecipients() throws Exception {
            Map<String, Object> job = new HashMap<>(Map.of(
                    "id", "job-r4", "tenant_id", "t1", "job_type", "REPORT_EXPORT",
                    "job_reference_id", "report-1", "cron_expression", "0 0 * * * *", "timezone", "UTC",
                    "name", "Weekly Sales",
                    "config", "{\"recipients\":[\"a@x.com\",\"b@y.com\"]}"));
            when(repository.findDueJobs()).thenReturn(List.of(job));
            when(repository.findReportById("report-1")).thenReturn(Optional.of(Map.of(
                    "id", "report-1", "name", "Test Report",
                    "primaryCollectionId", "col-1", "columns", "[]")));
            doAnswer(inv -> {
                Writer w = inv.getArgument(1);
                w.write("Name,Amount\nAlice,100\n");
                return null;
            }).when(reportExecutionService).exportCsv(any(), any());

            executor.executeAll();

            org.mockito.ArgumentCaptor<List<io.kelta.worker.service.email.EmailAttachment>> attachCaptor =
                    org.mockito.ArgumentCaptor.forClass(List.class);
            verify(emailService).queueEmailWithAttachments(eq("t1"), eq("a@x.com"),
                    contains("Weekly Sales"), anyString(), eq("SCHEDULED_REPORT"), eq("job-r4"),
                    attachCaptor.capture());
            verify(emailService).queueEmailWithAttachments(eq("t1"), eq("b@y.com"),
                    contains("Weekly Sales"), anyString(), eq("SCHEDULED_REPORT"), eq("job-r4"), any());
            io.kelta.worker.service.email.EmailAttachment attachment = attachCaptor.getValue().get(0);
            assertThat(attachment.contentType()).isEqualTo("text/csv");
            assertThat(new String(attachment.content())).contains("Alice,100");
            verify(repository).insertExecutionLog(eq("job-r4"), eq("SUCCESS"),
                    contains("Delivered to 2/2"), any(), any(), anyLong());
        }

        @Test
        @DisplayName("no config → export succeeds with no email attempted")
        void noConfigNoDelivery() throws Exception {
            Map<String, Object> job = Map.of(
                    "id", "job-r5", "tenant_id", "t1", "job_type", "REPORT_EXPORT",
                    "job_reference_id", "report-1", "cron_expression", "0 0 * * * *", "timezone", "UTC");
            when(repository.findDueJobs()).thenReturn(List.of(job));
            when(repository.findReportById("report-1")).thenReturn(Optional.of(Map.of(
                    "id", "report-1", "name", "Test Report",
                    "primaryCollectionId", "col-1", "columns", "[]")));
            doAnswer(inv -> {
                ((Writer) inv.getArgument(1)).write("h\n");
                return null;
            }).when(reportExecutionService).exportCsv(any(), any());

            executor.executeAll();

            verifyNoInteractions(emailService);
            verify(repository).updateAfterExecution(eq("job-r5"), eq("SUCCESS"), any(), any());
        }

        @Test
        @DisplayName("email disabled (no DefaultEmailService bean) → export succeeds, delivery skipped")
        void emailDisabledSkipsDelivery() throws Exception {
            ScheduledJobExecutorService noEmailExecutor = buildExecutor(null);
            Map<String, Object> job = new HashMap<>(Map.of(
                    "id", "job-r7", "tenant_id", "t1", "job_type", "REPORT_EXPORT",
                    "job_reference_id", "report-1", "cron_expression", "0 0 * * * *", "timezone", "UTC",
                    "name", "Weekly Sales",
                    "config", "{\"recipients\":[\"a@x.com\"]}"));
            when(repository.findDueJobs()).thenReturn(List.of(job));
            when(repository.findReportById("report-1")).thenReturn(Optional.of(Map.of(
                    "id", "report-1", "name", "Test Report",
                    "primaryCollectionId", "col-1", "columns", "[]")));
            doAnswer(inv -> {
                ((Writer) inv.getArgument(1)).write("h\n");
                return null;
            }).when(reportExecutionService).exportCsv(any(), any());

            noEmailExecutor.executeAll();

            verify(repository).updateAfterExecution(eq("job-r7"), eq("SUCCESS"), any(), any());
            verify(repository).insertExecutionLog(eq("job-r7"), eq("SUCCESS"),
                    contains("email disabled"), any(), any(), anyLong());
            verifyNoInteractions(emailService);
        }

        @Test
        @DisplayName("delivery failure never fails the job — logged on the execution note")
        void deliveryFailureDoesNotFailJob() throws Exception {
            Map<String, Object> job = new HashMap<>(Map.of(
                    "id", "job-r6", "tenant_id", "t1", "job_type", "REPORT_EXPORT",
                    "job_reference_id", "report-1", "cron_expression", "0 0 * * * *", "timezone", "UTC",
                    "name", "Weekly Sales",
                    "config", "{\"recipients\":[\"a@x.com\"]}"));
            when(repository.findDueJobs()).thenReturn(List.of(job));
            when(repository.findReportById("report-1")).thenReturn(Optional.of(Map.of(
                    "id", "report-1", "name", "Test Report",
                    "primaryCollectionId", "col-1", "columns", "[]")));
            doAnswer(inv -> {
                ((Writer) inv.getArgument(1)).write("h\n");
                return null;
            }).when(reportExecutionService).exportCsv(any(), any());
            when(emailService.queueEmailWithAttachments(any(), any(), any(), any(), any(), any(), any()))
                    .thenThrow(new RuntimeException("smtp down"));

            executor.executeAll();

            verify(repository).updateAfterExecution(eq("job-r6"), eq("SUCCESS"), any(), any());
            verify(repository).insertExecutionLog(eq("job-r6"), eq("SUCCESS"),
                    contains("Delivered to 0/1"), any(), any(), anyLong());
        }
    }

    @Nested
    @DisplayName("executeAll — mixed job types")
    class MixedJobs {

        @Test
        @DisplayName("Should handle mixed FLOW, SCRIPT, and REPORT_EXPORT jobs in one poll cycle")
        void shouldHandleMixedJobTypes() throws Exception {
            Map<String, Object> flowJob = Map.of(
                    "id", "j-flow", "tenant_id", "t1", "job_type", "FLOW",
                    "job_reference_id", "flow-1", "cron_expression", "0 0 * * * *", "timezone", "UTC");
            Map<String, Object> scriptJob = Map.of(
                    "id", "j-script", "tenant_id", "t1", "job_type", "SCRIPT",
                    "job_reference_id", "script-1", "cron_expression", "0 0 * * * *", "timezone", "UTC");
            Map<String, Object> reportJob = Map.of(
                    "id", "j-report", "tenant_id", "t1", "job_type", "REPORT_EXPORT",
                    "job_reference_id", "report-1", "cron_expression", "0 0 * * * *", "timezone", "UTC");

            when(repository.findDueJobs()).thenReturn(List.of(flowJob, scriptJob, reportJob));
            when(repository.findFlowById("flow-1")).thenReturn(Optional.of(Map.of(
                    "id", "flow-1", "active", true, "definition", "{}", "trigger_config", "{}")));
            when(flowEngine.startExecution(any(), any(), any(), any(), any(), any(), anyBoolean())).thenReturn("exec-1");
            when(repository.findScriptById("script-1")).thenReturn(Optional.of(Map.of(
                    "id", "script-1", "name", "test", "active", true,
                    "source_code", "1+1;", "language", "javascript")));
            when(scriptExecutor.execute(any(ScriptExecutionRequest.class)))
                    .thenReturn(ScriptExecutionResult.success(Map.of(), 5L));
            when(repository.findReportById("report-1")).thenReturn(Optional.of(Map.of(
                    "id", "report-1", "name", "Report", "primaryCollectionId", "c1", "columns", "[]")));
            doAnswer(inv -> {
                Writer w = inv.getArgument(1);
                w.write("Col\nVal\n");
                return null;
            }).when(reportExecutionService).exportCsv(any(), any());

            executor.executeAll();

            verify(repository).updateAfterExecution(eq("j-flow"), eq("SUCCESS"), any(), any());
            verify(repository).updateAfterExecution(eq("j-script"), eq("SUCCESS"), any(), any());
            verify(repository).updateAfterExecution(eq("j-report"), eq("SUCCESS"), any(), any());
        }

        @Test
        @DisplayName("Should skip unknown job types")
        void shouldSkipUnknownJobType() {
            Map<String, Object> job = Map.of(
                    "id", "j-unknown", "tenant_id", "t1", "job_type", "WEBHOOK",
                    "job_reference_id", "x", "cron_expression", "0 0 * * * *", "timezone", "UTC");
            when(repository.findDueJobs()).thenReturn(List.of(job));

            executor.executeAll();

            verify(repository).updateAfterExecution(eq("j-unknown"), eq("SKIPPED"), any(), any());
        }
    }

    @Nested
    @DisplayName("general behaviour")
    class GeneralBehaviour {

        @Test
        @DisplayName("Should do nothing when no due jobs")
        void shouldDoNothingWhenNoDueJobs() {
            when(repository.findDueJobs()).thenReturn(List.of());

            executor.executeAll();

            verify(flowEngine, never()).startExecution(any(), any(), any(), any(), any(), any(), anyBoolean());
            verify(scriptExecutor, never()).execute(any());
            verify(repository, never()).updateAfterExecution(any(), any(), any(), any());
        }

        @Test
        @DisplayName("Should clear TenantContext even when exception thrown")
        void shouldClearTenantContextOnException() {
            Map<String, Object> job = Map.of(
                    "id", "j-err", "tenant_id", "t-err", "job_type", "FLOW",
                    "job_reference_id", "flow-err", "cron_expression", "0 0 * * * *", "timezone", "UTC");
            when(repository.findDueJobs()).thenReturn(List.of(job));
            when(repository.findFlowById("flow-err")).thenReturn(Optional.of(Map.of(
                    "id", "flow-err", "active", true, "definition", "{}", "trigger_config", "{}")));
            when(flowEngine.startExecution(any(), any(), any(), any(), any(), any(), anyBoolean()))
                    .thenThrow(new RuntimeException("Boom"));

            executor.executeAll();

            assertThat(TenantContext.get()).isNull();
        }
    }

    @Nested
    @DisplayName("calculateNextRunAt")
    class CalculateNextRunAt {

        @Test
        @DisplayName("Should calculate next run in specified timezone")
        void shouldCalculateInTimezone() {
            Instant next = ScheduledJobRepository.calculateNextRunAt("0 0 9 * * *", "America/New_York");
            assertThat(next).isNotNull().isAfter(Instant.now());
        }

        @Test
        @DisplayName("Should default to UTC when timezone is null")
        void shouldDefaultToUtc() {
            Instant next = ScheduledJobRepository.calculateNextRunAt("0 0 9 * * *", null);
            assertThat(next).isNotNull().isAfter(Instant.now());
        }

        @Test
        @DisplayName("Should calculate from NOW, not from stale time")
        void shouldCalculateFromNow() {
            Instant next1 = ScheduledJobRepository.calculateNextRunAt("0 0 * * * *", "UTC");
            Instant next2 = ScheduledJobRepository.calculateNextRunAt("0 0 * * * *", "UTC");
            assertThat(next1).isAfter(Instant.now().minusSeconds(1));
            assertThat(next2).isAfter(Instant.now().minusSeconds(1));
        }
    }
}
