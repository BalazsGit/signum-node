package application.module.logging.spike;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.impl.Log4jContextFactory;
import org.apache.logging.log4j.core.selector.ContextSelector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P0 gate Q1: context factory / selector contract.
 *
 * <p>Test 1 (evidence): records what the DEFAULT factory does on log4j 2.26.1 for the
 * null-name path ({@code LogManager.getContext()}) vs the FQCN path
 * ({@code LogManager.getLogger("<FQCN>")}). First run showed the two resolve to DIFFERENT
 * contexts on 2.26.1, so the plan's "selector only needs the null-name path" assumption
 * needs the custom-selector test below.</p>
 *
 * <p>Test 2: verifies the actual P1 mechanism — a custom {@link ContextSelector} serving
 * ONE shared context for ANY name converges both lookups (the D7 global context).</p>
 */
@DisplayName("P0 Q1 - context factory / selector contract")
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SpikeQ1DefaultContextContractTest {

    @Test
    @Order(1)
    @DisplayName("EVIDENCE: default factory - null-name vs FQCN lookups")
    void defaultFactoryBehavior_evidence() {
        LoggerContext byNullName = (LoggerContext) LogManager.getContext();
        Logger byFqcn = (Logger) LogManager.getLogger("application.spike.SomeFqcnClass");
        LoggerContext byFqcnContext = byFqcn.getContext();

        Log4jContextFactory factory = (Log4jContextFactory) LogManager.getFactory();
        System.out.println("SPIKE Q1 DEFAULT: null-name ctx = '" + byNullName.getName() + "'"
                + ", FQCN ctx = '" + byFqcnContext.getName() + "'"
                + ", same = " + (byNullName == byFqcnContext)
                + ", selector = " + factory.getSelector().getClass().getSimpleName()
                + ", total contexts = " + factory.getSelector().getLoggerContexts().size());
        assertTrue(true, "evidence only, see SPIKE Q1 DEFAULT line");
    }

    @Test
    @Order(2)
    @DisplayName("a single-context selector converges null-name and FQCN lookups (P1 mechanism)")
    void customSelectorConvergesBothLookups() {
        Log4jContextFactory previous = (Log4jContextFactory) LogManager.getFactory();
        LogManager.setFactory(new Log4jContextFactory(new SingleContextSelector()));
        try {
            LoggerContext byNullName = (LoggerContext) LogManager.getContext();
            LoggerContext byFqcnContext =
                    ((Logger) LogManager.getLogger("application.spike.OtherFqcnClass")).getContext();
            assertSame(byNullName, byFqcnContext,
                    "a single-context selector must converge null-name and FQCN lookups (D7 global context)");
            System.out.println("SPIKE Q1 CUSTOM: converged context = '" + byNullName.getName() + "'");
        } finally {
            LogManager.setFactory(previous);
        }
    }

    /**
     * P1-style selector: serves one shared context for ANY name — this is exactly how
     * {@code SignumGlobalContextSelector} will work (name is never used for routing).
     */
    static final class SingleContextSelector implements ContextSelector {

        private final LoggerContext shared = new LoggerContext("spike-shared");

        SingleContextSelector() {
            if (!shared.isStarted()) {
                shared.start();
            }
        }

        @Override
        public LoggerContext getContext(String name, ClassLoader classLoader, boolean useCurrentContext) {
            return shared;
        }

        @Override
        public LoggerContext getContext(String name, ClassLoader classLoader, boolean useCurrentContext, URI config) {
            return shared;
        }

        @Override
        public List<LoggerContext> getLoggerContexts() {
            return List.of(shared);
        }

        @Override
        public void removeContext(LoggerContext context) {
            // no-op for the spike
        }
    }
}
