package io.kelta.runtime.module.integration;

import io.kelta.runtime.flow.StateDataResolver;
import io.kelta.runtime.module.integration.handlers.*;
import io.kelta.runtime.module.integration.mapping.PayloadMapperService;
import io.kelta.runtime.module.integration.spi.ApiSpecStore;
import io.kelta.runtime.module.integration.spi.CredentialResolverPort;
import io.kelta.runtime.module.integration.spi.EmailService;
import io.kelta.runtime.module.integration.spi.IdempotencyStore;
import io.kelta.runtime.module.integration.spi.NotificationStore;
import io.kelta.runtime.module.integration.spi.ScriptExecutor;
import io.kelta.runtime.module.integration.spi.noop.LoggingEmailService;
import io.kelta.runtime.module.integration.spi.noop.LoggingScriptExecutor;
import io.kelta.runtime.outbound.OutboundUrlGuard;
import io.kelta.runtime.workflow.ActionHandler;
import io.kelta.runtime.workflow.module.KeltaModule;
import io.kelta.runtime.workflow.module.ModuleContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.ArrayList;
import java.util.List;

/**
 * Kelta module that provides integration action handlers for external communication.
 *
 * <p>This module registers the following action handlers:
 * <ul>
 *   <li><b>HTTP_CALLOUT</b> — Makes HTTP requests with response capture</li>
 *   <li><b>OUTBOUND_MESSAGE</b> — Sends outbound webhook messages</li>
 *   <li><b>SEND_NOTIFICATION</b> — Writes an in-app notification (needs the
 *       {@link NotificationStore} extension)</li>
 *   <li><b>PUBLISH_EVENT</b> — Publishes a tenant-scoped custom event (needs the
 *       {@link PublishEventActionHandler.EventPublisher} extension)</li>
 *   <li><b>DELAY</b> — Retired: fails, pointing at the {@code Wait} state</li>
 *   <li><b>EMAIL_ALERT</b> — Sends email notifications</li>
 *   <li><b>INVOKE_SCRIPT</b> — Invokes server-side scripts</li>
 * </ul>
 *
 * <p>Handlers with deep infrastructure dependencies use SPI interfaces:
 * <ul>
 *   <li>{@link EmailService} — for EmailAlertActionHandler</li>
 *   <li>{@link ScriptExecutor} — for InvokeScriptActionHandler</li>
 * </ul>
 *
 * <p>If no SPI implementations are provided via ModuleContext extensions,
 * logging no-op implementations are used as defaults.
 *
 * <p>{@code HTTP_CALLOUT}, {@code OUTBOUND_MESSAGE} and {@code CALL_API} fetch through the
 * {@link OutboundUrlGuard} extension (the host service's guard, carrying its private-range
 * allow-list). Without one they use a guard that allows no private range, so a missing extension
 * never widens what a flow can reach.
 *
 * @since 1.0.0
 */
public class IntegrationModule implements KeltaModule {

    private final List<ActionHandler> handlers = new ArrayList<>();

    @Override
    public String getId() {
        return "kelta-integration";
    }

    @Override
    public String getName() {
        return "Integration Module";
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    @Override
    public void onStartup(ModuleContext context) {
        var objectMapper = context.objectMapper();

        // Outbound HTTP handlers — every tenant-configured URL goes through the host's guard
        OutboundUrlGuard urlGuard = context.getExtension(OutboundUrlGuard.class);
        if (urlGuard == null) {
            urlGuard = new OutboundUrlGuard("");
        }
        handlers.add(new HttpCalloutActionHandler(objectMapper, urlGuard));
        handlers.add(new OutboundMessageActionHandler(objectMapper, urlGuard));

        // In-app notifications — without a NotificationStore extension every execution fails
        handlers.add(new SendNotificationActionHandler(objectMapper,
            context.getExtension(NotificationStore.class)));

        // Event publishing — without an EventPublisher extension every execution fails
        PublishEventActionHandler.EventPublisher eventPublisher =
            context.getExtension(PublishEventActionHandler.EventPublisher.class);
        handlers.add(new PublishEventActionHandler(objectMapper, eventPublisher));

        // Retired: kept registered so already-saved flows fail with a readable message
        handlers.add(new DelayActionHandler());

        // SPI-based handlers — use implementations from extensions or defaults

        // PayloadMapperService is shared by EmailAlert (PR 5) and CALL_API
        // (PR 4) so build it once up front.
        StateDataResolver dataResolver = context.getExtension(StateDataResolver.class);
        if (dataResolver == null) {
            dataResolver = new StateDataResolver(objectMapper);
        }
        PayloadMapperService payloadMapper = new PayloadMapperService(dataResolver);

        EmailService emailService = context.getExtension(EmailService.class);
        if (emailService == null) {
            emailService = new LoggingEmailService();
        }
        handlers.add(new EmailAlertActionHandler(objectMapper, emailService, payloadMapper));

        ScriptExecutor scriptExecutor = context.getExtension(ScriptExecutor.class);
        if (scriptExecutor == null) {
            scriptExecutor = new LoggingScriptExecutor();
        }
        handlers.add(new InvokeScriptActionHandler(objectMapper, scriptExecutor));

        // CALL_API — generic external-API step. Pulls SPI implementations
        // from the worker via ModuleContext extensions; missing extensions
        // produce typed runtime errors instead of failing module startup so
        // legacy installations stay bootable.
        ApiSpecStore apiSpecStore = context.getExtension(ApiSpecStore.class);
        CredentialResolverPort credentialResolver =
            context.getExtension(CredentialResolverPort.class);
        IdempotencyStore idempotencyStore = context.getExtension(IdempotencyStore.class);
        handlers.add(new CallApiActionHandler(
            objectMapper, payloadMapper, apiSpecStore,
            credentialResolver, idempotencyStore, urlGuard));

        // SQL_QUERY — raw SQL step. Needs JdbcTemplate + TransactionTemplate.
        // Skipped silently if extensions are not wired (e.g. in unit-test contexts
        // that don't have a datasource), keeping module startup tolerant.
        JdbcTemplate jdbcTemplate = context.getExtension(JdbcTemplate.class);
        TransactionTemplate transactionTemplate = context.getExtension(TransactionTemplate.class);
        if (jdbcTemplate != null && transactionTemplate != null) {
            handlers.add(new SqlQueryActionHandler(objectMapper, jdbcTemplate, transactionTemplate));
        }
    }

    @Override
    public List<ActionHandler> getActionHandlers() {
        return handlers;
    }
}
