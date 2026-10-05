package io.kelta.gateway.authz.cerbos;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * A gRPC {@link Channel} to Cerbos whose underlying {@link ManagedChannel} can be reset
 * or replaced without rebuilding the {@code CerbosBlockingClient} that sits on top of it.
 *
 * <ul>
 *   <li>{@link #resetConnection()} — drop the current transport; the next call dials a
 *       fresh connection. In-flight calls on the old transport are allowed to finish.</li>
 *   <li>{@link #rebuild()} — replace the whole {@link ManagedChannel} (name resolver,
 *       load balancer, subchannels) and gracefully shut the old one down.</li>
 * </ul>
 */
public class CerbosChannel extends Channel {

    private static final Logger log = LoggerFactory.getLogger(CerbosChannel.class);

    private final Supplier<ManagedChannel> factory;
    private final AtomicReference<ManagedChannel> delegate;

    public CerbosChannel(Supplier<ManagedChannel> factory) {
        this.factory = factory;
        this.delegate = new AtomicReference<>(factory.get());
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(MethodDescriptor<ReqT, RespT> method,
                                                         CallOptions callOptions) {
        return delegate.get().newCall(method, callOptions);
    }

    @Override
    public String authority() {
        return delegate.get().authority();
    }

    public void resetConnection() {
        delegate.get().enterIdle();
    }

    public void rebuild() {
        ManagedChannel previous = delegate.getAndSet(factory.get());
        previous.shutdown();
        log.info("Rebuilt Cerbos gRPC channel to {}", authority());
    }

    public void shutdown() {
        ManagedChannel current = delegate.get();
        current.shutdown();
        try {
            if (!current.awaitTermination(2, TimeUnit.SECONDS)) {
                current.shutdownNow();
            }
        } catch (InterruptedException e) {
            current.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
