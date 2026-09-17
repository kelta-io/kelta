package io.kelta.testharness;

import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/**
 * JUnit 5 extension that boots the full Kelta mini-stack once per test JVM run.
 *
 * <p>Usage:
 * <pre>{@code
 * @ExtendWith(KeltaStackExtension.class)
 * class MyScenarioTest { ... }
 * }</pre>
 *
 * <p>The stack starts on first use and stops when the JVM exits (via shutdown hook).
 * All scenario tests share the same running stack to avoid redundant startup time.
 *
 * <p>A failed start is remembered and rethrown, not retried. Retrying is what a plain
 * "did it succeed?" flag does, and it is ruinous here: every one of the ~40 scenario
 * classes would boot a fresh service container and wait out its multi-minute health
 * timeout, so a single broken migration burns the whole CI budget and the job is killed
 * before any test report is written — a red run with nothing in it to read.
 */
public class KeltaStackExtension implements BeforeAllCallback, ExtensionContext.Store.CloseableResource {

    private static volatile boolean started = false;
    private static volatile RuntimeException startFailure;

    @Override
    public void beforeAll(ExtensionContext context) {
        if (!started) {
            synchronized (KeltaStackExtension.class) {
                if (startFailure != null) {
                    throw startFailure;
                }
                if (!started) {
                    try {
                        KeltaStack.start();
                    } catch (RuntimeException e) {
                        startFailure = e;
                        throw e;
                    }
                    // Register shutdown hook to stop containers when JVM exits
                    Runtime.getRuntime().addShutdownHook(new Thread(KeltaStack::stop, "kelta-stack-shutdown"));
                    started = true;
                }
            }
        }
        // Store this instance so JUnit can call close() — we use a no-op here since
        // teardown is handled by the shutdown hook (shared singleton pattern)
        context.getStore(ExtensionContext.Namespace.GLOBAL)
               .put(KeltaStackExtension.class.getName(), this);
    }

    @Override
    public void close() {
        // Intentional no-op: containers are shared across all scenario tests
        // and torn down by the JVM shutdown hook instead.
    }
}
