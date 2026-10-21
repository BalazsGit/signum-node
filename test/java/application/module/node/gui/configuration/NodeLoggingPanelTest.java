package application.module.node.gui.configuration;

import application.module.logging.LoggingAssignmentStore;
import application.module.logging.LoggingProfileRepository;
import application.module.logging.gui.ModuleLoggingProfilePanel;
import application.utils.config.ModuleIds;
import application.utils.gui.ConfigurationUtils;
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
import javax.swing.JList;
import javax.swing.JTextField;
import javax.swing.ListCellRenderer;
import javax.swing.SwingUtilities;
import javax.swing.border.TitledBorder;
import java.awt.Component;
import java.awt.Container;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
                if (baseLabel(label).equals(baseLabel(((JLabel) child).getText())) && i + 1 < root.getComponentCount()) {
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
        assertTrue(labels.stream().anyMatch(l -> baseLabel(l).equals("File Level")), "File Level row present; labels=" + labels);
        assertTrue(labels.stream().anyMatch(l -> baseLabel(l).equals("Log File Pattern")), "Log File Pattern row present; labels=" + labels);
        assertTrue(labels.stream().anyMatch(l -> baseLabel(l).equals("File Size Limit (bytes)")), "File limit row present; labels=" + labels);
        assertTrue(labels.stream().anyMatch(l -> baseLabel(l).equals("File Count")), "File count row present; labels=" + labels);
    }

    @Test
    @DisplayName("the File Level editor is a CLASSIC (non-editable) level combo; the other editors EDITABLE combos: no plain text fields")
    void valueTypedEditor() {
        NodeLoggingPanel panel = newPanel();
        JComboBox<?> fileLevel = assertInstanceOf(JComboBox.class, editorForLabel(panel, "File Level"), "File Level should be a combo");
        assertFalse(fileLevel.isEditable(), "the File Level editor is a classic (non-editable) combo over the predefined level list");
        JComboBox<?> limit = assertInstanceOf(JComboBox.class, editorForLabel(panel, "File Size Limit (bytes)"), "limit should be an editable combo (no plain text field)");
        assertTrue(limit.isEditable(), "the File Size Limit editor is an editable combo");
        JComboBox<?> count = assertInstanceOf(JComboBox.class, editorForLabel(panel, "File Count"), "count should be an editable combo (no plain text field)");
        assertTrue(count.isEditable(), "the File Count editor is an editable combo");
        JComboBox<?> pattern = assertInstanceOf(JComboBox.class, editorForLabel(panel, "Log File Pattern"), "pattern should be an editable combo (no plain text field)");
        assertTrue(pattern.isEditable(), "the Log File Pattern editor is an editable combo");
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
        assertTrue(labels.stream().anyMatch(l -> l != null && l.startsWith("File Level") && l.endsWith("*")),
                "the changed row carries the trailing star; labels=" + labels);
        assertFalse(labels.stream().anyMatch(l -> l != null && l.startsWith("File Count") && l.endsWith("*")),
                "an untouched row must not be starred; labels=" + labels);
    }

    @Test
    @DisplayName("the applied profile (per-node assignment SSOT) is selected by default in the profile menu")
    void appliedProfileIsSelectedByDefault() throws Exception {
        LoggingAssignmentStore store = new LoggingAssignmentStore();
        LoggingProfileRepository repo = new LoggingProfileRepository();
        String node = "zz-applied-default-node";
        String profile = "zz-applied-default-" + System.nanoTime();
        try {
            repo.create(ModuleIds.NODE, profile, new Properties());
            store.setAssignmentForModule(node, ModuleIds.NODE, profile);

            final NodeLoggingPanel[] holder = new NodeLoggingPanel[1];
            onEdt(() -> holder[0] = new NodeLoggingPanel(node, () -> { }));
            assertEquals(profile, holder[0].getProfileCombo().getSelectedItem(),
                    "the applied profile is pre-selected in the profile menu");
        } finally {
            try {
                store.clearAssignment(node);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
            try {
                repo.delete(ModuleIds.NODE, profile);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    @Test
    @DisplayName("without any applied profile the virtual 'Default' entry stays the default selection")
    void defaultEntrySelectedWhenNothingApplied() {
        // Guard: the assertion only holds when no CUSTOM node logging profile is applied
        // in the runtime conf (a custom applied marker would be the legitimate selection).
        try {
            String applied = new LoggingProfileRepository().getApplied(ModuleIds.NODE);
            if (!application.utils.config.ReservedNames.isDefaultMarker(applied)) {
                return;
            }
        } catch (Exception ignored) {
            // unreadable marker — keep asserting the default
        }
        NodeLoggingPanel panel = newPanel();
        assertEquals(ModuleLoggingProfilePanel.DEFAULT_PROFILE_ENTRY,
                panel.getProfileCombo().getSelectedItem(),
                "nothing applied — the virtual 'Default' entry stays selected");
    }

    @Test
    @DisplayName("an unassigned node profile marks the virtual 'Default' entry as applied — the module marker never leaks into the node tab")
    void unassignedNodeProfileMarksDefaultAsApplied() throws Exception {
        LoggingProfileRepository repo = new LoggingProfileRepository();
        LoggingAssignmentStore store = new LoggingAssignmentStore();
        String profile = "zz-node-tab-no-leak-" + System.nanoTime();
        String node = "zz-node-tab-no-leak-node";
        String previousMarker = null;
        boolean hadMarker = false;
        try {
            try {
                previousMarker = repo.getApplied(ModuleIds.NODE);
                hadMarker = previousMarker != null;
            } catch (Exception ignored) {
                // unreadable marker — treat as absent
            }
            // The MODULE tab shows this profile as the applied one...
            repo.create(ModuleIds.NODE, profile, new Properties());
            repo.setApplied(ModuleIds.NODE, profile);

            final NodeLoggingPanel[] holder = new NodeLoggingPanel[1];
            onEdt(() -> holder[0] = new NodeLoggingPanel(node, () -> { }));
            NodeLoggingPanel panel = holder[0];

            // ...but the node tab (no per-node assignment) shows the built-in default.
            assertEquals(ModuleLoggingProfilePanel.DEFAULT_PROFILE_ENTRY,
                    panel.getProfileCombo().getSelectedItem(),
                    "an unassigned node profile keeps the virtual 'Default' entry selected — "
                            + "the module's applied profile is NOT pre-selected");

            @SuppressWarnings("unchecked")
            ListCellRenderer<String> renderer = (ListCellRenderer<String>) panel.getProfileCombo().getRenderer();
            JList<String> list = new JList<>();
            JLabel defaultCell = (JLabel) renderer.getListCellRendererComponent(
                    list, ModuleLoggingProfilePanel.DEFAULT_PROFILE_ENTRY, -1, false, false);
            assertNotNull(defaultCell.getIcon(),
                    "the virtual 'Default' entry is marked applied (the node runs with the built-in logging-default)");
            assertEquals(GuiColors.getApplied(), defaultCell.getForeground(),
                    "the 'Default' entry is painted with the applied color");
            JLabel moduleAppliedCell = (JLabel) renderer.getListCellRendererComponent(list, profile, -1, false, false);
            assertNull(moduleAppliedCell.getIcon(),
                    "the module's applied profile is NOT marked applied on the node tab");
        } finally {
            try {
                repo.setApplied(ModuleIds.NODE, hadMarker ? previousMarker : null);
            } catch (Exception ignored) {
                // best-effort restore
            }
            try {
                repo.delete(ModuleIds.NODE, profile);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
            try {
                store.clearAssignment(node);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    @Test
    @DisplayName("applying the virtual 'Default' profile CLEARS the node's custom assignment (no literal 'Default' in the SSOT)")
    void applyingDefaultClearsThePerNodeAssignment() {
        LoggingAssignmentStore store = new LoggingAssignmentStore();
        String node = "zz-default-clear-node";
        try {
            store.setAssignmentForModule(node, ModuleIds.NODE, "someprofile");

            final NodeLoggingPanel[] holder = new NodeLoggingPanel[1];
            onEdt(() -> holder[0] = new NodeLoggingPanel(node, () -> { }));
            holder[0].persistPerNodeAssignment(ModuleLoggingProfilePanel.DEFAULT_PROFILE_ENTRY);

            // Read via a FRESH store (the app's access pattern: a new
            // LoggingAssignmentStore per operation) — a long-lived store
            // instance caches the ProfileData it first loaded.
            assertNull(new LoggingAssignmentStore().getAssignment(node).get(ModuleIds.NODE),
                    "applying the virtual 'Default' entry clears the node's assignment, so the runtime "
                            + "falls back to the built-in logging-default");
        } finally {
            try {
                store.clearAssignment(node);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    @Test
    @DisplayName("the per-node assigned profile values are applied-colored regardless of a shared baseline")
    void assignedProfileUsesItsSavedValuesAsAppliedBaseline() throws Exception {
        LoggingAssignmentStore store = new LoggingAssignmentStore();
        LoggingProfileRepository repository = new LoggingProfileRepository();
        String node = "zz-applied-baseline-node-" + System.nanoTime();
        String profile = "zz-applied-baseline-profile-" + System.nanoTime();
        Properties values = new Properties();
        values.setProperty("java.util.logging.FileHandler.level", "WARNING");
        Properties sharedBaseline = new Properties();
        sharedBaseline.setProperty("java.util.logging.FileHandler.level", "INFO");
        try {
            repository.create(ModuleIds.NODE, profile, values);
            repository.rememberAppliedBaseline(ModuleIds.NODE, profile, sharedBaseline);
            store.setAssignmentForModule(node, ModuleIds.NODE, profile);
            final NodeLoggingPanel[] holder = new NodeLoggingPanel[1];
            onEdt(() -> holder[0] = new NodeLoggingPanel(node, () -> { }));

            assertEquals(profile, new LoggingAssignmentStore().getAssignment(node).get(ModuleIds.NODE),
                    "the selected profile is recorded as this node's assignment");
            assertEquals("INFO", repository.loadAppliedBaseline(ModuleIds.NODE, profile)
                            .getProperty("java.util.logging.FileHandler.level"),
                    "the shared module-level baseline is not overwritten by a per-node assignment");
            JComboBox<?> fileLevel = assertInstanceOf(JComboBox.class, editorForLabel(holder[0], "File Level"));
            assertEquals(GuiColors.getApplied(), fileLevel.getForeground(),
                    "the assigned profile's saved WARNING value matches its per-node applied baseline, "
                            + "regardless of the module-level INFO baseline");
        } finally {
            try {
                store.clearAssignment(node);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
            try {
                repository.delete(ModuleIds.NODE, profile);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    @Test
    @DisplayName("init states are visible and colored in node-profile logging dropdowns")
    void savedInitIsShownForNodeProfileAndHighlightedInDropdown() throws Exception {
        LoggingAssignmentStore store = new LoggingAssignmentStore();
        LoggingProfileRepository repository = new LoggingProfileRepository();
        String node = "zz-saved-init-node-" + System.nanoTime();
        String appliedProfile = "zz-saved-init-applied-" + System.nanoTime();
        String savedProfile = "zz-saved-init-saved-" + System.nanoTime();
        Properties appliedValues = new Properties();
        appliedValues.setProperty("java.util.logging.FileHandler.level", "INFO");
        Properties savedValues = new Properties();
        savedValues.setProperty("java.util.logging.FileHandler.level", "WARNING");
        try {
            repository.create(ModuleIds.NODE, appliedProfile, appliedValues);
            repository.create(ModuleIds.NODE, savedProfile, savedValues);
            store.setAssignmentForModule(node, ModuleIds.NODE, appliedProfile);

            final NodeLoggingPanel[] holder = new NodeLoggingPanel[1];
            onEdt(() -> {
                holder[0] = new NodeLoggingPanel(node, () -> { });
                holder[0].getProfileCombo().setSelectedItem(savedProfile);
            });
            NodeLoggingPanel panel = holder[0];

            JCheckBox savedInitFilter = findCheckBox(panel, "Saved Init");
            assertNotNull(savedInitFilter, "the node-profile logging filter includes Saved Init");
            assertTrue(savedInitFilter.isVisible(), "the node-profile Saved Init filter is visible");
            JCheckBox unsavedInitFilter = findCheckBox(panel, "Unsaved Init");
            assertNotNull(unsavedInitFilter, "the node-profile logging filter includes Unsaved Init");

            JComboBox<?> fileLevel = assertInstanceOf(JComboBox.class, editorForLabel(panel, "File Level"));
            @SuppressWarnings("unchecked")
            ListCellRenderer<Object> renderer = (ListCellRenderer<Object>) fileLevel.getRenderer();
            JLabel warningOption = (JLabel) renderer.getListCellRendererComponent(
                    new JList<>(), "WARNING", -1, false, false);
            assertEquals(GuiColors.getSavedInit(), warningOption.getForeground(),
                    "the saved init level uses the Saved Init font color in the dropdown");
            assertEquals(new JList<>().getBackground(), warningOption.getBackground(),
                    "the Saved Init option keeps the normal dropdown background");
            JList<String> options = new JList<>();
            JLabel selectedWarning = (JLabel) renderer.getListCellRendererComponent(
                    options, "WARNING", -1, true, false);
            assertEquals(GuiColors.getSavedInit(), selectedWarning.getForeground(),
                    "hovering a Saved Init level preserves its state font color");
            assertEquals(options.getSelectionBackground(), selectedWarning.getBackground(),
                    "hovering keeps the normal selection background");
            assertEquals(GuiColors.getSavedInit(), findLabel(panel, "File Level").getForeground(),
                    "the current row also reflects its Saved Init state");

            onEdt(() -> fileLevel.setSelectedItem("SEVERE"));
            onEdt(findButtonByTooltip(panel, "Save<br>")::doClick);
            onEdt(() -> fileLevel.setSelectedItem("WARNING"));
            assertEquals(GuiColors.getUnsavedInit(), findLabel(panel, "File Level *").getForeground(),
                    "returning to init after saving a different level yields Unsaved Init");
            assertEquals(GuiColors.getUnsavedInit(), fileLevel.getForeground(),
                    "the node logging editor uses the Unsaved Init palette color");
            assertEquals(GuiColors.getUnsavedInit(),
                    renderer.getListCellRendererComponent(new JList<>(), "WARNING", -1, false, false)
                            .getForeground(),
                    "the init dropdown option uses the Unsaved Init color after saved diverges");
        } finally {
            try {
                store.clearAssignment(node);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
            try {
                repository.delete(ModuleIds.NODE, appliedProfile);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
            try {
                repository.delete(ModuleIds.NODE, savedProfile);
            } catch (Exception ignored) {
                // best-effort cleanup
            }
        }
    }

    @Test
    @DisplayName("the apply dialog message is the short form: profile + target node profile, then the restart outcome")
    void applyMessageStatesPerNodeScope() {
        final NodeLoggingPanel[] holder = new NodeLoggingPanel[1];
        onEdt(() -> holder[0] = new NodeLoggingPanel("zz-msg-node", () -> { }));
        NodeLoggingPanel panel = holder[0];

        String msg = panel.nodeApplyMessage("myprofile", false);
        assertTrue(msg.startsWith("Apply logging profile 'myprofile' to node profile 'zz-msg-node'?"),
                "the message asks for confirmation naming the profile and the bound node profile");
        assertTrue(msg.contains("The node is not running"),
                "for a non-running node the message states that the node will not be started");
        assertTrue(msg.contains("the change takes effect the next time the node starts"),
                "the message states when the change takes effect");

        String defMsg = panel.nodeApplyMessage(ModuleLoggingProfilePanel.DEFAULT_PROFILE_ENTRY, true);
        assertTrue(defMsg.startsWith("Apply logging profile '"
                        + ModuleLoggingProfilePanel.DEFAULT_PROFILE_ENTRY + "' to node profile 'zz-msg-node'?"),
                "the 'Default' message uses the same short form");
    }

    @Test
    @DisplayName("the node Apply tooltip states link + saved values applied + node restart (module wording replaced)")
    void applyTooltipStatesNodeScope() {
        NodeLoggingPanel panel = newPanel();
        JButton apply = findButtonByTooltip(panel, "Apply<br>");
        assertNotNull(apply, "the Apply button is present");
        String tooltip = apply.getToolTipText();
        assertTrue(tooltip.contains("Apply<br><br>Links the selected logging profile to this node profile"),
                "the tooltip opens with the node-scoped link-to-the-node statement");
        assertTrue(tooltip.contains("applies its saved values to the node"),
                "the tooltip states that the profile's saved values are applied to the node");
        assertTrue(tooltip.contains("Applying restarts the node so the saved changes take effect"),
                "the tooltip states that applying restarts the node for the saved changes to take effect");
        assertFalse(tooltip.contains("Marks the selected profile as applied"),
                "the module-scoped wording is replaced on the node tab");
        assertFalse(tooltip.contains("module's applied reference"),
                "the module-centric description is not shown on the node tab");
    }

    @Test
    @DisplayName("bound panel: an editable combo's status tooltip survives an L&F (updateUI) pass")
    void editableComboStatusTooltipSurvivesLafPass() {
        final NodeLoggingPanel[] holder = new NodeLoggingPanel[1];
        onEdt(() -> holder[0] = new NodeLoggingPanel("zz-laf-node", () -> { }));
        NodeLoggingPanel panel = holder[0];

        JComboBox<?> combo = assertInstanceOf(JComboBox.class, editorForLabel(panel, "handlers"),
                "the 'handlers' row (a non-level value) is an editable combo");
        assertTrue(combo.isEditable(), "the non-level rows are editable combos");
        JTextField original = editorTextFieldOf((JComponent) combo.getEditor().getEditorComponent());
        assertNotNull(original, "the editable combo carries an editor text field");
        assertNotNull(original.getToolTipText(),
                "the editor field starts with the status tooltip (the hover tooltip of the row)");
        assertEquals(combo.getToolTipText(), original.getToolTipText(),
                "the field and the combo carry the same status tooltip");

        // Simulate an L&F / theme update pass: the walk hits the panel first
        // and then its descendants — an editable combo's updateUI() REPLACES
        // its editor text field (FlatLaf), so the tooltip styled onto the old
        // field would be lost (no status tooltip on hover in the profile
        // logging tab).
        onEdt(() -> {
            panel.updateUI();
            updateUiRecursively(panel);
        });
        // Pump the deferred re-style the panel scheduled after the pass.
        onEdt(() -> { });

        JTextField fresh = editorTextFieldOf((JComponent) combo.getEditor().getEditorComponent());
        assertNotNull(fresh, "the editable combo still carries an editor text field");
        assertEquals(combo.getToolTipText(), fresh.getToolTipText(),
                "after the L&F pass the (possibly replaced) editor field carries the same status tooltip");
        assertNotNull(fresh.getToolTipText(), "the status tooltip survives the L&F pass");
        assertTrue(fresh.getToolTipText().startsWith("Status: "),
                "the surviving tooltip is the status one: " + fresh.getToolTipText());
        if (original != fresh) {
            assertEquals(original.getForeground(), fresh.getForeground(),
                    "the state font color survives on the replaced editor field");
        }
    }

    /** The (first) text field inside the given component tree (a combo's editor text). */
    private static JTextField editorTextFieldOf(JComponent component) {
        if (component instanceof JTextField textField) {
            return textField;
        }
        if (component instanceof Container) {
            for (Component child : ((Container) component).getComponents()) {
                if (child instanceof JComponent) {
                    JTextField found = editorTextFieldOf((JComponent) child);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    /** Recursively applies the updateUI() pass an L&F change walks the tree with. */
    private static void updateUiRecursively(JComponent component) {
        component.updateUI();
        if (component instanceof Container container) {
            for (Component child : container.getComponents()) {
                if (child instanceof JComponent jc) {
                    updateUiRecursively(jc);
                }
            }
        }
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
            JComponent editor = editorForLabel(p, "File Count"); // editable combo, default "1"
            editorText(editor).setText("2");
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
            editorText(editorForLabel(p, "File Count")).setText("2"); // make it unsaved
            panelHolder[0] = p;
            boxHolder[0] = findCheckBox(p, "Unsaved");
        });
        assertNotNull(boxHolder[0], "the 'Unsaved' filter checkbox is present");

        final JCheckBox box = boxHolder[0];
        // doClick() (a simulated user click) — a programmatic setSelected does
        // not fire the ActionEvent that drives the row re-render.
        onEdt(box::doClick); // uncheck → hide unsaved rows
        assertFalse(hasLabel(panelHolder[0], "File Count"),
                "the unsaved row is hidden while 'Unsaved' is off");

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
        return allLabels(root).stream().anyMatch(t2 -> baseLabel(text).equals(baseLabel(t2)));
    }

    /** The text component carrying a row editor's value (a plain field, or the EDITOR TEXT of an editable combo). */
    private static JTextField editorText(JComponent editor) {
        if (editor instanceof JTextField textField) {
            return textField;
        }
        if (editor instanceof JComboBox<?> combo) {
            JComponent editorComponent = (JComponent) combo.getEditor().getEditorComponent();
            JTextField textField = editorTextComponent(editorComponent);
            assertNotNull(textField, "the combo's editor text is present");
            return textField;
        }
        throw new AssertionError("no text editor: " + editor);
    }

    private static JTextField editorTextComponent(JComponent component) {
        if (component instanceof JTextField textField) {
            return textField;
        }
        if (component instanceof Container) {
            for (Component child : component.getComponents()) {
                if (child instanceof JComponent) {
                    JTextField found = editorTextComponent((JComponent) child);
                    if (found != null) {
                        return found;
                    }
                }
            }
        }
        return null;
    }

    @Test
    @DisplayName("search row: Show values / Search left-aligned with uniform gaps; profile combo at the toolbar row's right end")
    void searchRowAlignment() {
        NodeLoggingPanel panel = newPanel();
        onEdt(() -> {
            panel.enableSearch(); // idempotent: the constructor already enabled it
            panel.setSize(900, 600);
            layoutRecursively(panel);
        });

        Container searchBox = findTitled(panel, "Search");
        Container showValues = findTitled(panel, "Show values");
        Container levelsFrame = findTitled(panel, "Logger levels");
        assertNull(findTitled(panel, "Profile"),
                "the former 'Profile' titled box must be gone (the selector is a plain combo on the toolbar row)");
        assertNotNull(searchBox, "the 'Search' titled box is present");
        assertNotNull(showValues, "the 'Show values' titled box is present");
        assertNotNull(levelsFrame, "the 'Logger levels' titled frame is present");

        // The search row carries the node configuration panel's search-row
        // left margin: the row's FIRST box (the "Show values" filter) starts
        // 10px right of the "Logger levels" frame (the frame runs flush,
        // like the configuration tab's tab area).
        assertEquals(horizontalPosition(panel, showValues) - horizontalPosition(panel, levelsFrame), 10,
                "the search row's first box must start 10px right of the 'Logger levels' frame (the configuration panel's search-row left margin)");

        // The row order: the "Show values" state filter first and the
        // "Search" box second (the profile selector left this row — it now
        // sits on the toolbar row above, at the RIGHT end after the buttons).
        assertTrue(horizontalPosition(panel, showValues) < horizontalPosition(panel, searchBox),
                "the search row order must be Show values, Search");
        // Uniform 4px gap between the two boxes.
        assertEquals(4, horizontalPosition(panel, searchBox)
                        - (horizontalPosition(panel, showValues) + showValues.getWidth()),
                "the gap between Show values and Search must be the row's uniform 4px gap");
        // Uniform heights across the row's titled boxes.
        assertEquals(searchBox.getHeight(), showValues.getHeight(),
                "the Search and Show values boxes must have the same height");

        // The profile combo: the LAST element of the toolbar row (after the
        // buttons), on the same row, at the shared toolbar row gap.
        JComboBox<String> profile = panel.getProfileCombo();
        Container toolbarRow = (Container) profile.getParent();
        int last = toolbarRow.getComponentCount() - 1;
        assertSame(profile, toolbarRow.getComponent(last),
                "the profile combo must be the LAST element of the toolbar row");
        Component previous = toolbarRow.getComponent(last - 1);
        assertInstanceOf(JButton.class, previous,
                "the element before the profile combo must be a toolbar button");
        assertTrue(horizontalPosition(panel, previous) + previous.getWidth() <= horizontalPosition(panel, profile),
                "the profile combo must sit to the RIGHT of the toolbar buttons");
        assertTrue(Math.abs(verticalPosition(panel, profile) - verticalPosition(panel, previous)) <= 24,
                "the profile combo must sit on the SAME row as the toolbar buttons");
        assertEquals(ConfigurationUtils.PROFILE_TOOLBAR_GAP,
                horizontalPosition(panel, profile) - (horizontalPosition(panel, previous) + previous.getWidth()),
                "the gap between the toolbar buttons and the profile combo must be the shared toolbar row gap");
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

    /** The y position of {@code c} relative to {@code root} (walking up the parents). */
    private static int verticalPosition(Container root, Component c) {
        int y = c.getY();
        Component p = c.getParent();
        while (p != null && p != root) {
            y += p.getY();
            p = p.getParent();
        }
        return y;
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

    /**
     * Whether the label shows the row name, ignoring the trailing unsaved star
     * and the "(=...)" initial-value annotation (so a row is found by its plain
     * label regardless of its unsaved / initial-value state).
     */
    private static boolean labelMatches(JLabel label, String text) {
        return baseLabel(text).equals(baseLabel(label.getText()));
    }

    /** A row's base label text: star and "(=...)" initial-value annotation stripped. */
    private static String baseLabel(String text) {
        if (text == null) {
            return "";
        }
        int p = text.indexOf(" (=");
        if (p >= 0) {
            text = text.substring(0, p);
        }
        if (text.endsWith(" *")) {
            text = text.substring(0, text.length() - 2);
        }
        return text;
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
