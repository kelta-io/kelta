package io.kelta.runtime.outbound;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The outbound guard for <b>external-module</b> traffic: the manifest fetch and every request the
 * platform sends to a module ({@code specs/module-platform/9-external-modules.md} §3.10).
 *
 * <p>Module base URLs are typed by tenant admins, and modules run in-cluster resolve to private
 * addresses. The general {@link OutboundUrlGuard} would refuse those unless
 * {@code KELTA_OUTBOUND_ALLOWED_PRIVATE_CIDRS} admitted the cluster ranges — and that variable also
 * governs every flow {@code HTTP_CALLOUT}, so widening it would let any flow reach internal
 * services. Module traffic therefore has its own allow-list, {@value #ALLOWED_HOSTS_PROPERTY}
 * (env {@code KELTA_MODULE_ALLOWED_HOSTS}): comma-separated hostnames, or {@code *.suffix}
 * patterns, that may resolve to private addresses <em>for module calls only</em>. This guard never
 * reads the general CIDR allow-list.
 *
 * <ul>
 *   <li>A host <b>not</b> on the list must use {@code https} and resolve only to public addresses,
 *       exactly as {@link OutboundUrlGuard} with no allowed ranges.</li>
 *   <li>A host <b>on</b> the list may use {@code http} and resolve to private or loopback
 *       addresses, but never to a link-local (the {@code 169.254.169.254} cloud metadata service,
 *       {@code fe80::/10}), unspecified or multicast one.</li>
 * </ul>
 *
 * <p>The check resolves the host on every call, so a caller that runs it before each request
 * re-checks DNS that changed after install. It follows nothing: callers send module requests with
 * redirects off and treat a redirect as a failure.
 */
public class ModuleHostGuard implements OutboundTargetGuard {

    /** Comma-separated hostnames or {@code *.suffix} patterns allowed to resolve privately. */
    public static final String ALLOWED_HOSTS_PROPERTY = "kelta.modules.allowed-hosts";

    private static final Pattern HOSTNAME = Pattern.compile("^[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)*$");

    private final List<String> exactHosts;
    private final List<String> suffixes;

    /**
     * @param allowedHosts comma-separated hostnames or {@code *.suffix} patterns; blank allows none
     * @throws IllegalArgumentException when an entry is neither
     */
    public ModuleHostGuard(String allowedHosts) {
        List<String> exact = new ArrayList<>();
        List<String> wildcard = new ArrayList<>();
        if (allowedHosts != null) {
            for (String raw : allowedHosts.split(",")) {
                String entry = raw.trim().toLowerCase(Locale.ROOT);
                if (entry.isEmpty()) {
                    continue;
                }
                boolean isWildcard = entry.startsWith("*.");
                String name = isWildcard ? entry.substring(2) : entry;
                if (!HOSTNAME.matcher(name).matches()) {
                    throw new IllegalArgumentException(ALLOWED_HOSTS_PROPERTY
                            + ": not a hostname or *.suffix pattern: '" + raw.trim() + "'");
                }
                (isWildcard ? wildcard : exact).add(name);
            }
        }
        this.exactHosts = List.copyOf(exact);
        this.suffixes = List.copyOf(wildcard);
    }

    /** True when {@code host} is on the allow-list. */
    public boolean isAllowListed(String host) {
        if (host == null) {
            return false;
        }
        String h = host.toLowerCase(Locale.ROOT);
        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }
        if (exactHosts.contains(h)) {
            return true;
        }
        for (String suffix : suffixes) {
            if (h.endsWith("." + suffix)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public URI check(String url) {
        if (url == null || url.isBlank()) {
            throw rejected(OutboundUrlGuard.Reason.INVALID_URL, "no URL");
        }
        URI uri;
        try {
            uri = new URI(url.trim());
        } catch (Exception e) {
            throw rejected(OutboundUrlGuard.Reason.INVALID_URL, "not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("https") && !scheme.equals("http")) {
            throw rejected(OutboundUrlGuard.Reason.INVALID_URL, "only http and https URLs are allowed");
        }
        if (uri.getRawUserInfo() != null) {
            throw rejected(OutboundUrlGuard.Reason.INVALID_URL, "URLs with credentials are not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw rejected(OutboundUrlGuard.Reason.INVALID_URL, "the URL has no host");
        }
        boolean allowListed = isAllowListed(host);
        if (!allowListed && !scheme.equals("https")) {
            throw rejected(OutboundUrlGuard.Reason.INVALID_URL,
                    "a module URL must use https unless its host is in KELTA_MODULE_ALLOWED_HOSTS");
        }

        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw rejected(OutboundUrlGuard.Reason.UNRESOLVABLE, "the host does not resolve");
        }
        for (InetAddress address : addresses) {
            if (allowListed ? isNeverReachable(address) : OutboundUrlGuard.isNonPublic(address)) {
                throw rejected(OutboundUrlGuard.Reason.NON_PUBLIC_ADDRESS, allowListed
                        ? "the host resolves to a link-local, unspecified or multicast address"
                        : "the host resolves to a non-public address and is not in KELTA_MODULE_ALLOWED_HOSTS");
            }
        }
        return uri;
    }

    /** Addresses refused even for an allow-listed host: metadata, unspecified, multicast. */
    static boolean isNeverReachable(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLinkLocalAddress() || address.isMulticastAddress()) {
            return true;
        }
        byte[] b = address.getAddress();
        // 169.254.0.0/16 is link-local and isLinkLocalAddress covers it; 0.0.0.0/8 is "this network".
        return b.length == 4 && (b[0] & 0xFF) == 0;
    }

    private static OutboundUrlGuard.RejectedUrlException rejected(OutboundUrlGuard.Reason reason, String message) {
        return new OutboundUrlGuard.RejectedUrlException(reason, message);
    }
}
