package io.kelta.worker.module;

import io.kelta.runtime.module.ExternalModuleManifest;
import io.kelta.runtime.module.TenantModuleData;
import io.kelta.runtime.outbound.OutboundHttpClient;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

/** Rows, manifests and responses for the part-9d invocation tests. */
final class ExternalModuleFixtures {

    static final String TENANT = "tenant-1";
    static final String MODULE = "stub";
    static final String INSTALL = "install-1";

    private ExternalModuleFixtures() {
    }

    static TenantModuleData row(String status) {
        return row(status, Instant.parse("2026-10-01T00:00:00Z"));
    }

    static TenantModuleData row(String status, Instant updatedAt) {
        return new TenantModuleData(INSTALL, TENANT, MODULE, "Stub", "1.0.0", null, "https://stub.example.test",
                null, null, null, "{}", status, "admin", updatedAt, updatedAt, null,
                List.of(action("ping")), "https://stub.example.test", 1, "app-1", "svc-1");
    }

    static TenantModuleData.TenantModuleActionData action(String key) {
        return new TenantModuleData.TenantModuleActionData("act-" + key, INSTALL, MODULE + ":" + key, "Ping " + key,
                "Integration", "Pings", null, null, null);
    }

    static ExternalModuleManifest manifest(List<ExternalModuleManifest.Hook> hooks,
                                           List<ExternalModuleManifest.Service> services,
                                           List<ExternalModuleManifest.Route> routes, boolean webhookEnabled) {
        return new ExternalModuleManifest(2, 1, MODULE, "Stub", "1.0.0", null, null, null,
                ExternalModuleManifest.Permissions.NONE, null, null,
                List.of(new ExternalModuleManifest.Action("ping", "Ping", null, null, null, 1500, null, null, null)),
                hooks, List.of(), services, routes, List.of(), webhookEnabled, null);
    }

    static ExternalModuleManifest manifest() {
        return manifest(List.of(), List.of(), List.of(), false);
    }

    static OutboundHttpClient.Response json(int status, String body) {
        return new OutboundHttpClient.Response(status, URI.create("https://stub.example.test"),
                Map.of("Content-Type", List.of("application/json")), body.getBytes(StandardCharsets.UTF_8), false);
    }

    /** A clock tests move by hand. */
    static final class MutableClock extends Clock {
        private Instant now = Instant.parse("2026-10-30T12:00:00Z");

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
