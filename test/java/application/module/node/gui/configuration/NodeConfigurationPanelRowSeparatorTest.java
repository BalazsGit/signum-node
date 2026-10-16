package application.module.node.gui.configuration;

import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JSeparator;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Regression test for the misplaced DB.Url row separator: the JDBC row (and
 * the list-property rows) have NO help button in the outer property grid
 * (their ? icon travels inside the input), so the help column is still free
 * on the input row. A plain MigLayout {@code span} separator then lands in
 * that free column — a short horizontal line to the RIGHT of the JDBC card
 * (vertically centered, beside the "Path:" row) — instead of dropping to
 * its own full-width row under the "JDBC URL:" preview.
 * <p>
 * The fix is the {@code span all} separator constraint: MigLayout moves a
 * component whose span exceeds the row's right edge to the start of the next
 * row, where it spans the whole grid width.
 * </p>
 */
class NodeConfigurationPanelRowSeparatorTest {

    private static final String PROFILE = "probe-gap-test";

    /**
     * Same icon-font registration the application performs at startup (see
     * AppearanceModule#init).
     */
    private static void registerFontAwesome() {
        jiconfont.swing.IconFontSwing.register(
                jiconfont.icons.font_awesome.FontAwesome.getIconFont());
    }

    @Test
    void jdbcRowSeparatorSitsOnItsOwnFullWidthRow() throws Exception {
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
                JFrame owner = new JFrame("row-separator");
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
            JTabbedPane tabs = categoryTabs(panel);
            selectDatabaseTab(tabs);
            JComponent wrapper = awaitJdbcWrapper(panel);
            assertNotNull(wrapper, "the DB.Url row wrapper must exist");
            settle();

            // The tab panel that directly contains the wrapper (the row
            // separator is its direct child, right after the wrapper — the
            // JDBC row adds no help button in the OUTER grid).
            Container tabPanel = wrapper.getParent();
            JSeparator separator = null;
            for (int i = 0; i < tabPanel.getComponentCount() - 1; i++) {
                if (tabPanel.getComponent(i) == wrapper) {
                    separator = (JSeparator) tabPanel.getComponent(i + 1);
                    break;
                }
            }
            assertNotNull(separator, "the component after the JDBC wrapper must be the row separator");

            // The bug: the separator sat in the free help column of the
            // INPUT row (a short line right of the card, beside "Path:").
            // The fix: it must start a NEW row under the wrapper and span
            // the full grid width.
            int wrapperBottom = wrapper.getY() + wrapper.getHeight();
            int separatorTop = separator.getY();
            assertTrue(separatorTop >= wrapperBottom,
                    "the separator must start a NEW row under the JDBC card (separatorTop=" + separatorTop
                            + " < wrapperBottom=" + wrapperBottom + " means it sat in the free help column)");
            // Full-width: the separator spans the WHOLE grid (all columns,
            // starting at the label column) — it must start at or left of
            // the input column and be at least as wide as the input itself
            // (the bug rendered it as a short line of the free help column
            // alone, to the right of the card).
            assertTrue(separator.getX() <= wrapper.getX(),
                    "the separator must start at or left of the input column (separator.x=" + separator.getX()
                            + ", wrapper.x=" + wrapper.getX() + ")");
            assertTrue(separator.getWidth() >= wrapper.getWidth(),
                    "the separator must span at least the input width (separator=" + separator.getWidth()
                            + ", wrapper=" + wrapper.getWidth() + ")");
        } finally {
            SwingUtilities.invokeAndWait(owner::dispose);
            if (!propsExisted)
                Files.deleteIfExists(props);
        }
    }

    private static JTabbedPane categoryTabs(NodeConfigurationPanel panel) throws Exception {
        final JTabbedPane[] out = new JTabbedPane[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                Field f = NodeConfigurationPanel.class.getDeclaredField("categoryTabbedPane");
                f.setAccessible(true);
                out[0] = (JTabbedPane) f.get(panel);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
        return out[0];
    }

    private static void selectDatabaseTab(JTabbedPane tabs) throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            for (int i = 0; i < tabs.getTabCount(); i++) {
                if ("Database".equals(tabs.getTitleAt(i)))
                    tabs.setSelectedIndex(i);
            }
        });
    }

    private static JComponent awaitJdbcWrapper(NodeConfigurationPanel panel) throws Exception {
        JTabbedPane tabs = categoryTabs(panel);
        for (int i = 0; i < 150; i++) {
            JComponent[] out = new JComponent[1];
            SwingUtilities.invokeAndWait(() -> {
                for (int t = 0; t < tabs.getTabCount(); t++) {
                    if (!"Database".equals(tabs.getTitleAt(t)))
                        continue;
                    findWrapper(tabs.getComponentAt(t), out);
                    if (out[0] != null)
                        return;
                }
            });
            if (out[0] != null)
                return out[0];
            Thread.sleep(100);
        }
        return null;
    }

    /**
     * The row wrapper: the container that directly holds the "Linked
     * Database Profile" checkbox (searched at any depth — the tab page sits
     * inside JScrollPane -> JViewport).
     */
    private static void findWrapper(Component c, JComponent[] out) {
        if (c instanceof Container cc) {
            for (Component inner : cc.getComponents())
                if (inner instanceof javax.swing.JCheckBox cb && "Linked Database Profile".equals(cb.getText())) {
                    out[0] = (JComponent) cc;
                    return;
                }
            for (Component k : cc.getComponents())
                findWrapper(k, out);
        }
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
}
