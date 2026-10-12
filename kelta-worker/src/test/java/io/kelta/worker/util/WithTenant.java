package io.kelta.worker.util;

import org.junit.jupiter.api.extension.ExtendWith;

import java.lang.annotation.ElementType;
import java.lang.annotation.Inherited;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Binds {@link io.kelta.runtime.context.TenantContext} around a test, the way the worker's
 * {@code TenantContextFilter} binds it around a request.
 *
 * <p>Tests used to call the ThreadLocal setters ({@code TenantContext.set}/{@code clear}) in
 * {@code @BeforeEach}/{@code @AfterEach}; those are gone, and a {@link ScopedValue} binding cannot
 * span separate lifecycle callbacks, so {@link TenantScopeExtension} wraps each invocation
 * ({@code @BeforeEach}, the test method, {@code @AfterEach}) in its own scope instead.
 *
 * <p>Put it on the class for every test, or on a method to override the class value. A method
 * that must run with no tenant bound uses {@code @WithTenant(none = true)}.
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
@Inherited
@ExtendWith(TenantScopeExtension.class)
public @interface WithTenant {

    /** Tenant id to bind. */
    String value() default "";

    /** Tenant slug to bind; empty leaves the slug unbound. */
    String slug() default "";

    /** Run with no tenant bound, overriding a class-level binding. */
    boolean none() default false;
}
