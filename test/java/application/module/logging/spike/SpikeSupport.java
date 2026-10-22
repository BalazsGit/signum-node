package application.module.logging.spike;

import org.apache.logging.log4j.core.LoggerContext;

import java.nio.file.Path;

/**
 * Shared helpers for the P0 log4j2 {@link LoggerContext} spike tests
 * (see {@code implementation_plan/node_isolated_loggercontext_logging_plan.md}, P0 section, D7).
 *
 * <p>Every spike context is created directly (object reference, D7) and is NOT registered
 * with {@code LogManager} / a {@code ContextSelector} — exactly how
 * {@code NodeLoggingContextManager} will own its contexts in P1.</p>
 */
final class SpikeSupport {

    private SpikeSupport() {
    }

    /**
     * Creates a named {@link LoggerContext} from a generated .properties file and starts it
     * if the constructor did not start it already.
     */
    static LoggerContext startContext(String name, Path config) {
        LoggerContext ctx = new LoggerContext(name, null, config.toUri());
        boolean startedByConstructor = ctx.isStarted();
        if (!startedByConstructor) {
            ctx.start();
        }
        if (!ctx.isStarted()) {
            throw new IllegalStateException("context " + name + " did not start from " + config);
        }
        System.out.println("SPIKE: context '" + name + "' startedByConstructor=" + startedByConstructor);
        return ctx;
    }

    /** Stops the context if it is still started (safe to call from {@code finally} blocks). */
    static void stopQuietly(LoggerContext ctx) {
        if (ctx != null && ctx.isStarted()) {
            ctx.stop();
        }
    }
}
