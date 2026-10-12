package io.kelta.worker.util;

import io.kelta.runtime.context.TenantContext;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.InvocationInterceptor;
import org.junit.jupiter.api.extension.ReflectiveInvocationContext;
import org.junit.platform.commons.support.AnnotationSupport;

import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Runs {@code @BeforeEach}, the test method and {@code @AfterEach} inside the
 * {@link TenantContext} scope declared by {@link WithTenant}. See that annotation.
 */
public class TenantScopeExtension implements InvocationInterceptor {

    @Override
    public void interceptTestMethod(Invocation<Void> invocation,
                                    ReflectiveInvocationContext<Method> invocationContext,
                                    ExtensionContext extensionContext) throws Throwable {
        proceedInScope(invocation, extensionContext);
    }

    @Override
    public void interceptTestTemplateMethod(Invocation<Void> invocation,
                                            ReflectiveInvocationContext<Method> invocationContext,
                                            ExtensionContext extensionContext) throws Throwable {
        proceedInScope(invocation, extensionContext);
    }

    @Override
    public void interceptBeforeEachMethod(Invocation<Void> invocation,
                                          ReflectiveInvocationContext<Method> invocationContext,
                                          ExtensionContext extensionContext) throws Throwable {
        proceedInScope(invocation, extensionContext);
    }

    @Override
    public void interceptAfterEachMethod(Invocation<Void> invocation,
                                         ReflectiveInvocationContext<Method> invocationContext,
                                         ExtensionContext extensionContext) throws Throwable {
        proceedInScope(invocation, extensionContext);
    }

    private static void proceedInScope(Invocation<Void> invocation, ExtensionContext context)
            throws Throwable {
        Optional<WithTenant> binding = resolve(context);
        if (binding.isEmpty() || binding.get().none() || binding.get().value().isEmpty()) {
            invocation.proceed();
            return;
        }
        WithTenant tenant = binding.get();
        Throwable[] failure = new Throwable[1];
        Runnable body = () -> {
            try {
                invocation.proceed();
            } catch (Throwable t) {
                failure[0] = t;
            }
        };
        if (tenant.slug().isEmpty()) {
            TenantContext.runWithTenant(tenant.value(), body);
        } else {
            TenantContext.runWithTenant(tenant.value(), tenant.slug(), body);
        }
        if (failure[0] != null) {
            throw failure[0];
        }
    }

    /** Method annotation wins over the (possibly enclosing) class annotation. */
    private static Optional<WithTenant> resolve(ExtensionContext context) {
        Optional<WithTenant> onMethod = context.getTestMethod()
                .flatMap(m -> AnnotationSupport.findAnnotation(m, WithTenant.class));
        if (onMethod.isPresent()) {
            return onMethod;
        }
        Optional<Class<?>> type = context.getTestClass();
        while (type.isPresent()) {
            Optional<WithTenant> onType = AnnotationSupport.findAnnotation(type.get(), WithTenant.class);
            if (onType.isPresent()) {
                return onType;
            }
            type = Optional.ofNullable(type.get().getEnclosingClass());
        }
        return Optional.empty();
    }
}
