package application.module.logging.context;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * P1.1: {@link SignumGlobalContextSelector} — every lookup (null-name and
 * caller-FQCN) must converge on the single "signum-global" context. The P0
 * spike (Q1) proved the default log4j 2.26.1 factory does NOT converge, so
 * the convergence itself is the contract under test here.
 */
@DisplayName("P1.1 - SignumGlobalContextSelector")
class SignumGlobalContextSelectorTest {

    @TempDir
    Path tempDir;

    private Log4jContextFactory previousFactory;

    @BeforeEach
    void installFactory() {
        previousFactory = (Log4jContextFactory) LogManager.getFactory();
        LogManager.setFactory(new Log4jContextFactory(new SignumGlobalContextSelector()));
    }

    @AfterEach
    void restoreFactory() {
        Log4jContextFactory current = (Log4jContextFactory) LogManager.getFactory();
        for (LoggerContext ctx : current.getSelector().getLoggerContexts()) {
            ctx.stop();
        }
        LogManager.setFactory(previousFactory);
    }

    @Test
    @DisplayName("null-name and FQCN lookups converge to the same signum-global context")
    void bothLookupPathsConverge() {
        LoggerContext byNullName = (LoggerContext) LogManager.getContext();
        Logger byFqcn = (Logger) LogManager.getLogger("application.some.SomeClass");
        LoggerContext byFqcnContext = byFqcn.getContext();

        assertSame(byNullName, byFqcnContext,
                "every lookup must converge on one context (D7)");
        assertEquals(SignumGlobalContextSelector.GLOBAL_CONTEXT_NAME, byNullName.getName(),
                "the global context must carry the deterministic name");
    }

    @Test
    @DisplayName("getLoggerContexts reports exactly the shared global context")
    void onlyTheGlobalContextIsReported() {
        Log4jContextFactory factory = (Log4jContextFactory) LogManager.getFactory();
        List<LoggerContext> contexts = factory.getSelector().getLoggerContexts();
        LoggerContext byNullName = (LoggerContext) LogManager.getContext();

        assertEquals(1, contexts.size(), "the selector serves exactly one context");
        assertSame(byNullName, contexts.get(0));
    }

    @Test
    @DisplayName("removeContext is a no-op (the global context survives)")
    void removeContextKeepsTheGlobalContext() {
        Log4jContextFactory factory = (Log4jContextFactory) LogManager.getFactory();
        LoggerContext byNullName = (LoggerContext) LogManager.getContext();

        factory.getSelector().removeContext(byNullName);

        assertSame(byNullName, (LoggerContext) LogManager.getContext(),
                "the global context must not be removable through the selector");
    }

    @Test
    @DisplayName("a selector constructed with a config URI applies that config")
    void configUriIsAppliedOnConstruction() throws Exception {
        Path file = tempDir.resolve("global.log4j2.properties");
        Files.writeString(file, "status = ERROR\nrootLogger.level = TRACE\n");

        LoggerContext ctx = new SignumGlobalContextSelector(file.toUri())
                .getContext(null, null, false);

        assertEquals("TRACE",
                String.valueOf(ctx.getConfiguration().getRootLogger().getLevel()),
                "the generated global config must be applied by the constructor (spike Q2a)");
        ctx.stop();
    }
}