package io.kelta.gateway.filter;

import io.kelta.gateway.cache.GatewayCacheManager;
import org.reactivestreams.Publisher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferFactory;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Store half of the system-collection response cache: captures the worker's 200 response for a
 * request {@link SystemCollectionResponseCacheFilter} looked up and missed, and caches it under
 * the key that filter recorded on the exchange.
 *
 * <p>It is a separate filter because of ordering. Spring Cloud Gateway's
 * {@code NettyWriteResponseFilter} (order -1) writes the routed response to the response of the
 * exchange <em>it</em> received, so a response decorator only sees the body when it is installed
 * by a filter ordered before it. The lookup has to run after route authorization (order 0). This
 * filter therefore only installs the decorator, and the decorator caches nothing unless the
 * lookup ran, missed and recorded a key — a request refused by route authorization, or answered
 * from the cache, is never stored.
 */
@Component
public class SystemCollectionResponseCaptureFilter implements GlobalFilter, Ordered {

    private static final Logger log = LoggerFactory.getLogger(SystemCollectionResponseCaptureFilter.class);

    /** Before {@code NettyWriteResponseFilter} (-1), so the decorator sees the routed body. */
    static final int ORDER = -10;

    private final GatewayCacheManager cacheManager;

    public SystemCollectionResponseCaptureFilter(GatewayCacheManager cacheManager) {
        this.cacheManager = cacheManager;
    }

    @Override
    public int getOrder() {
        return ORDER;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        if (exchange.getRequest().getMethod() != HttpMethod.GET) {
            return chain.filter(exchange);
        }
        String collectionName = SystemCollectionResponseCacheFilter.extractCollectionName(
                exchange.getRequest().getPath().value());
        if (collectionName == null
                || !SystemCollectionResponseCacheFilter.CACHEABLE_COLLECTIONS.contains(collectionName)) {
            return chain.filter(exchange);
        }

        ServerHttpResponse originalResponse = exchange.getResponse();
        DataBufferFactory bufferFactory = originalResponse.bufferFactory();

        ServerHttpResponseDecorator decoratedResponse = new ServerHttpResponseDecorator(originalResponse) {
            @Override
            public Mono<Void> writeWith(Publisher<? extends DataBuffer> body) {
                // Attributes are shared by every mutated copy of the exchange, so the key the
                // lookup recorded downstream is visible here.
                String cacheKey = exchange.getAttribute(SystemCollectionResponseCacheFilter.CACHE_KEY_ATTR);
                HttpStatus statusCode = HttpStatus.resolve(
                        getDelegate().getStatusCode() != null
                                ? getDelegate().getStatusCode().value() : 0);

                if (cacheKey == null || statusCode != HttpStatus.OK) {
                    return super.writeWith(body);
                }

                return DataBufferUtils.join(Flux.from(body))
                        .flatMap(dataBuffer -> {
                            byte[] content = new byte[dataBuffer.readableByteCount()];
                            dataBuffer.read(content);
                            DataBufferUtils.release(dataBuffer);

                            cacheManager.putSystemCollectionResponse(cacheKey, content);
                            log.debug("Gateway cached response for system collection: {} ({} bytes)",
                                    collectionName, content.length);

                            return getDelegate().writeWith(
                                    Mono.just(bufferFactory.wrap(content)));
                        });
            }
        };

        return chain.filter(exchange.mutate().response(decoratedResponse).build());
    }
}
