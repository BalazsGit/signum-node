package application.module.node.gui.configuration;

import application.module.appearance.AppearanceModule;
import application.utils.gui.GuiColors;
import org.junit.jupiter.api.Test;

import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JTextField;
import javax.swing.JFrame;
import javax.swing.JCheckBox;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Regression test for the report: "when the global font size is changed,
 * the value-state color coding of some input fields disappears."
 *
 * A font / L&F change replaces the UI delegates, and every EDITABLE combo's
 * editor component is recreated by the new delegate — the foreground the
 * value-state color coding set on the old editor (and the JDBC section) is
 * lost. {@link NodeConfigurationPanel#updateUI()} must therefore re-apply
 * the colors (refreshUIColors) as its LAST step, after every re-style.
 *
 * The test makes the profile select dirty (its editor gets the unsaved
 * color), changes the global font size the same way the appearance panel
 * does (updateCommonFontKeys + a full UI tree update), and asserts that:
 * <ol>
 *   <li>the recreated editor keeps the dirty-state color, and</li>
 *   <li>even if the color were lost (simulated with a sentinel), the next
 *       UI refresh (panel.updateUI) restores the state color.</li>
 * </ol>
 */
class NodeConfigurationPanelFontChangeColorTest {

    private static final String PROFILE = "probe-font-color";
    private static final Color SENTINEL = Color.MAGENTA;
    private record EditorSnapshot(String text, Color foreground) { }

    private static void registerFontAwesome() {
        jiconfont.swing.IconFontSwing.register(
                jiconfont.icons.font_awesome.FontAwesome.getIconFont());
    }

    @Test
    void globalFontSizeChangePreservesValueStateColors() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        registerFontAwesome();
        Path props = Path.of("conf", "node", "profiles", PROFILE + ".properties");
        boolean propsExisted = Files.exists(props) && Files.size(props) > 0;
        if (!propsExisted) {
            Files.createDirectories(props.getParent());
            Files.deleteIfExists(props);
            Files.writeString(props,
                    "DB.Url=jdbc:sqlite:file:./database/SQLite/" + PROFILE + "/signum.sqlite.db" + System.lineSeparator());
        }

        AtomicReference<Object[]> refs = new AtomicReference<>();
        final Exception[] err = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                JFrame owner = new JFrame("font-change-color");
                NodeConfigurationPanel panel = new NodeConfigurationPanel(null, "./conf", null, null, PROFILE);
                owner.add(panel);
                owner.setSize(900, 700);
                owner.setVisible(true);
                refs.set(new Object[] { panel, owner });
            } catch (Throwable t) {
                err[0] = new RuntimeException(t);
            }
        });
        if (err[0] != null)
            throw err[0];
        NodeConfigurationPanel panel = (NodeConfigurationPanel) refs.get()[0];
        JFrame owner = (JFrame) refs.get()[1];
        try {
            JComponent wrapper = awaitJdbcWrapper(panel);
            assertNotNull(wrapper, "the DB.Url row wrapper must exist");
            JComponent jdbcPanel = (JComponent) wrapper.getClientProperty("profilePanel");
            assertNotNull(jdbcPanel, "the unified JDBC panel must exist");

            selectDatabaseTab(panel);
            settle(owner);

            Map<JComboBox<?>, EditorSnapshot> valueComboSnapshots = snapshotStateAwareCombos(panel);
            assertTrue(!valueComboSnapshots.isEmpty(),
                    "the node configuration panel must contain state-aware editable value combos");

            // Linked mode: the (editable, state-aware) profile select is the
            // component whose editor is recreated by an L&F/font change.
            JCheckBox checkbox = findCheckbox(wrapper);
            assertNotNull(checkbox, "the 'Linked Database Profile' checkbox must exist");
            SwingUtilities.invokeAndWait(checkbox::doClick);
            settle(owner);

            JComboBox<?> profileCombo = findProfileCombo(jdbcPanel);
            assertNotNull(profileCombo, "the editable Profile select must be visible in linked mode");
            JTextField editor0 = (JTextField) profileCombo.getEditor().getEditorComponent();
            assertNotNull(editor0, "the profile select must have a text editor");

            // Make the profile select dirty: the document listener resolves
            // the value state and colors the editor with the unsaved color.
            SwingUtilities.invokeAndWait(() -> editor0.setText("definitely-not-a-profile"));
            settle(owner);
            Color dirtyColor = editor0.getForeground();
            assertEquals(GuiColors.getUnsaved(), dirtyColor,
                    "the dirty profile select editor must carry the unsaved color before the font change");

            // The JDBC preview (a plain text field) must keep its color too.
            JTextField preview = findFirstTextField(jdbcPanel);
            assertNotNull(preview, "the JDBC URL preview field must exist");
            Color previewColor = preview.getForeground();

            // === The global font size change, exactly as the appearance
            // panel performs it: new Label.font + full UI tree update. ===
            Font before = labelFont();
            SwingUtilities.invokeAndWait(() -> {
                AppearanceModule.updateCommonFontKeys(new Font(before.getFamily(), before.getStyle(), before.getSize() + 3));
                SwingUtilities.updateComponentTreeUI(owner);
            });
            settle(owner);

            try {
                for (Map.Entry<JComboBox<?>, EditorSnapshot> entry : valueComboSnapshots.entrySet()) {
                    JComboBox<?> combo = entry.getKey();
                    EditorSnapshot snapshot = entry.getValue();
                    Object currentItem = combo.getEditor().getItem();
                    assertEquals(snapshot.text(), currentItem == null ? "" : currentItem.toString(),
                            "a font-only UI refresh must preserve every editable value");
                    assertEquals(snapshot.foreground(),
                            ((JComponent) combo.getEditor().getEditorComponent()).getForeground(),
                            "a font-only UI refresh must preserve each value's GUIColors state color");
                }

                JComboBox<?> combo1 = findProfileCombo(jdbcPanel);
                JTextField editor1 = (JTextField) combo1.getEditor().getEditorComponent();
                assertNotEquals(editor0, editor1,
                        "the font change must have recreated the combo editor (this is what loses the color)");
                assertEquals(dirtyColor, editor1.getForeground(),
                        "the RECREATED profile select editor must keep the dirty-state color after the font change");
                assertEquals(before.getSize() + 3, editor1.getFont().getSize(),
                        "the font change must have actually been applied to the editor");

                // Proof of the RE-COLORING path: simulate the reported bug
                // (the color is gone) and let the panel refresh its UI once
                // more — updateUI must restore the state color.
                SwingUtilities.invokeAndWait(() -> editor1.setForeground(SENTINEL));
                settle(owner);
                assertEquals(SENTINEL, editor1.getForeground());
                SwingUtilities.invokeAndWait(panel::updateUI);
                settle(owner);
                JComboBox<?> combo2 = findProfileCombo(jdbcPanel);
                JTextField editor2 = (JTextField) combo2.getEditor().getEditorComponent();
                assertEquals(dirtyColor, editor2.getForeground(),
                        "a UI refresh must re-apply the value-state color to the (new) editor");

                // The plain text field's color survives the font change.
                JTextField preview1 = findFirstTextField(jdbcPanel);
                assertEquals(previewColor, preview1.getForeground(),
                        "the JDBC preview field must keep its color across the font change");
            } finally {
                SwingUtilities.invokeAndWait(() -> AppearanceModule.updateCommonFontKeys(before));
                SwingUtilities.updateComponentTreeUI(owner);
            }
        } finally {
            SwingUtilities.invokeAndWait(owner::dispose);
            if (!propsExisted)
                Files.deleteIfExists(props);
        }
    }

    private static Font labelFont() throws Exception {
        final Font[] out = new Font[1];
        SwingUtilities.invokeAndWait(() -> out[0] = javax.swing.UIManager.getFont("Label.font"));
        if (out[0] == null)
            out[0] = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
        return out[0];
    }

    @SuppressWarnings("unchecked")
    private static Map<JComboBox<?>, EditorSnapshot> snapshotStateAwareCombos(NodeConfigurationPanel panel)
            throws Exception {
        Field field = NodeConfigurationPanel.class.getDeclaredField("stateAwareCombos");
        field.setAccessible(true);
        Map<JComboBox<String>, ?> combos = (Map<JComboBox<String>, ?>) field.get(panel);
        Map<JComboBox<?>, EditorSnapshot> snapshots = new HashMap<>();
        for (JComboBox<String> combo : combos.keySet()) {
            Object item = combo.getEditor().getItem();
            snapshots.put(combo, new EditorSnapshot(
                    item == null ? "" : item.toString(),
                    ((JComponent) combo.getEditor().getEditorComponent()).getForeground()));
        }
        return snapshots;
    }

    /** The visible editable combo inside the JDBC panel (the Profile select in linked mode). */
    private static JComboBox<?> findProfileCombo(JComponent jdbcPanel) throws Exception {
        final JComboBox<?>[] out = new JComboBox<?>[1];
        SwingUtilities.invokeAndWait(() -> findCombo(jdbcPanel, out));
        return out[0];
    }

    private static void findCombo(Component c, JComboBox<?>[] out) {
        if (out[0] != null)
            return;
        if (c instanceof JComboBox<?> cb && cb.isVisible() && cb.isEditable()) {
            out[0] = cb;
            return;
        }
        if (c instanceof Container cont) {
            for (Component k : cont.getComponents())
                findCombo(k, out);
        }
    }

    private static JTextField findFirstTextField(JComponent jdbcPanel) throws Exception {
        final JTextField[] out = new JTextField[1];
        SwingUtilities.invokeAndWait(() -> findTextField(jdbcPanel, out));
        return out[0];
    }

    private static void findTextField(Component c, JTextField[] out) {
        if (out[0] != null)
            return;
        if (c instanceof JTextField tf && !(tf.getParent() instanceof JComboBox) && ancestorVisible(c)) {
            out[0] = tf;
            return;
        }
        if (c instanceof Container cont) {
            for (Component k : cont.getComponents())
                findTextField(k, out);
        }
    }

    private static boolean ancestorVisible(Component c) {
        for (Component p = c; p != null; p = p.getParent())
            if (!p.isVisible())
                return false;
        return true;
    }

    // ------------------------------------------------------------------
    // setup helpers (same pattern as NodeConfigurationPanelJdbcCardHeightTest)
    // ------------------------------------------------------------------

    private static JComponent awaitJdbcWrapper(NodeConfigurationPanel panel) throws Exception {
        JTabbedPane[] tabs = new JTabbedPane[1];
        awaitUI(() -> {
            try {
                Field f = NodeConfigurationPanel.class.getDeclaredField("categoryTabbedPane");
                f.setAccessible(true);
                tabs[0] = (JTabbedPane) f.get(panel);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return true;
        });
        for (int i = 0; i < 150; i++) {
            JComponent found = findJdbcRowWrapper(tabs[0]);
            if (found != null)
                return found;
            Thread.sleep(100);
        }
        return null;
    }

    private static JComponent findJdbcRowWrapper(JTabbedPane tabs) throws Exception {
        JComponent[] out = new JComponent[1];
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                if (!"Database".equals(tabs.getTitleAt(i)))
                    continue;
                findRow(tabs.getComponentAt(i), out);
                if (out[0] != null)
                    return;
            }
        });
        return out[0];
    }

    private static void findRow(Component c, JComponent[] out) {
        if (c instanceof Container cc) {
            for (Component inner : cc.getComponents())
                if (inner instanceof JCheckBox cb && "Linked Database Profile".equals(cb.getText())) {
                    if (cc.getLayout() != null
                            && cc.getLayout().getClass().getSimpleName().startsWith("Mig"))
                        out[0] = (JComponent) cc;
                    return;
                }
            for (Component k : cc.getComponents())
                findRow(k, out);
        }
    }

    private static void selectDatabaseTab(NodeConfigurationPanel panel) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            try {
                Field f = NodeConfigurationPanel.class.getDeclaredField("categoryTabbedPane");
                f.setAccessible(true);
                JTabbedPane tabs = (JTabbedPane) f.get(panel);
                for (int i = 0; i < tabs.getTabCount(); i++) {
                    if ("Database".equals(tabs.getTitleAt(i)))
                        tabs.setSelectedIndex(i);
                }
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    /**
     * Sleep + EDT sync so pending revalidate/layout passes settle. The
     * forced {@code validate()} is load-bearing: a font change recreates
     * the editable combos' editors (SHOWN but not yet sized), and under load
     * the paint pass can outpace the layout pass — a MigLayout max-size
     * query on the unsized editor then NPEs (flaky in the full suite).
     */
    private static void settle(JFrame owner) throws Exception {
        for (int i = 0; i < 2; i++) {
            Thread.sleep(300);
            SwingUtilities.invokeAndWait(() -> owner.validate());
        }
    }

    private static JCheckBox findCheckbox(JComponent wrapper) throws Exception {
        JCheckBox[] out = new JCheckBox[1];
        SwingUtilities.invokeAndWait(() -> {
            for (Component c : wrapper.getComponents())
                if (c instanceof JCheckBox cb && "Linked Database Profile".equals(cb.getText()))
                    out[0] = cb;
        });
        return out[0];
    }

    private static void awaitUI(java.util.function.BooleanSupplier probe) throws Exception {
        final Exception[] err = new Exception[1];
        for (int i = 0; i < 100; i++) {
            final boolean[] ok = new boolean[1];
            try {
                SwingUtilities.invokeAndWait(() -> {
                    try {
                        ok[0] = probe.getAsBoolean();
                    } catch (Exception e) {
                        err[0] = e;
                    }
                });
            } catch (Exception e) {
                err[0] = e;
            }
            if (err[0] != null)
                throw err[0];
            if (ok[0])
                return;
            Thread.sleep(100);
        }
        assertTrue(false, "UI did not reach the awaited state");
    }
}