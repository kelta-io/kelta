package io.kelta.gateway.ratelimit;

import io.kelta.gateway.geo.ClientIpResolver;
import io.kelta.gateway.net.CidrBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;

import java.util.ArrayList;
import java.util.List;

/**
 * Decides whether a client IP is exempt from rate limiting.
 *
 * <p>Configured as a list of IPs or CIDR ranges via
 * {@code kelta.gateway.rate-limit.exempt-cidrs} (env {@code RATE_LIMIT_EXEMPT_CIDRS}).
 * Intended for infrastructure whose traffic is trusted and bursty by nature —
 * uptime probes, in-cluster service-to-service calls, a payment processor's
 * webhook egress ranges, an office or VPN range during a load test.
 *
 * <p>Ranges are parsed <b>once at construction</b>; a malformed entry is logged
 * and skipped rather than throwing, so one typo cannot stop the gateway booting.
 * An empty list (the default) means nothing is exempt and every limiter behaves
 * exactly as it did before.
 *
 * <p><b>Security note.</b> A bare IP is accepted and treated as a single-host
 * range ({@code /32} or {@code /128}). Exemption is evaluated against the
 * <em>same</em> resolved client IP the limiter would key on, so an exempt entry
 * is only as trustworthy as {@link ClientIpResolver}'s answer: with
 * {@code kelta.security.trusted-proxies} unset and {@code trust-forwarded-for}
 * enabled that answer is a client-supplied header — keep the list to ranges you
 * control, and prefer narrow prefixes over broad ones.
 *
 * @since 1.0.0
 */
@Component
public class RateLimitExemptionService {

    private static final Logger log = LoggerFactory.getLogger(RateLimitExemptionService.class);

    private final ClientIpResolver clientIpResolver;
    private final List<CidrBlock> exemptBlocks;
    private final List<String> configured;

    public RateLimitExemptionService(
            ClientIpResolver clientIpResolver,
            @Value("${kelta.gateway.rate-limit.exempt-cidrs:}") List<String> exemptCidrs) {
        this.clientIpResolver = clientIpResolver;
        // Filter before copying: List.copyOf rejects null elements, and a null
        // must be skipped like any other invalid entry rather than fail startup.
        this.configured = exemptCidrs == null
                ? List.of()
                : exemptCidrs.stream()
                        .filter(e -> e != null && !e.isBlank())
                        .map(String::trim)
                        .toList();
        this.exemptBlocks = parse(this.configured);
        if (exemptBlocks.isEmpty()) {
            log.info("Rate-limit exemption list is empty — all clients are rate limited");
        } else {
            log.info("Rate-limit exemption list active: {} range(s) {}",
                    exemptBlocks.size(), this.configured);
        }
    }

    private static List<CidrBlock> parse(List<String> entries) {
        List<CidrBlock> blocks = new ArrayList<>();
        for (String entry : entries) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            CidrBlock block = CidrBlock.parseRangeOrHost(entry);
            if (block == null) {
                log.warn("Ignoring invalid rate-limit exemption entry: '{}'", entry);
                continue;
            }
            blocks.add(block);
        }
        return List.copyOf(blocks);
    }

    /** True when {@code ip} falls inside any configured exempt range. */
    public boolean isExempt(String ip) {
        if (ip == null || exemptBlocks.isEmpty()) {
            return false;
        }
        String normalized = ClientIpResolver.normalizeIp(ip);
        if (normalized == null) {
            return false;
        }
        for (CidrBlock block : exemptBlocks) {
            if (block.contains(normalized)) {
                return true;
            }
        }
        return false;
    }

    /**
     * True when the request's resolved client IP is exempt. Uses the same
     * trust-aware resolver the limiters key on, so exemption and limiting always
     * agree on who the client is.
     */
    public boolean isExempt(ServerWebExchange exchange) {
        if (exemptBlocks.isEmpty()) {
            return false;
        }
        return isExempt(clientIpResolver.resolve(exchange));
    }

    /** Configured entries, for diagnostics. */
    public List<String> getConfiguredRanges() {
        return configured;
    }
}
