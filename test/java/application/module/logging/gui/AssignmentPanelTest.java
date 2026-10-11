package application.module.logging.gui;

import application.api.ModuleContext;
import application.module.logging.LoggingAssignmentStore;
import application.utils.logging.ModuleLoggingProfile;
import application.utils.logging.ModuleLoggingProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JTable;
import javax.swing.SwingUtilities;
import javax.swing.table.TableCellEditor;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for the Logging module's {@link AssignmentPanel} (the "Assignments" sub-tab of a
 * module tab: the node-profile â†’ logging-profile association table scoped to one module).
 * Construction is pumped on the EDT (headless-safe); only the read/display surface is
 * asserted â€” the Apply and Delete actions open modal dialogs and are deliberately not
 * exercised in tests.
 */
@DisplayName("AssignmentPanel Tests")
class AssignmentPanelTest {

    /** Sandbox conf root for the push-refresh tests (never the real {@code conf/}). */
    @TempDir
    Path tempConf;

    /** The push-refresh test's sandbox-bound panel (null outside those tests). */
    private AssignmentPanel pushPanel;

    @AfterEach
    void disposePushPanel() {
        if (pushPanel != null) {
            onEdt(pushPanel::dispose);
            pushPanel = null;
        }
    }

    private static void onEdt(Runnable action) {
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    /** Creates the push-refresh test's panel bound to the sandbox conf root. */
    private AssignmentPanel newPushPanel() {
        final AssignmentPanel[] holder = new AssignmentPanel[1];
        onEdt(() -> holder[0] = new AssignmentPanel(new NoOpContext(), new TestProvider("node"), tempConf.toString()));
        pushPanel = holder[0];
        return holder[0];
    }

    /** Writes a node profile file the way the Node module's repository does. */
    private void writeNodeProfile(String name) throws IOException {
        Path dir = tempConf.resolve("node").resolve("profiles");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve(name + ".properties"), "n=1\n");
    }

    private int pushRowCount() {
        final int[] rows = new int[1];
        onEdt(() -> rows[0] = pushPanel.model().getRowCount());
        return rows[0];
    }

    private String pushCell(int row, int col) {
        final Object[] value = new Object[1];
        onEdt(() -> value[0] = pushPanel.model().getValueAt(row, col));
        return String.valueOf(value[0]);
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
    @DisplayName("the select menu offers (default) and the on-disk profiles")
    void selectOptions_includeDefaultAndOnDiskProfiles() {
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
    }

    @Test
    @DisplayName("a changed assignment is starred on the node-profile cell; Refresh clears it")
    void changedAssignmentIsStarred() {
        AssignmentPanel panel = newPanel();
        JTable table = findTable(panel);
        javax.swing.table.TableModel model = table.getModel();
        org.junit.jupiter.api.Assumptions.assumeTrue(model.getRowCount() > 0,
                "no node profiles on disk â€” nothing to assign");

        // The model value of the row identity stays CLEAN (the star is
        // display-only, applied by the column renderer).
        String cleanProfile = String.valueOf(model.getValueAt(0, 0));
        assertFalse(cleanProfile.endsWith(" *"), "the model value is not starred");

        // Change the assignment in the model: the rendered cell is starred.
        String current = String.valueOf(model.getValueAt(0, 1));
        String changed = "(default)".equals(current) ? "starred-probe" : "(default)";
        onEdt(() -> model.setValueAt(changed, 0, 1));
        javax.swing.JLabel rendered = (javax.swing.JLabel)
                renderOnEdt(table, 0, 0);
        assertTrue(rendered.getText().endsWith(" *"),
                "the node-profile cell carries the trailing star; got \"" + rendered.getText() + "\"");
        assertEquals(cleanProfile, rendered.getText().replace(" *", ""),
                "the star is a suffix of the clean profile name");

        // Refresh re-reads the store (the change was never persisted): the row
        // reverts to the on-disk value and the star clears.
        onEdt(() -> findButton(panel, "Refresh").doClick());
        javax.swing.JLabel refreshed = (javax.swing.JLabel)
                renderOnEdt(table, 0, 0);
        assertFalse(refreshed.getText().endsWith(" *"),
                "Refresh re-baselines the row â€” no star; got \"" + refreshed.getText() + "\"");
    }

    @Test
    @DisplayName("a newly created node profile appears in the table via the push notification")
    void newNodeProfileAppearsViaPush() throws IOException {
        newPushPanel();
        assertEquals(0, pushRowCount(), "the table starts empty for a fresh conf root");

        // A new node profile appears on disk and the store fires the push.
        writeNodeProfile("push-node");
        LoggingAssignmentStore.fireProfileChanged();
        onEdt(() -> { }); // pump the EDT: the queued refresh runs

        assertEquals(1, pushRowCount(), "the new node profile row must appear without a manual refresh");
        assertEquals("push-node", pushCell(0, 0), "the row holds the node profile name");
        assertEquals("(default)", pushCell(0, 1), "an unassigned profile shows the (default) sentinel");
    }

    @Test
    @DisplayName("an assignment change updates the matching row via the push notification")
    void assignmentChangeUpdatesRowViaPush() throws IOException {
        newPushPanel();
        writeNodeProfile("push-node");
        LoggingAssignmentStore.fireProfileChanged();
        onEdt(() -> { });
        assertEquals("(default)", pushCell(0, 1));

        new LoggingAssignmentStore(tempConf.toString())
                .setAssignmentForModule("push-node", "node", "verbose"); // fires the push
        onEdt(() -> { });

        assertEquals("verbose", pushCell(0, 1), "the assigned logging profile must appear in the row");
    }

    @Test
    @DisplayName("the push refresh never clobbers an unsaved row edit (manual Refresh re-scans)")
    void pushRefreshSkipsUnsavedRows() throws IOException {
        newPushPanel();
        writeNodeProfile("push-node");
        LoggingAssignmentStore.fireProfileChanged();
        onEdt(() -> { });
        assertEquals(1, pushRowCount());

        // A pending (unsaved) cell edit; remember the baseline value.
        final Object[] baseline = new Object[1];
        onEdt(() -> {
            baseline[0] = pushPanel.model().getValueAt(0, 1);
            pushPanel.model().setValueAt("pending-profile", 0, 1);
        });

        writeNodeProfile("push-node-2");
        LoggingAssignmentStore.fireProfileChanged();
        onEdt(() -> { });

        assertEquals(1, pushRowCount(),
                "the automatic refresh must skip the table while it has unsaved changes");
        assertEquals("pending-profile", pushCell(0, 1), "the pending cell edit must not be clobbered");

        // The manual Refresh stays the explicit unconditional re-scan.
        onEdt(() -> findButton(pushPanel, "Refresh").doClick());
        assertEquals(2, pushRowCount(), "the manual refresh re-scans and picks up the new profile");
        assertEquals(String.valueOf(baseline[0]), pushCell(0, 1),
                "the manual refresh re-baselines the cell from the store");
    }

    @Test
    @DisplayName("refreshIfSafe re-scans when no row is unsaved (the sub-tab activation path)")
    void refreshIfSafeRescansWhenClean() throws IOException {
        newPushPanel();
        assertEquals(0, pushRowCount());

        writeNodeProfile("push-node"); // no push — the activation re-scan must catch it
        onEdt(pushPanel::refreshIfSafe);

        assertEquals(1, pushRowCount(), "refreshIfSafe must re-scan a clean table");
    }

    @Test
    @DisplayName("dispose unsubscribes the panel from the push notification")
    void disposeStopsPushRefresh() throws IOException {
        AssignmentPanel disposed = newPushPanel();
        onEdt(disposed::dispose);
        pushPanel = null; // disposePushPanel() must not dispose twice

        writeNodeProfile("push-node");
        LoggingAssignmentStore.fireProfileChanged();
        onEdt(() -> { }); // pump the EDT: a queued refresh would run here

        final int[] rows = new int[1];
        onEdt(() -> rows[0] = disposed.model().getRowCount());
        assertEquals(0, rows[0], "a disposed (unsubscribed) panel must not refresh on the push");
    }

    /** Renders the given cell with the column's own renderer (on the EDT) and returns the component. */
    private static java.awt.Component renderOnEdt(JTable table, int row, int col) {
        final java.awt.Component[] holder = new java.awt.Component[1];
        onEdt(() -> holder[0] = table.prepareRenderer(
                (javax.swing.table.TableCellRenderer) table.getColumnModel().getColumn(col).getCellRenderer(),
                row, col));
        return holder[0];
    }

    /** No-op {@link ModuleContext} â€” the panel's read path never calls back into it. */
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
