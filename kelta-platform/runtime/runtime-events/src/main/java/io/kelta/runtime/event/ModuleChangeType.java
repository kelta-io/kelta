package io.kelta.runtime.event;

/**
 * Types of module lifecycle changes.
 *
 * @since 1.0.0
 */
public enum ModuleChangeType {
    INSTALLED,
    ENABLED,
    DISABLED,
    UNINSTALLED,
    /** An external module moved to a higher version in place (part 9c). */
    UPGRADED
}
