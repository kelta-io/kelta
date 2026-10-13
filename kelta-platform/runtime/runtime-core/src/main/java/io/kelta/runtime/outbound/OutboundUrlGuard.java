package io.kelta.runtime.outbound;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Decides whether a service may fetch a URL that a tenant administrator supplied — the guard
 * against server-side request forgery for any outbound call whose target comes from tenant
 * configuration rather than from the platform. Plain Java: each service builds one from its
 * {@value #ALLOWED_CIDRS_PROPERTY} property (env {@code KELTA_OUTBOUND_ALLOWED_PRIVATE_CIDRS}),
 * and fetches normally go through {@link OutboundHttpClient}, which applies it to every hop.
 *
 * <p>A URL passes when it is an absolute {@code http}/{@code https} URL without user-info, and
 * <em>every</em> address its host resolves to is publicly routable. Loopback, unspecified,
 * link-local (including the {@code 169.254.169.254} cloud metadata address), private
 * (RFC 1918), carrier-grade NAT, IPv6 unique-local, multicast and reserved ranges are refused,
 * including when they are embedded in an IPv6 address (IPv4-mapped, NAT64, 6to4).
 *
 * <p>An operator whose legitimate targets live on a private network (a self-hosted identity
 * provider, say) lists those ranges in {@value #ALLOWED_CIDRS_PROPERTY} (comma-separated CIDRs or
 * bare addresses). Nothing is allowed by default.
 *
 * <p>A caller that fetches without {@link OutboundHttpClient} must itself re-check every
 * redirect hop (a redirect to a private address would bypass this check) and bound the request
 * with timeouts and a response-size limit. The check resolves the host itself; see
 * {@link OutboundHttpClient} for how that relates to the address the connection uses.
 */
public class OutboundUrlGuard {

    /** Comma-separated CIDRs (or bare addresses) that are reachable despite being non-public. */
    public static final String ALLOWED_CIDRS_PROPERTY = "kelta.outbound.allowed-private-cidrs";

    private final List<Cidr> allowedRanges;

    /**
     * @param allowedPrivateCidrs comma-separated CIDRs or bare addresses that are allowed even
     *                            though they are not public; blank allows none
     * @throws IllegalArgumentException when an entry is not an IP address or CIDR
     */
    public OutboundUrlGuard(String allowedPrivateCidrs) {
        this.allowedRanges = parseRanges(allowedPrivateCidrs);
    }

    /**
     * Returns {@code url} as a {@link URI} when it may be fetched.
     *
     * @throws RejectedUrlException with a caller-safe reason when it may not
     */
    public URI check(String url) {
        if (url == null || url.isBlank()) {
            throw new RejectedUrlException(Reason.INVALID_URL, "no URL");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception e) {
            throw new RejectedUrlException(Reason.INVALID_URL, "not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw new RejectedUrlException(Reason.INVALID_URL, "only http and https URLs are allowed");
        }
        if (uri.getRawUserInfo() != null) {
            throw new RejectedUrlException(Reason.INVALID_URL, "URLs with credentials are not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new RejectedUrlException(Reason.INVALID_URL, "the URL has no host");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new RejectedUrlException(Reason.UNRESOLVABLE, "the host does not resolve");
        }
        for (InetAddress address : addresses) {
            if (isNonPublic(address) && !isAllowed(address)) {
                throw new RejectedUrlException(Reason.NON_PUBLIC_ADDRESS, "the host resolves to a non-public address");
            }
        }
        return uri;
    }

    private boolean isAllowed(InetAddress address) {
        for (Cidr range : allowedRanges) {
            if (range.contains(address)) {
                return true;
            }
        }
        return false;
    }

    /** True for any address that is not publicly routable. */
    static boolean isNonPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        if (address instanceof Inet4Address) {
            return isNonPublicV4(b);
        }
        if (address instanceof Inet6Address) {
            if ((b[0] & 0xFE) == 0xFC) {
                return true; // fc00::/7 unique-local
            }
            if (isNat64(b)) {
                return isNonPublicV4(new byte[] {b[12], b[13], b[14], b[15]});
            }
            if ((b[0] & 0xFF) == 0x20 && (b[1] & 0xFF) == 0x02) {
                return isNonPublicV4(new byte[] {b[2], b[3], b[4], b[5]}); // 2002::/16 6to4
            }
            if (isAllZero(b, 0, 12)) {
                return true; // ::/96 IPv4-compatible (deprecated) — never a legitimate target
            }
        }
        return false;
    }

    private static boolean isNonPublicV4(byte[] b) {
        int b0 = b[0] & 0xFF;
        int b1 = b[1] & 0xFF;
        int b2 = b[2] & 0xFF;
        return b0 == 0                                  // 0.0.0.0/8
                || b0 == 10                             // 10.0.0.0/8
                || b0 == 127                            // 127.0.0.0/8
                || (b0 == 100 && (b1 & 0xC0) == 64)     // 100.64.0.0/10 carrier-grade NAT
                || (b0 == 169 && b1 == 254)             // 169.254.0.0/16 link-local, cloud metadata
                || (b0 == 172 && (b1 & 0xF0) == 16)     // 172.16.0.0/12
                || (b0 == 192 && b1 == 168)             // 192.168.0.0/16
                || (b0 == 192 && b1 == 0 && b2 == 0)    // 192.0.0.0/24 protocol assignments
                || (b0 == 198 && (b1 & 0xFE) == 18)     // 198.18.0.0/15 benchmarking
                || b0 >= 224;                           // multicast, reserved, broadcast
    }

    private static boolean isNat64(byte[] b) {
        // 64:ff9b::/96 — the well-known NAT64 prefix embeds an IPv4 address in the last 4 bytes
        return (b[0] & 0xFF) == 0x00 && (b[1] & 0xFF) == 0x64 && (b[2] & 0xFF) == 0xFF
                && (b[3] & 0xFF) == 0x9B && isAllZero(b, 4, 12);
    }

    private static boolean isAllZero(byte[] b, int from, int to) {
        for (int i = from; i < to; i++) {
            if (b[i] != 0) {
                return false;
            }
        }
        return true;
    }

    private static List<Cidr> parseRanges(String raw) {
        List<Cidr> ranges = new ArrayList<>();
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        for (String entry : raw.split(",")) {
            String trimmed = entry.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            ranges.add(Cidr.parse(trimmed));
        }
        return List.copyOf(ranges);
    }

    /** An address range. Parsed from literals only — never resolves DNS. */
    record Cidr(byte[] network, int prefixLength) {

        static Cidr parse(String entry) {
            int slash = entry.indexOf('/');
            String address = slash < 0 ? entry : entry.substring(0, slash);
            byte[] network;
            try {
                network = InetAddress.ofLiteral(address.trim()).getAddress();
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        ALLOWED_CIDRS_PROPERTY + ": not an IP address or CIDR: '" + entry + "'", e);
            }
            int prefix;
            try {
                prefix = slash < 0 ? network.length * 8 : Integer.parseInt(entry.substring(slash + 1).trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(
                        ALLOWED_CIDRS_PROPERTY + ": invalid prefix length in '" + entry + "'", e);
            }
            if (prefix < 0 || prefix > network.length * 8) {
                throw new IllegalArgumentException(
                        ALLOWED_CIDRS_PROPERTY + ": invalid prefix length in '" + entry + "'");
            }
            return new Cidr(network, prefix);
        }

        boolean contains(InetAddress address) {
            byte[] candidate = address.getAddress();
            if (candidate.length != network.length) {
                return false;
            }
            int fullBytes = prefixLength / 8;
            for (int i = 0; i < fullBytes; i++) {
                if (candidate[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xFF << (8 - remainingBits)) & 0xFF;
            return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
        }
    }

    /** Why a URL was refused. */
    public enum Reason {
        /** Malformed, not http(s), carries credentials, or has no host. */
        INVALID_URL,
        /** The host has no address — not a policy decision, the target simply does not exist. */
        UNRESOLVABLE,
        /** The host resolves to an address outside the public internet that is not allowed. */
        NON_PUBLIC_ADDRESS
    }

    /** The URL may not be fetched; the message is safe to log and to show an administrator. */
    public static final class RejectedUrlException extends RuntimeException {
        private final Reason reason;

        public RejectedUrlException(Reason reason, String message) {
            super(message);
            this.reason = reason;
        }

        public Reason reason() {
            return reason;
        }
    }
}
