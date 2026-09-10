package application.module.logging.gui;

import application.api.ModuleContext;
import application.utils.logging.LoggingModuleRegistry;
import application.utils.logging.ModuleLoggingProfile;
import application.utils.logging.ModuleLoggingProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link LoggingPanel} — the dynamic tab behavior.
 * <p>
 * Verifies that one tab is added/removed when a provider is registered/unregistered, that
 * re-registration is idempotent, and that each module tab contains a "Logging Profiles"
 * and an "Assignments" sub-tab.
 * </p>
 *
 * <h3>EDT handling</h3>
 * The panel's tab mutations are dispatched via {@code SwingUtilities.invokeLater}; tests pump the
 * EDT with {@code SwingUtilities.invokeAndWait} (the same pattern as other GUI tests in this repo).
 */
@DisplayName("LoggingPanel dynamic tab Tests")
class LoggingPanelTest {

    @TempDir
    Path tempDir;

    private LoggingModuleRegistry registry;
    private ModuleContext context;

    @BeforeEach
    void setUp() {
        registry = LoggingModuleRegistry.getInstance();
        registry.clear();
        context = new ModuleContext() {
            @Override
            public Path getConfigDirectory() {
                return tempDir;
            }

            @Override
            public void requestRestart() {
                // no-op in tests
            }

            @Override
            public void shutdown() {
                // no-op in tests
            }
        };
    }

    @AfterEach
    void tearDown() {
        registry.clear();
    }

    private JTabbedPane findTabbedPane(LoggingPanel panel) {
        Component child = ((Container) panel).getComponent(0);
        if (child instanceof JTabbedPane) {
            return (JTabbedPane) child;
        }
        throw new AssertionError("LoggingPanel must contain a JTabbedPane as its first child");
    }

    @Test
    @DisplayName("No module tabs until a provider registers")
    void noProvider_emptyTabbedPane() throws Exception {
        LoggingPanel panel = new LoggingPanel(context);
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals(0, findTabbedPane(panel).getTabCount(), "no tabs before any provider registers");
    }

    @Test
    @DisplayName("Each module tab contains 'Logging Profiles' and 'Assignments' sub-tabs")
    void moduleTab_containsSubTabs() throws Exception {
        new TestProvider("alpha").register();
        LoggingPanel panel = new LoggingPanel(context);
        SwingUtilities.invokeAndWait(() -> { });

        JTabbedPane tabs = findTabbedPane(panel);
        assertEquals(1, tabs.getTabCount(), "one module tab");
        Component tab = tabs.getComponentAt(0);
        assertTrue(tab instanceof JTabbedPane, "module tab must be a JTabbedPane");
        JTabbedPane inner = (JTabbedPane) tab;
        assertEquals(2, inner.getTabCount(), "two sub-tabs");
        assertEquals("Logging Profiles", inner.getTitleAt(0));
        assertEquals("Assignments", inner.getTitleAt(1));
    }

    @Test
    @DisplayName("A tab is added when a provider is registered (even before panel creation)")
    void providerRegisteredBeforePanel_tabAdded() throws Exception {
        new TestProvider("alpha").register();
        SwingUtilities.invokeAndWait(() -> { });

        LoggingPanel panel = new LoggingPanel(context);
        SwingUtilities.invokeAndWait(() -> { });

        JTabbedPane tabs = findTabbedPane(panel);
        assertEquals(1, tabs.getTabCount(), "only the alpha tab");
        assertEquals("Test A", tabs.getTitleAt(0));
    }

    @Test
    @DisplayName("A tab is added dynamically when a provider registers after panel creation")
    void providerRegisteredAfterPanel_tabAdded() throws Exception {
        LoggingPanel panel = new LoggingPanel(context);
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(0, findTabbedPane(panel).getTabCount());

        new TestProvider("beta").register();
        SwingUtilities.invokeAndWait(() -> { });

        JTabbedPane tabs = findTabbedPane(panel);
        assertEquals(1, tabs.getTabCount(), "only the beta tab");
        assertEquals("Test B", tabs.getTitleAt(0));
    }

    @Test
    @DisplayName("A tab is removed when a provider is unregistered")
    void providerUnregistered_tabRemoved() throws Exception {
        new TestProvider("gamma").register();
        LoggingPanel panel = new LoggingPanel(context);
        SwingUtilities.invokeAndWait(() -> { });
        assertEquals(1, findTabbedPane(panel).getTabCount());

        registry.unregister("gamma");
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals(0, findTabbedPane(panel).getTabCount(), "the module tab is removed");
    }

    @Test
    @DisplayName("Re-registering the same module ID does not duplicate the tab")
    void reRegister_sameModuleId_noDuplicateTab() throws Exception {
        new TestProvider("delta").register();
        LoggingPanel panel = new LoggingPanel(context);
        SwingUtilities.invokeAndWait(() -> { });
        int afterFirst = findTabbedPane(panel).getTabCount();

        new TestProvider("delta").register();
        SwingUtilities.invokeAndWait(() -> { });

        assertEquals(afterFirst, findTabbedPane(panel).getTabCount(),
                "re-registration must not duplicate the tab");
    }

    @Test
    @DisplayName("Multiple distinct providers yield one tab each")
    void multipleProviders_oneTabEach() throws Exception {
        new TestProvider("mod-a").register();
        new TestProvider("mod-b").register();
        LoggingPanel panel = new LoggingPanel(context);
        SwingUtilities.invokeAndWait(() -> { });

        JTabbedPane tabs = findTabbedPane(panel);
        assertEquals(2, tabs.getTabCount(), "mod-a + mod-b");
    }

    // ── Test fixtures ──────────────────────────────────────────────────

    static final class TestProfile extends ModuleLoggingProfile {
        private final String moduleId;

        TestProfile(String moduleId) {
            this.moduleId = moduleId;
        }

        @Override
        public String getModuleId() {
            return moduleId;
        }

        @Override
        public String getDisplayName() {
            return "Test " + Character.toUpperCase(moduleId.charAt(0));
        }

        @Override
        public String getDescription() {
            return "Test module " + moduleId;
        }

        @Override
        public Map<String, String> getDefaults() {
            return Map.of(moduleId + ".level", "INFO");
        }

        @Override
        public Map<String, Map<String, String>> getPresetOverrides() {
            return Map.of("quiet", Map.of(moduleId + ".level", "SEVERE"));
        }
    }

    static final class TestProvider extends ModuleLoggingProvider {
        private final ModuleLoggingProfile profile;

        TestProvider(String moduleId) {
            this.profile = new TestProfile(moduleId);
        }

        @Override
        public ModuleLoggingProfile getProfile() {
            return profile;
        }
    }
}