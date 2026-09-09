package application.module.logging.gui;

import application.api.ModuleContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the Logging module's {@link AssignmentPanel} (the "Assignments" tab: the
 * node-profile → module-preset table). Construction is pumped on the EDT (headless-safe);
 * only the read/display surface is asserted — the Apply action opens a modal dialog and is
 * deliberately not exercised in tests.
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
        onEdt(() -> holder[0] = new AssignmentPanel(new NoOpContext()));
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
    @DisplayName("constructs; the table leads with the 'Node profile' column")
    void constructs() {
        JTable table = findTable(newPanel());
        assertNotNull(table, "assignment table present");
        List<String> names = columnNames(table);
        assertTrue("Node profile".equals(names.get(0)), "first column is 'Node profile'; cols=" + names);
    }

    @Test
    @DisplayName("module preset columns include node and database")
    void moduleColumns() {
        List<String> names = columnNames(findTable(newPanel()));
        assertTrue(names.contains("node"), "node column present; cols=" + names);
        assertTrue(names.contains("database"), "database column present; cols=" + names);
    }

    @Test
    @DisplayName("the toolbar offers Apply and Refresh")
    void toolbarButtons() {
        AssignmentPanel panel = newPanel();
        assertNotNull(findButton(panel, "Apply (restart node)"), "Apply button present");
        assertNotNull(findButton(panel, "Refresh"), "Refresh button present");
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
}
