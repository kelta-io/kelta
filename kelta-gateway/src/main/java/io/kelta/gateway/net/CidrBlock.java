package io.kelta.gateway.net;

import java.net.InetAddress;

/**
 * A parsed CIDR block with dependency-free, DNS-free containment checking.
 *
 * <p>Uses {@link InetAddress#ofLiteral(String)} (JDK 22+) rather than
 * {@code getByName}, so an attacker-supplied forwarded IP is parsed as a literal
 * and is <b>never</b> resolved via DNS — a hostname in an {@code X-Forwarded-For}
 * header cannot turn a containment check into an outbound lookup.
 *
 * <p>Shared by the per-tenant IP allowlist and the rate-limit exemption list;
 * both must agree on what "inside this range" means, so there is exactly one
 * implementation.
 *
 * @since 1.0.0
 */
public record CidrBlock(byte[] network, int prefixLen) {

    /**
     * Parses {@code a.b.c.d/len} or an IPv6 equivalent. Returns {@code null} for
     * anything malformed — callers log and skip rather than throwing, so one bad
     * entry in a config list can never take a filter down.
     */
    public static CidrBlock parse(String cidr) {
        if (cidr == null) {
            return null;
        }
        String s = cidr.trim();
        int slash = s.indexOf('/');
        if (slash <= 0 || slash == s.length() - 1) {
            return null;
        }
        byte[] network;
        int prefixLen;
        try {
            network = InetAddress.ofLiteral(s.substring(0, slash)).getAddress();
            prefixLen = Integer.parseInt(s.substring(slash + 1).trim());
        } catch (RuntimeException e) {
            return null;
        }
        if (prefixLen < 0 || prefixLen > network.length * 8) {
            return null;
        }
        return new CidrBlock(network, prefixLen);
    }

    /**
     * Like {@link #parse(String)}, but also accepts a bare address as a single-host
     * range ({@code /32} or {@code /128}) so operators can write {@code 203.0.113.7}
     * instead of {@code 203.0.113.7/32}.
     */
    public static CidrBlock parseRangeOrHost(String entry) {
        if (entry == null || entry.isBlank()) {
            return null;
        }
        String trimmed = entry.trim();
        String normalized = trimmed.indexOf('/') >= 0
                ? trimmed
                : trimmed + (trimmed.indexOf(':') >= 0 ? "/128" : "/32");
        return parse(normalized);
    }

    /**
     * True when {@code ip} is an IPv4/IPv6 address literal. Never resolves DNS: a
     * hostname or any other garbage is simply not a literal.
     */
    public static boolean isAddressLiteral(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        try {
            InetAddress.ofLiteral(ip);
            return true;
        } catch (RuntimeException e) {
            return false;
        }
    }

    /** True when {@code ip} (an address literal) falls inside this block. */
    public boolean contains(String ip) {
        if (ip == null) {
            return false;
        }
        byte[] target;
        try {
            target = InetAddress.ofLiteral(ip).getAddress();
        } catch (RuntimeException e) {
            return false;
        }
        if (target.length != network.length) {
            return false; // address-family mismatch (IPv4 vs IPv6)
        }
        int fullBytes = prefixLen / 8;
        for (int i = 0; i < fullBytes; i++) {
            if (network[i] != target[i]) {
                return false;
            }
        }
        int remBits = prefixLen % 8;
        if (remBits > 0) {
            int mask = (0xFF << (8 - remBits)) & 0xFF;
            return (network[fullBytes] & mask) == (target[fullBytes] & mask);
        }
        return true;
    }
}
