package application.module.node.gui.configuration;

import org.junit.jupiter.api.Test;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Regression test for the "empty space under the JDBC URL preview" report:
 * switching the DB.Url row between manual mode (tall engine-aware fields) and
 * linked mode (short profile card) must resize the row to the MODE's height.
 *
 * The legacy root cause was a CardLayout card holder whose default
 * getMinimumSize() was the max of ALL cards' minimums; the unified
 * {@link JdbcConfigurationPanel} removes the outer holder (visibility is
 * driven by the mode instead of card switching), but its internal
 * SQLite/remote field group is a CardLayout that still overrides
 * getPreferredSize/getMinimumSize to track the current card — this test
 * guards both layers against a dead-space regression.
 */
class NodeConfigurationPanelJdbcCardHeightTest {

    private static final String PROFILE = "probe-gap-test";

    /**
     * Same icon-font registration the application performs at startup (see
     * AppearanceModule#init): without it, the FontAwesome icon labels in the
     * Database tab would wrap differently than in the real UI and the
     * measured heights would not represent the reported scenario.
     */
    private static void registerFontAwesome() {
        jiconfont.swing.IconFontSwing.register(
                jiconfont.icons.font_awesome.FontAwesome.getIconFont());
    }

    @Test
    void jdbcRowResizesToActiveCard() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        registerFontAwesome();
        Path props = Path.of("conf", "node", "profiles", PROFILE + ".properties");
        boolean propsExisted = Files.exists(props) && Files.size(props) > 0;
        if (!propsExisted) {
            Files.createDirectories(props.getParent());
            Files.deleteIfExists(props); // an empty leftover of a crashed run
            Files.writeString(props,
                    "DB.Url=jdbc:sqlite:file:./database/SQLite/" + PROFILE + "/signum.sqlite.db" + System.lineSeparator());
        }

        AtomicReference<Object[]> refs = new AtomicReference<>();
        final Exception[] err = new Exception[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                JFrame owner = new JFrame("jdbc-card-height");
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

            // Select the Database tab so the row is laid out at its real
            // width (hidden-tab measurements happen at the default 80x30).
            selectDatabaseTab(panel);
            settle();

            JCheckBox checkbox = findCheckbox(wrapper);
            assertNotNull(checkbox, "the 'Linked Database Profile' checkbox must exist");

            // 1. Manual mode: the panel must fit the manual content.
            final Object[] state0 = new Object[1];
            SwingUtilities.invokeAndWait(() -> state0[0] = ((JdbcConfigurationPanel) jdbcPanel).isLinkedMode());
            assertTrue(!(Boolean) state0[0], "the JDBC row must start in manual mode (no database link recorded)");
            int manualH = jdbcPanel.getHeight();
            assertEquals(visibleBottom(jdbcPanel), manualH,
                    "the panel must be sized to the manual content (no dead space)");

            // 2. Switch to the linked profile mode.
            SwingUtilities.invokeAndWait(checkbox::doClick);
            settle();
            final Object[] linked = new Object[2];
            SwingUtilities.invokeAndWait(() -> {
                JdbcConfigurationPanel j = (JdbcConfigurationPanel) jdbcPanel;
                linked[0] = j.isLinkedMode();
                linked[1] = j.getProfileName();
            });
            assertTrue((Boolean) linked[0], "the JDBC row must switch to linked mode after the check");
            assertNotNull(linked[1], "the linked mode must resolve a profile name");
            int linkedH = jdbcPanel.getHeight();
            // The unified panel keeps one row per slot: the linked mode
            // replaces the Path field with the (slightly taller) Profile
            // select, so the heights stay in lockstep — the mode switch is
            // proven by the row SET changing, not by a height drop.
            assertTrue(rowLabelVisible(jdbcPanel, "Profile:"),
                    "the linked mode must show the Profile row");
            assertFalse(rowLabelVisible(jdbcPanel, "Path:"),
                    "the linked mode must hide the Path row");
            assertEquals(visibleBottom(jdbcPanel), linkedH,
                    "the panel must be sized to the linked content (no dead space under the JDBC URL preview)");

            // 3. Switch back: the panel must grow back to the manual height.
            SwingUtilities.invokeAndWait(checkbox::doClick);
            settle();
            final Object[] state2 = new Object[1];
            SwingUtilities.invokeAndWait(() -> state2[0] = ((JdbcConfigurationPanel) jdbcPanel).isLinkedMode());
            assertTrue(!(Boolean) state2[0], "the JDBC row must be back in manual mode after the uncheck");
            assertEquals(manualH, jdbcPanel.getHeight(), "the panel must grow back to the manual mode's height");
            assertEquals(visibleBottom(jdbcPanel), jdbcPanel.getHeight(),
                    "the panel must again fit the manual content");
        } finally {
            SwingUtilities.invokeAndWait(owner::dispose);
            if (!propsExisted)
                Files.deleteIfExists(props);
        }
    }

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

    /**
     * The DB panel page sits inside JScrollPane -> JViewport; search for the
     * MigLayout row wrapper (the container that directly holds the
     * "Linked Database Profile" checkbox) at any depth.
     */
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

    /** Sleep + EDT sync so pending revalidate/layout passes settle. */
    private static void settle() throws Exception {
        for (int i = 0; i < 2; i++) {
            Thread.sleep(300);
            SwingUtilities.invokeAndWait(() -> {
                // no-op: EDT sync barrier
            });
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

    /** Whether a row label starting with the given base text is visible. */
    private static boolean rowLabelVisible(JComponent panel, String baseText) throws Exception {
        final boolean[] out = new boolean[1];
        SwingUtilities.invokeAndWait(() -> {
            for (Component c : panel.getComponents())
                if (c instanceof JLabel l && l.isVisible() && l.getText().startsWith(baseText)) {
                    out[0] = true;
                    return;
                }
        });
        return out[0];
    }

    /** Max (y + height) of the visible direct children of a panel. */
    private static int visibleBottom(Container panel) throws Exception {
        int[] out = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            int bottom = 0;
            for (Component c : panel.getComponents()) {
                if (c.isVisible())
                    bottom = Math.max(bottom, c.getY() + c.getHeight());
            }
            out[0] = bottom;
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
