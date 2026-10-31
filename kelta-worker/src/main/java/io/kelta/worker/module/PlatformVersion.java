package io.kelta.worker.module;

import io.kelta.runtime.module.ModuleVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * The running Kelta release, from {@code kelta.platform.version} ({@code KELTA_VERSION}), which a
 * module's {@code minPlatformVersion} is checked against at install and upgrade
 * ({@code specs/module-platform/3-lifecycle.md} §3.2). The Maven project version is
 * {@code 1.0.0-SNAPSHOT} for every build and says nothing; the variable that picks the release
 * image is what the container knows.
 *
 * <p>Blank or unparseable means unknown: the check is skipped with a WARN, never failed, so a
 * deployment that does not set the variable can still install modules.
 */
@Component
public class PlatformVersion {

    private static final Logger log = LoggerFactory.getLogger(PlatformVersion.class);

    private final ModuleVersion current;

    public PlatformVersion(@Value("${kelta.platform.version:}") String version) {
        ModuleVersion parsed = null;
        if (version != null && !version.isBlank()) {
            try {
                parsed = ModuleVersion.parse(version.strip());
            } catch (IllegalArgumentException e) {
                log.warn("kelta.platform.version '{}' is not a semantic version; module "
                        + "minPlatformVersion checks are skipped", version);
            }
        }
        this.current = parsed;
    }

    /** The running version, or empty when unknown. */
    public Optional<ModuleVersion> current() {
        return Optional.ofNullable(current);
    }

    /** The version, or {@code unknown}. */
    public String describe() {
        return current == null ? "unknown" : current.toString();
    }
}
