package application.module.node.gui.configuration;

import application.module.logging.gui.ModuleLoggingProfilePanel;
import application.utils.gui.GuiColors;
import application.utils.logging.ModuleLoggingProfile;
import application.utils.logging.ModuleLoggingProvider;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link NodeLoggingPanel} — the thin node adapter over {@link ModuleLoggingProfilePanel}.
 * <p>
 * Verifies the composition (the adapter IS-A core panel), that the node File Handler rows are
 * present, that the value-typed editor assigns the correct control type per key, and that the core
 * extension methods ({@code addExtraField}, {@code setHelpSupplier}, {@code enableSearch}) take effect.
 * Construction is pumped on the EDT (the same pattern as {@code LoggingPanelTest}).
 * </p>
 */
@DisplayName("NodeLoggingPanel Tests")
class NodeLoggingPanelTest {

    private static void onEdt(Runnable action) {
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static NodeLoggingPanel newPanel() {
        final NodeLoggingPanel[] holder = new NodeLoggingPanel[1];
        onEdt(() -> holder[0] = new NodeLoggingPanel(() -> { }));
        return holder[0];
    }

    private static List<String> allLabels(Container root) {
        List<String> out = new ArrayList<>();
        collectLabels(root, out);
        return out;
    }

    private static void collectLabels(Container c, List<String> out) {
        for (Component child : c.getComponents()) {
            if (child instanceof JLabel) {
                out.add(((JLabel) child).getText());
            }
            if (child instanceof Container) {
                collectLabels((Container) child, out);
            }
        }
    }

    private static JComponent editorForLabel(Container root, String label) {
        for (int i = 0; i < root.getComponentCount(); i++) {
            Component child = root.getComponent(i);
            if (child instanceof JLabel) {
                if (label.equals(((JLabel) child).getText()) && i + 1 < root.getComponentCount()) {
                    JComponent next = (JComponent) root.getComponent(i + 1);
                    // The editor now sits inside the row's value cell (editor +
                    // per-row "Remove key" trash icon) — unwrap the cell.
                    if (next instanceof Container cell) {
                        for (Component inner : cell.getComponents()) {
                            if (inner instanceof JComboBox || inner instanceof JTextField) {
                                return (JComponent) inner;
                            }
                        }
                    }
                    return next;
                }
            } else if (child instanceof Container) {
                JComponent found = editorForLabel((Container) child, label);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("is a ModuleLoggingProfilePanel; getCorePanel() returns itself")
    void composition() {
        NodeLoggingPanel panel = newPanel();
        assertInstanceOf(ModuleLoggingProfilePanel.class, panel);
        assertSame(panel, panel.getCorePanel());
    }

    @Test
    @DisplayName("FileHandler extra fields are present as editor rows")
    void fileHandlerRowsPresent() {
        NodeLoggingPanel panel = newPanel();
        List<String> labels = allLabels(panel);
        assertTrue(labels.contains("File Level"), "File Level row present; labels=" + labels);
        assertTrue(labels.contains("Log File Pattern"), "Log File Pattern row present");
        assertTrue(labels.contains("File Size Limit (bytes)"), "File limit row present");
        assertTrue(labels.contains("File Count"), "File count row present");
    }

    @Test
    @DisplayName("value-typed editor: level keys → combo, non-level keys → text field")
    void valueTypedEditor() {
        NodeLoggingPanel panel = newPanel();
        assertInstanceOf(JComboBox.class, editorForLabel(panel, "File Level"), "File Level should be a combo");
        assertInstanceOf(JTextField.class, editorForLabel(panel, "File Size Limit (bytes)"), "limit should be a text field");
        assertInstanceOf(JTextField.class, editorForLabel(panel, "File Count"), "count should be a text field");
        assertInstanceOf(JTextField.class, editorForLabel(panel, "Log File Pattern"), "pattern should be a text field");
    }

    @Test
    @DisplayName("core addExtraField adds a new editor row")
    void addExtraField() {
        final ModuleLoggingProfilePanel[] holder = new ModuleLoggingProfilePanel[1];
        onEdt(() -> {
            ModuleLoggingProfilePanel core = new ModuleLoggingProfilePanel(new TestProvider());
            core.addExtraField("custom.key", "Custom Key", "INFO");
            holder[0] = core;
        });
        List<String> labels = allLabels(holder[0]);
        assertTrue(labels.contains("Custom Key"), "extra field row present; labels=" + labels);
    }

    @Test
    @DisplayName("core setHelpSupplier enables the Help button")
    void helpSupplier() {
        final ModuleLoggingProfilePanel[] holder = new ModuleLoggingProfilePanel[1];
        onEdt(() -> {
            ModuleLoggingProfilePanel core = new ModuleLoggingProfilePanel(new TestProvider());
            core.setHelpSupplier(() -> "<html>help</html>");
            holder[0] = core;
        });
        JButton help = findButtonByTooltip(holder[0], "Help");
        assertNotNull(help, "Help button present");
        assertTrue(help.isEnabled(), "Help button enabled after setHelpSupplier");
    }

    @Test
    @DisplayName("the live row search is on by default (enableSearch stays idempotent)")
    void searchIsOnByDefault() {
        final ModuleLoggingProfilePanel[] holder = new ModuleLoggingProfilePanel[1];
        final int[] before = new int[1];
        onEdt(() -> {
            ModuleLoggingProfilePanel core = new ModuleLoggingProfilePanel(new TestProvider());
            assertNotNull(findTitled(core, "Search"),
                    "the 'Search' titled box must be visible straight after construction (default-on)");
            before[0] = countVisibleTextFields(core);
            core.enableSearch(); // idempotent: the constructor already enabled it
            holder[0] = core;
        });
        assertEquals(before[0], countVisibleTextFields(holder[0]),
                "enableSearch must not add a second search field (the search is on by default)");
    }

    @Test
    @DisplayName("unsaved rows carry the trailing star (saved/applied rows do not)")
    void unsavedRowIsStarred() {
        NodeLoggingPanel panel = newPanel();
        // With the virtual 'Default' entry selected, any change is unsaved.
        JComboBox<?> fileLevel = assertInstanceOf(JComboBox.class, editorForLabel(panel, "File Level"));
        Object current = fileLevel.getSelectedItem();
        String other = "FINE".equals(String.valueOf(current)) ? "INFO" : "FINE";
        onEdt(() -> fileLevel.setSelectedItem(other));
        List<String> labels = allLabels(panel);
        assertTrue(labels.contains("File Level *"),
                "the changed row carries the trailing star; labels=" + labels);
        assertFalse(labels.contains("File Count *"),
                "an untouched row must not be starred; labels=" + labels);
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private static int countVisibleTextFields(Container root) {
        int[] n = {0};
        countTextFields(root, n);
        return n[0];
    }

    private static void countTextFields(Container c, int[] n) {
        for (Component child : c.getComponents()) {
            if (child instanceof JTextField && child.isVisible()) {
                n[0]++;
            }
            if (child instanceof Container) {
                countTextFields((Container) child, n);
            }
        }
    }

    private static JButton findButtonByTooltip(Container root, String part) {
        for (int i = 0; i < root.getComponentCount(); i++) {
            Component child = root.getComponent(i);
            if (child instanceof JButton) {
                if (part.equals(((JButton) child).getToolTipText())
                        || (((JButton) child).getToolTipText() != null
                                && ((JButton) child).getToolTipText().contains(part))) {
                    return (JButton) child;
                }
            } else if (child instanceof Container) {
                JButton found = findButtonByTooltip((Container) child, part);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("row coloring: an untouched row is never 'unsaved' (it is saved/applied)")
    void untouchedRowIsNotUnsaved() {
        NodeLoggingPanel panel = newPanel();
        JLabel label = findLabel(panel, "File Count");
        assertNotNull(label, "File Count row label present");
        assertNotEquals(GuiColors.getUnsaved(), label.getForeground(),
                "an untouched row (value == default/saved) must not be colored unsaved");
    }

    @Test
    @DisplayName("row coloring: editing a row turns it 'unsaved' (the unsaved color)")
    void editedRowBecomesUnsaved() {
        final NodeLoggingPanel[] holder = new NodeLoggingPanel[1];
        onEdt(() -> {
            NodeLoggingPanel p = new NodeLoggingPanel(() -> { }); // already on the EDT
            JComponent editor = editorForLabel(p, "File Count"); // text field, default "1"
            ((JTextField) editor).setText("2");
            holder[0] = p;
        });
        JLabel label = findLabel(holder[0], "File Count");
        assertEquals(GuiColors.getUnsaved(), label.getForeground(), "an edited row is colored unsaved");
    }

    @Test
    @DisplayName("the 'Show values' filter hides rows of the unchecked state")
    void showValuesFilterHidesUnsavedRows() {
        final NodeLoggingPanel[] panelHolder = new NodeLoggingPanel[1];
        final JCheckBox[] boxHolder = new JCheckBox[1];
        onEdt(() -> {
            NodeLoggingPanel p = new NodeLoggingPanel(() -> { }); // already on the EDT
            ((JTextField) editorForLabel(p, "File Count")).setText("2"); // make it unsaved
            panelHolder[0] = p;
            boxHolder[0] = findCheckBox(p, "Unsaved values");
        });
        assertNotNull(boxHolder[0], "the 'Unsaved values' filter checkbox is present");

        final JCheckBox box = boxHolder[0];
        // doClick() (a simulated user click) — a programmatic setSelected does
        // not fire the ActionEvent that drives the row re-render.
        onEdt(box::doClick); // uncheck → hide unsaved rows
        assertFalse(hasLabel(panelHolder[0], "File Count"),
                "the unsaved row is hidden while 'Unsaved values' is off");

        onEdt(box::doClick); // check again → show them
        assertTrue(hasLabel(panelHolder[0], "File Count"),
                "the row reappears when the filter is switched back on");
    }

    @Test
    @DisplayName("host-added (FileHandler) rows carry a DISABLED 'Remove key' icon (built-in rows)")
    void hostRowsAreNotRemovable() {
        NodeLoggingPanel panel = newPanel();
        JComponent editor = editorForLabel(panel, "File Count");
        assertNotNull(editor, "the File Count row editor is present");
        Container cell = (Container) editor.getParent();
        JButton remove = null;
        for (Component child : cell.getComponents()) {
            if (child instanceof JButton) {
                remove = (JButton) child;
            }
        }
        assertNotNull(remove, "the row's value cell carries the 'Remove key' trash icon");
        assertFalse(remove.isEnabled(), "host-registered (built-in) rows cannot be removed");
    }

    private static boolean hasLabel(Container root, String text) {
        return allLabels(root).stream().anyMatch(t2 -> text.equals(t2) || (text + " *").equals(t2));
    }

    @Test
    @DisplayName("search row: Profile / Search / Show values are left-aligned with uniform gaps and equal heights")
    void searchRowAlignment() {
        NodeLoggingPanel panel = newPanel();
        onEdt(() -> {
            panel.enableSearch(); // idempotent: the constructor already enabled it
            panel.setSize(900, 600);
            layoutRecursively(panel);
        });

        Container profileBox = findTitled(panel, "Profile");
        Container searchBox = findTitled(panel, "Search");
        Container showValues = findTitled(panel, "Show values");
        Container levelsFrame = findTitled(panel, "Logger levels");
        assertNotNull(profileBox, "the 'Profile' titled box is present");
        assertNotNull(searchBox, "the 'Search' titled box is present");
        assertNotNull(showValues, "the 'Show values' titled box is present");
        assertNotNull(levelsFrame, "the 'Logger levels' titled frame is present");

        // Left-aligned: the row's first box starts at the same left edge as
        // the "Logger levels" frame (the panels line up column-wise).
        assertEquals(horizontalPosition(panel, levelsFrame), horizontalPosition(panel, profileBox),
                "the profile box must start at the same left edge as the 'Logger levels' frame");

        // Uniform gaps between all three boxes (Profile ↔ Search ↔ Show values).
        int profileToSearch = horizontalPosition(panel, searchBox)
                - (horizontalPosition(panel, profileBox) + profileBox.getWidth());
        int searchToShowValues = horizontalPosition(panel, showValues)
                - (horizontalPosition(panel, searchBox) + searchBox.getWidth());
        assertEquals(profileToSearch, searchToShowValues,
                "the gaps between Profile/Search/Show values must be uniform (profile→search="
                        + profileToSearch + ", search→show-values=" + searchToShowValues + ")");
        assertEquals(4, profileToSearch, "the gap must be the row's uniform 4px gap");

        // Uniform heights across all three boxes.
        assertEquals(profileBox.getHeight(), searchBox.getHeight(),
                "the Profile and Search boxes must have the same height");
        assertEquals(profileBox.getHeight(), showValues.getHeight(),
                "the Profile and Show values boxes must have the same height");
    }

    /** The first container carrying a TitledBorder with exactly the given title (may be null). */
    private static Container findTitled(Container root, String title) {
        for (int i = 0; i < root.getComponentCount(); i++) {
            Component child = root.getComponent(i);
            if (child instanceof JComponent jcomponent && jcomponent instanceof Container) {
                Container container = jcomponent;
                if (jcomponent.getBorder() instanceof TitledBorder border && title.equals(border.getTitle())) {
                    return container;
                }
                Container found = findTitled(container, title);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /** The x position of {@code c} relative to {@code root} (walking up the parents). */
    private static int horizontalPosition(Container root, Component c) {
        int x = c.getX();
        Component p = c.getParent();
        while (p != null && p != root) {
            x += p.getX();
            p = p.getParent();
        }
        return x;
    }

    /** Forces a full layout pass over the component tree (mirrors the core panel's test). */
    private static void layoutRecursively(Container c) {
        c.doLayout();
        for (Component child : c.getComponents()) {
            if (child instanceof Container nested) {
                layoutRecursively(nested);
            }
        }
    }

    /** Whether the label shows the row name, with or without the trailing unsaved star. */
    private static boolean labelMatches(JLabel label, String text) {
        String shown = label.getText();
        return text.equals(shown) || (shown != null && shown.equals(text + " *"));
    }

    private static JLabel findLabel(Container root, String text) {
        for (int i = 0; i < root.getComponentCount(); i++) {
            Component child = root.getComponent(i);
            if (child instanceof JLabel && labelMatches((JLabel) child, text)) {
                return (JLabel) child;
            }
            if (child instanceof Container) {
                JLabel found = findLabel((Container) child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private static JCheckBox findCheckBox(Container root, String text) {
        for (int i = 0; i < root.getComponentCount(); i++) {
            Component child = root.getComponent(i);
            if (child instanceof JCheckBox cb && text.equals(cb.getText())) {
                return cb;
            }
            if (child instanceof Container) {
                JCheckBox found = findCheckBox((Container) child, text);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    // ── Test fixture ───────────────────────────────────────────────────

    static final class TestProvider extends ModuleLoggingProvider {
        @Override
        public ModuleLoggingProfile getProfile() {
            return new ModuleLoggingProfile() {
                @Override public String getModuleId() { return "test-mod"; }
                @Override public String getDisplayName() { return "Test Module"; }
                @Override public String getDescription() { return "test provider"; }
                @Override public Map<String, String> getDefaults() {
                    return Map.of(
                            "test.level", "INFO",
                            "test.handler", "java.util.logging.ConsoleHandler");
                }
            };
        }
    }
}
