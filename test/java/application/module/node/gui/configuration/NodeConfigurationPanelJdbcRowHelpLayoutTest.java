package application.module.node.gui.configuration;

import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.awt.GraphicsEnvironment;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * The "Linked Database Profile" checkbox row of the DB.Url (JDBC) property
 * row: when a question-mark help icon follows a check box, the two must sit
 * on the SAME row, the ? vertically centered on that row. The help button
 * therefore lives INSIDE the row's input wrapper (next to the checkbox),
 * not in the row's separate help column (which would span the tall JDBC
 * card and could not be centered on the checkbox row alone).
 */
class NodeConfigurationPanelJdbcRowHelpLayoutTest {

    private static final String PROFILE = "probe-jdbc-row-help";

    @Test
    void jdbcRowHelpIconSitsCenteredOnTheCheckboxRow() throws Exception {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        jiconfont.swing.IconFontSwing.register(
                jiconfont.icons.font_awesome.FontAwesome.getIconFont());
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
                JFrame owner = new JFrame("jdbc-row-help-layout");
                NodeConfigurationPanel panel = new NodeConfigurationPanel(null, "./conf", null, null, PROFILE);
                owner.add(panel);
                owner.setSize(900, 700);
                owner.setVisible(true);
                refs.set(new Object[] { panel, owner });
            } catch (Throwable t) {
                err[0] = new RuntimeException(t);
            }
        });
        if (err[0] != null) {
            throw err[0];
        }
        NodeConfigurationPanel panel = (NodeConfigurationPanel) refs.get()[0];
        JFrame owner = (JFrame) refs.get()[1];
        try {
            JComponent wrapper = null;
            for (int i = 0; i < 150 && wrapper == null; i++) {
                wrapper = findJdbcWrapper(panel);
                if (wrapper == null) {
                    Thread.sleep(100);
                }
            }
            assertNotNull(wrapper, "the DB.Url row wrapper must exist");
            final JComponent w = wrapper;

            final Component[] comps = new Component[2];
            SwingUtilities.invokeAndWait(() -> {
                JCheckBox checkbox = null;
                JButton helpBtn = null;
                for (Component c : w.getComponents()) {
                    if (c instanceof JCheckBox cb && "Linked Database Profile".equals(cb.getText())) {
                        checkbox = cb;
                    }
                }
                assertNotNull(checkbox, "the 'Linked Database Profile' checkbox must exist");
                for (Component c : w.getComponents()) {
                    if (c instanceof JButton) {
                        helpBtn = (JButton) c;
                    }
                }
                comps[0] = checkbox;
                comps[1] = helpBtn;
            });
            JCheckBox checkbox = (JCheckBox) comps[0];
            JButton helpBtn = (JButton) comps[1];
            assertNotNull(helpBtn, "the row's ? help icon must sit in the wrapper's checkbox row");
            assertNotSame(checkbox.getParent(), panel, "the checkbox is a direct wrapper child");
            assertTrue(w.isAncestorOf(helpBtn), "the ? help icon must live inside the row's wrapper");
            assertTrue(checkbox.getParent() == helpBtn.getParent(),
                    "the ? must share the checkbox's row container (the same row)");

            // Same row: the ? starts to the RIGHT of the checkbox...
            final java.awt.Point[] pos = { new java.awt.Point(), new java.awt.Point() };
            final java.awt.Dimension[] size = { new java.awt.Dimension(), new java.awt.Dimension() };
            SwingUtilities.invokeAndWait(() -> {
                checkbox.getLocation(pos[0]);
                helpBtn.getLocation(pos[1]);
                size[0] = checkbox.getSize();
                size[1] = helpBtn.getSize();
            });
            assertTrue(pos[1].x >= pos[0].x + size[0].width,
                    "the ? must sit to the right of the checkbox (? x=" + pos[1].x
                            + ", checkbox end x=" + (pos[0].x + size[0].width) + ")");
            // ...and is vertically CENTERED on that row (centers align).
            int checkboxCenterY = pos[0].y + size[0].height / 2;
            int helpCenterY = pos[1].y + size[1].height / 2;
            assertFalse(Math.abs(checkboxCenterY - helpCenterY) > 2,
                    "the ? must be vertically centered on the checkbox row (checkbox centerY=" + checkboxCenterY
                            + ", ? centerY=" + helpCenterY + ")");

            // The wrapper carries exactly the checkbox row's ? icon — the
            // row's model-level help part is gone (the ? travels inside the
            // wrapper).
            final int[] wrapperButtons = { -1 };
            SwingUtilities.invokeAndWait(() -> {
                int n = 0;
                for (Component c : w.getComponents()) {
                    if (c instanceof JButton) {
                        n++;
                    }
                }
                wrapperButtons[0] = n;
            });
            assertTrue(wrapperButtons[0] == 1,
                    "the wrapper carries exactly the checkbox row's ? icon, got " + wrapperButtons[0]);
        } finally {
            SwingUtilities.invokeAndWait(owner::dispose);
            if (!propsExisted) {
                try {
                    Files.deleteIfExists(props);
                } catch (Exception ignored) {
                    // best-effort cleanup
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // lookup helpers (the same pattern as the JDBC card-height test)
    // ------------------------------------------------------------------
    /** The DB.Url row's input wrapper (the Database tab's MigLayout panel with the checkbox). */
    private static JComponent findJdbcWrapper(NodeConfigurationPanel panel) {
        final JComponent[] out = new JComponent[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    Field f = NodeConfigurationPanel.class.getDeclaredField("categoryTabbedPane");
                    f.setAccessible(true);
                    JTabbedPane tabs = (JTabbedPane) f.get(panel);
                    for (int i = 0; i < tabs.getTabCount(); i++) {
                        if (!"Database".equals(tabs.getTitleAt(i))) {
                            continue;
                        }
                        findRow(tabs.getComponentAt(i), out);
                        return;
                    }
                } catch (Exception e) {
                    // panel not ready yet
                }
            });
        } catch (Exception ignored) {
            // not on the EDT / panel not ready
        }
        return out[0];
    }

    private static void findRow(Component c, JComponent[] out) {
        if (out[0] != null || !(c instanceof Container cc)) {
            return;
        }
        for (Component inner : cc.getComponents()) {
            if (inner instanceof JCheckBox cb && "Linked Database Profile".equals(cb.getText())) {
                if (cc.getLayout() != null && cc.getLayout().getClass().getSimpleName().startsWith("Mig")) {
                    out[0] = (JComponent) cc;
                }
                return;
            }
        }
        for (Component k : cc.getComponents()) {
            findRow(k, out);
        }
    }
}