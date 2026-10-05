package io.kelta.gateway.geo;

import io.kelta.gateway.net.CidrBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * The single trust-aware client-IP resolver for the gateway. Every filter that needs
 * "who is the client" — audit logging, geo enrichment, rate limiting, rate-limit
 * exemption, the tenant IP allowlist and the {@value #CLIENT_IP_HEADER} header forwarded
 * downstream — asks this class; nothing else in the gateway parses
 * {@code X-Forwarded-For}.
 *
 * <p><b>Trusted-proxy mode</b> ({@code kelta.security.trusted-proxies} non-empty —
 * comma-separated CIDRs or bare addresses). Forwarding headers are only believed when
 * the socket peer is itself a trusted proxy:
 * <ul>
 *   <li>Peer not trusted → the peer address; {@code X-Forwarded-For} / {@code X-Real-IP}
 *       are ignored, so a client talking to the gateway directly cannot forge them.</li>
 *   <li>Peer trusted → walk {@code X-Forwarded-For} right to left, skipping trusted
 *       hops, and return the first untrusted one (the address the outermost trusted
 *       proxy actually saw). If every hop is trusted, the left-most. A hop that is not
 *       an address literal (a hostname, garbage) stops the walk and the nearest hop
 *       already verified is returned — nothing is ever resolved via DNS.</li>
 *   <li>Peer trusted but no {@code X-Forwarded-For} → {@code X-Real-IP} if it is a
 *       literal, else the peer.</li>
 * </ul>
 *
 * <p><b>Legacy mode</b> (list empty, the default): unchanged pre-PLT-341 behaviour. When
 * {@code kelta.gateway.ip-allowlist.trust-forwarded-for} is true the left-most
 * {@code X-Forwarded-For} hop is taken as the client — client-spoofable whenever the
 * edge proxy appends rather than replaces — otherwise the socket peer.
 */
@Component
public class ClientIpResolver {

    private static final Logger log = LoggerFactory.getLogger(ClientIpResolver.class);

    /**
     * Header the gateway stamps with the resolved client IP for downstream services. Any
     * client-supplied copy is stripped by {@code IdentityHeaderStripFilter}.
     */
    public static final String CLIENT_IP_HEADER = "X-Kelta-Client-Ip";

    private static final String X_FORWARDED_FOR = "X-Forwarded-For";
    private static final String X_REAL_IP = "X-Real-IP";

    private final boolean trustForwardedFor;
    private final List<CidrBlock> trustedProxies;

    public ClientIpResolver(boolean trustForwardedFor) {
        this(trustForwardedFor, List.of());
    }

    @Autowired
    public ClientIpResolver(
            @Value("${kelta.gateway.ip-allowlist.trust-forwarded-for:true}") boolean trustForwardedFor,
            @Value("${kelta.security.trusted-proxies:}") List<String> trustedProxies) {
        this.trustForwardedFor = trustForwardedFor;
        this.trustedProxies = parseTrustedProxies(trustedProxies);
        if (this.trustedProxies.isEmpty()) {
            log.info("Client IP resolution: legacy mode (kelta.security.trusted-proxies unset) — "
                    + "{}", trustForwardedFor
                    ? "left-most X-Forwarded-For hop trusted; client-spoofable"
                    : "socket peer only");
        } else {
            log.info("Client IP resolution: trusted-proxy mode — {} trusted range(s); "
                    + "forwarding headers honoured only from a trusted peer", this.trustedProxies.size());
        }
    }

    private static List<CidrBlock> parseTrustedProxies(List<String> entries) {
        if (entries == null) {
            return List.of();
        }
        List<CidrBlock> blocks = new ArrayList<>();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            CidrBlock block = CidrBlock.parseRangeOrHost(entry);
            if (block == null) {
                log.warn("Ignoring invalid kelta.security.trusted-proxies entry: '{}'", entry);
                continue;
            }
            blocks.add(block);
        }
        return List.copyOf(blocks);
    }

    /** True when {@code kelta.security.trusted-proxies} is configured. */
    public boolean isTrustedProxyMode() {
        return !trustedProxies.isEmpty();
    }

    /** Resolves the client IP for the request, or null when none is determinable. */
    public String resolve(ServerWebExchange exchange) {
        String peer = peerAddress(exchange);
        HttpHeaders headers = exchange.getRequest().getHeaders();
        if (isTrustedProxyMode()) {
            return resolveThroughTrustedProxies(peer, headers);
        }
        if (trustForwardedFor) {
            String xff = headers.getFirst(X_FORWARDED_FOR);
            if (xff != null && !xff.isBlank()) {
                String first = normalizeIp(xff.split(",")[0]);
                if (first != null) {
                    return first;
                }
            }
        }
        return peer;
    }

    /**
     * Every address the tenant IP allowlist should judge. In trusted-proxy mode that is
     * exactly {@link #resolve} — one answer, not spoofable. In legacy mode it is the
     * documented any-hop set (peer, plus every {@code X-Forwarded-For} hop and
     * {@code X-Real-IP} when {@code trust-forwarded-for} is on), kept so existing
     * deployments behave identically until the trusted-proxy list is set.
     */
    public List<String> allowlistCandidates(ServerWebExchange exchange) {
        List<String> ips = new ArrayList<>();
        if (isTrustedProxyMode()) {
            addDistinct(ips, resolve(exchange));
            return ips;
        }
        addDistinct(ips, peerAddress(exchange));
        if (trustForwardedFor) {
            HttpHeaders headers = exchange.getRequest().getHeaders();
            String xff = headers.getFirst(X_FORWARDED_FOR);
            if (xff != null && !xff.isBlank()) {
                for (String hop : xff.split(",")) {
                    addDistinct(ips, normalizeIp(hop));
                }
            }
            addDistinct(ips, normalizeIp(headers.getFirst(X_REAL_IP)));
        }
        return ips;
    }

    private String resolveThroughTrustedProxies(String peer, HttpHeaders headers) {
        if (peer == null || !isTrusted(peer)) {
            return peer;
        }
        List<String> hops = forwardedHops(headers);
        if (hops.isEmpty()) {
            String realIp = normalizeIp(headers.getFirst(X_REAL_IP));
            return CidrBlock.isAddressLiteral(realIp) ? realIp : peer;
        }
        String nearestVerified = peer;
        for (int i = hops.size() - 1; i >= 0; i--) {
            String hop = hops.get(i);
            if (!CidrBlock.isAddressLiteral(hop)) {
                return nearestVerified;
            }
            if (!isTrusted(hop)) {
                return hop;
            }
            nearestVerified = hop;
        }
        return nearestVerified;
    }

    /** All {@code X-Forwarded-For} hops in order, across repeated header lines. */
    private static List<String> forwardedHops(HttpHeaders headers) {
        List<String> values = headers.get(X_FORWARDED_FOR);
        List<String> hops = new ArrayList<>();
        if (values == null) {
            return hops;
        }
        for (String value : values) {
            if (value == null) {
                continue;
            }
            for (String raw : value.split(",")) {
                String hop = normalizeIp(raw);
                // An empty hop is still a hop: keep a placeholder so the walk stops on it.
                hops.add(hop == null ? "" : hop);
            }
        }
        if (hops.size() == 1 && hops.get(0).isEmpty()) {
            hops.clear();
        }
        return hops;
    }

    private boolean isTrusted(String ip) {
        for (CidrBlock block : trustedProxies) {
            if (block.contains(ip)) {
                return true;
            }
        }
        return false;
    }

    private static String peerAddress(ServerWebExchange exchange) {
        InetSocketAddress remote = exchange.getRequest().getRemoteAddress();
        if (remote != null && remote.getAddress() != null) {
            return normalizeIp(remote.getAddress().getHostAddress());
        }
        return null;
    }

    private static void addDistinct(List<String> ips, String ip) {
        if (ip != null && !ips.contains(ip)) {
            ips.add(ip);
        }
    }

    /**
     * Normalizes a raw IP token: trims, strips an IPv6 scope suffix ({@code %eth0}) and
     * surrounding brackets ({@code [::1]}). Returns null for blanks.
     */
    public static String normalizeIp(String raw) {
        if (raw == null) {
            return null;
        }
        String ip = raw.trim();
        if (ip.isEmpty()) {
            return null;
        }
        if (ip.startsWith("[")) {
            int close = ip.indexOf(']');
            if (close > 0) {
                ip = ip.substring(1, close);
            }
        }
        int pct = ip.indexOf('%');
        if (pct >= 0) {
            ip = ip.substring(0, pct);
        }
        return ip.isEmpty() ? null : ip;
    }
}
