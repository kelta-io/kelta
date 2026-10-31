package io.kelta.worker.module;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The SHA-256 the module code compares: plan hashes, provisioned-resource content hashes and pinned
 * UI bundles. Hex-encoded, lower case.
 */
final class ModuleHashes {

    private ModuleHashes() {
    }

    /** Hex-encoded SHA-256 of {@code data}. */
    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }
}
