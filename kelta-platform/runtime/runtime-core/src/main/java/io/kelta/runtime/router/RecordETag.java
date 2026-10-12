package io.kelta.runtime.router;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;

/**
 * The optimistic-locking version token (unified record experience, slice 5): emitted as
 * {@code ETag} on get-by-id and checked against {@code If-Match} on top-level update/delete by
 * {@link RecordWritePipeline}.
 */
final class RecordETag {

    private RecordETag() {
    }

    /**
     * Derives an opaque optimistic-locking ETag from a record's {@code updatedAt} version. Returns
     * {@code null} when the record has no version (nothing to lock against). The token is
     * base64-encoded so it is header-safe and collision-free for a single record's timeline; the
     * client treats it as opaque and echoes it back via {@code If-Match}.
     */
    static String of(Map<String, Object> record) {
        Object updatedAt = record.get("updatedAt");
        if (updatedAt == null) {
            return null;
        }
        String token = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(String.valueOf(updatedAt).getBytes(StandardCharsets.UTF_8));
        return "\"" + token + "\"";
    }
}
