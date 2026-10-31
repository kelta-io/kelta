package io.kelta.runtime.module;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A semantic version ({@code MAJOR.MINOR.PATCH[-PRERELEASE][+BUILD]}, semver.org 2.0.0), as used
 * by a module's {@code version} and {@code minPlatformVersion}.
 *
 * <p>Ordering follows semver precedence: numeric core first, a pre-release sorts before its
 * release, pre-release identifiers compare numerically when both are numeric and lexically
 * otherwise, and build metadata is ignored. A leading {@code v} is accepted ({@code v0.4.0}),
 * because release tags carry one.
 *
 * @param major      major version
 * @param minor      minor version
 * @param patch      patch version
 * @param preRelease dot-separated pre-release identifiers, or {@code null}
 * @since 1.0.0
 */
public record ModuleVersion(int major, int minor, int patch, String preRelease)
        implements Comparable<ModuleVersion> {

    private static final Pattern SEMVER = Pattern.compile(
            "^v?(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})\\.(0|[1-9]\\d{0,8})"
                    + "(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?"
                    + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$");

    /**
     * Parses a version string.
     *
     * @throws IllegalArgumentException when {@code value} is not a semantic version
     */
    public static ModuleVersion parse(String value) {
        if (value == null) {
            throw new IllegalArgumentException("version is required");
        }
        Matcher m = SEMVER.matcher(value.trim());
        if (!m.matches()) {
            throw new IllegalArgumentException("not a semantic version (MAJOR.MINOR.PATCH): " + value);
        }
        return new ModuleVersion(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)),
                Integer.parseInt(m.group(3)), m.group(4));
    }

    /** True when {@code value} parses. */
    public static boolean isValid(String value) {
        try {
            parse(value);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public int compareTo(ModuleVersion other) {
        int c = Integer.compare(major, other.major);
        if (c == 0) {
            c = Integer.compare(minor, other.minor);
        }
        if (c == 0) {
            c = Integer.compare(patch, other.patch);
        }
        if (c != 0) {
            return c;
        }
        if (Objects.equals(preRelease, other.preRelease)) {
            return 0;
        }
        // A release outranks any of its pre-releases.
        if (preRelease == null) {
            return 1;
        }
        if (other.preRelease == null) {
            return -1;
        }
        String[] a = preRelease.split("\\.");
        String[] b = other.preRelease.split("\\.");
        for (int i = 0; i < Math.min(a.length, b.length); i++) {
            c = compareIdentifier(a[i], b[i]);
            if (c != 0) {
                return c;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    private static int compareIdentifier(String a, String b) {
        boolean aNumeric = a.chars().allMatch(Character::isDigit);
        boolean bNumeric = b.chars().allMatch(Character::isDigit);
        if (aNumeric && bNumeric) {
            return new java.math.BigInteger(a).compareTo(new java.math.BigInteger(b));
        }
        if (aNumeric != bNumeric) {
            return aNumeric ? -1 : 1; // numeric identifiers sort before alphanumeric ones
        }
        return a.compareTo(b);
    }

    @Override
    public String toString() {
        return major + "." + minor + "." + patch + (preRelease == null ? "" : "-" + preRelease);
    }
}
