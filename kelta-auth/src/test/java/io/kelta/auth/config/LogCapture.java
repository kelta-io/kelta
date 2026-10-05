package io.kelta.auth.config;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.AppenderBase;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Collects the events one logger emits on the threads a test owns. Surefire runs test classes in
 * parallel, so a capture that took every event would also see another class's log lines.
 */
final class LogCapture extends AppenderBase<ILoggingEvent> implements AutoCloseable {

    private final Logger logger;
    private final Predicate<String> threadName;
    private final List<ILoggingEvent> events = new CopyOnWriteArrayList<>();

    private LogCapture(String loggerName, Predicate<String> threadName) {
        this.logger = (Logger) LoggerFactory.getLogger(loggerName);
        this.threadName = threadName;
        start();
        logger.addAppender(this);
    }

    /** Events logged on the calling thread. */
    static LogCapture onCurrentThread(Class<?> source) {
        String current = Thread.currentThread().getName();
        return new LogCapture(source.getName(), current::equals);
    }

    /**
     * Every logger's events (the root logger) on the calling thread or on any thread whose name
     * starts with {@code prefix}.
     */
    static LogCapture allLoggersOnCurrentThreadOr(String prefix) {
        String current = Thread.currentThread().getName();
        return new LogCapture(org.slf4j.Logger.ROOT_LOGGER_NAME,
                name -> current.equals(name) || name.startsWith(prefix));
    }

    @Override
    protected void append(ILoggingEvent event) {
        if (threadName.test(event.getThreadName())) {
            events.add(event);
        }
    }

    List<ILoggingEvent> events() {
        return List.copyOf(events);
    }

    @Override
    public void close() {
        logger.detachAppender(this);
        stop();
    }
}
