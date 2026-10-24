package io.kelta.runtime.router;

import io.kelta.runtime.context.GeoStamp;
import io.kelta.runtime.model.CollectionDefinition;

import java.util.Map;
import java.util.Optional;

/**
 * The record geo-stamp rule shared by {@link RecordWritePipeline} and {@link AtomicOperationExecutor},
 * so a write through {@code /api/{collection}} and one through {@code /api/operations} stamp the same.
 */
final class GeoStamping {

    private GeoStamping() {
    }

    /**
     * Stamps the request-origin geolocation into {@code createdGeo}/{@code updatedGeo} for
     * collections with {@code captureGeo} enabled (never system collections). Stamps null when the
     * origin has no geolocation — on update this intentionally clears a previous stamp, so the
     * value always describes the origin of the LAST write, never a stale one.
     */
    static void stamp(Map<String, Object> data, CollectionDefinition definition,
                      Optional<GeoStamp> geo, boolean isCreate) {
        if (!definition.captureGeo() || definition.systemCollection()) {
            return;
        }
        Map<String, Object> value = geo.map(GeoStamp::toMap).orElse(null);
        if (isCreate) {
            data.put("createdGeo", value);
        }
        data.put("updatedGeo", value);
    }
}
