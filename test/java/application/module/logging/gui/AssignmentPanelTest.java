package application.module.logging.gui;

import application.api.ModuleContext;
import application.utils.logging.ModuleLoggingProfile;
import application.utils.logging.ModuleLoggingProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.TableCellEditor;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the Logging module's {@link AssignmentPanel} (the "Assignments" sub-tab of a
 * module tab: the node-profile → logging-profile association table scoped to one module).
 * Construction is pumped on the EDT (headless-safe); only the read/display surface is
 * asserted — the Apply and Delete actions open modal dialogs and are deliberately not
 * exercised in tests.
 */
@DisplayName("AssignmentPanel Tests")
class AssignmentPanelTest {

    private static void onEdt(Runnable action) {
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static AssignmentPanel newPanel() {
        final AssignmentPanel[] holder = new AssignmentPanel[1];
        onEdt(() -> holder[0] = new AssignmentPanel(new NoOpContext(), new TestProvider("node")));
        return holder[0];
    }

    private static JTable findTable(Container root) {
        for (int i = 0; i < root.getComponentCount(); i++) {
            Component child = root.getComponent(i);
            if (child instanceof JTable) {
                return (JTable) child;
            }
            if (child instanceof Container) {
                JTable found = findTable((Container) child);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JButton findButton(Container root, String text) {
        for (int i = 0; i < root.getComponentCount(); i++) {
            Component child = root.getComponent(i);
            if (child instanceof JButton && text.equals(((JButton) child).getText())) {
                return (JButton) child;
            }
            if (child instanceof Container) {
                JButton found = findButton((Container) child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static List<String> columnNames(JTable table) {
        List<String> names = new ArrayList<>();
        for (int i = 0; i < table.getColumnCount(); i++) {
            names.add(table.getColumnName(i));
        }
        return names;
    }

    @Test
    @DisplayName("constructs; the table has 'Node profile' and 'Logging profile' columns")
    void constructs() {
        JTable table = findTable(newPanel());
        assertNotNull(table, "assignment table present");
        List<String> names = columnNames(table);
        assertEquals(2, names.size(), "exactly two columns; cols=" + names);
        assertEquals("Node profile", names.get(0));
        assertEquals("Logging profile", names.get(1));
    }

    @Test
    @DisplayName("the toolbar offers Delete selected, Refresh and Apply")
    void toolbarButtons() {
        AssignmentPanel panel = newPanel();
        assertNotNull(findButton(panel, "Delete selected"), "Delete selected button present");
        assertNotNull(findButton(panel, "Refresh"), "Refresh button present");
        assertNotNull(findButton(panel, "Apply (restart node)"), "Apply button present");
    }

    @Test
    @DisplayName("the select menu offers (default) and the provider presets")
    void selectOptions_includeDefaultAndProviderPresets() {
        AssignmentPanel panel = newPanel();
        JTable table = findTable(panel);
        TableCellEditor editor = table.getDefaultEditor(Object.class);
        assertNotNull(editor, "cell editor registered");
        JComboBox<?> combo = (JComboBox<?>) editor.getTableCellEditorComponent(
                table, "quiet", true, 0, 1);
        assertNotNull(combo, "combo cell editor");
        List<String> items = new ArrayList<>();
        for (int i = 0; i < combo.getItemCount(); i++) {
            items.add(String.valueOf(combo.getItemAt(i)));
        }
        assertTrue(items.contains("(default)"), "(default) option present; items=" + items);
        assertTrue(items.contains("quiet"), "provider preset 'quiet' present; items=" + items);
    }

    /** No-op {@link ModuleContext} — the panel's read path never calls back into it. */
    static final class NoOpContext implements ModuleContext {
        @Override
        public Path getConfigDirectory() {
            return Paths.get("target", "test-conf");
        }

        @Override
        public void requestRestart() {
            // no-op
        }

        @Override
        public void shutdown() {
            // no-op
        }
    }

    static final class TestProfile extends ModuleLoggingProfile {
        @Override
        public String getModuleId() {
            return "node";
        }

        @Override
        public String getDisplayName() {
            return "Test Node";
        }

        @Override
        public String getDescription() {
            return "Test node module";
        }

        @Override
        public Map<String, String> getDefaults() {
            return Map.of("node.level", "INFO");
        }

        @Override
        public Map<String, Map<String, String>> getPresetOverrides() {
            return Map.of("quiet", Map.of("node.level", "SEVERE"));
        }
    }

    static final class TestProvider extends ModuleLoggingProvider {
        private final ModuleLoggingProfile profile = new TestProfile();

        TestProvider(String moduleId) {
            // the panel derives the module id from the provider's profile
        }

        @Override
        public ModuleLoggingProfile getProfile() {
            return profile;
        }
    }
}
