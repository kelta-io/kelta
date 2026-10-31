package io.kelta.runtime.outbound;

import java.net.URI;

/**
 * Decides whether an outbound request may be sent to a URL. {@link OutboundHttpClient} applies it
 * to every hop. {@link OutboundUrlGuard} is the guard for tenant-supplied URLs in general;
 * {@link ModuleHostGuard} is the separate one for external-module traffic.
 */
public interface OutboundTargetGuard {

    /**
     * Returns {@code url} as a {@link URI} when it may be fetched.
     *
     * @throws OutboundUrlGuard.RejectedUrlException with a caller-safe reason when it may not
     */
    URI check(String url);
}
