package application.module.node.gui.configuration;

import org.junit.jupiter.api.Test;

import javax.swing.JCheckBox;
import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression test for the "empty space under the JDBC URL preview" report:
 * switching the DB.Url row from the tall manual card to the shorter linked
 * profile card (and back) must resize the card to the ACTIVE card's height.
 *
 * Root cause: the card holder is a CardLayout panel whose default
 * getMinimumSize() is the max of ALL cards' minimums; the manual card's
 * minimum kept the MigLayout row inflated after the switch. The card panel
 * now overrides getPreferredSize/getMinimumSize to track the current card.
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
        registerFontAwesome();
        Path props = Path.of("conf", "node", "profiles", PROFILE + ".properties");
        boolean propsExisted = Files.exists(props);
        if (!propsExisted) {
            Files.createDirectories(props.getParent());
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
            JComponent card = (JComponent) wrapper.getComponent(1);

            // Select the Database tab so the row is laid out at its real
            // width (hidden-tab measurements happen at the default 80x30).
            selectDatabaseTab(panel);
            settle();

            JComponent manual = visibleCardChild(card, true);
            JComponent profile = visibleCardChild(card, false);
            JCheckBox checkbox = findCheckbox(wrapper);
            assertNotNull(checkbox, "the 'Linked Database Profile' checkbox must exist");

            // 1. Manual card: the card panel must fit the manual content.
            assertEquals(manual, currentCard(card), "the manual card must be active initially");
            int manualCardH = card.getHeight();
            assertEquals(maxVisibleBottom(manual), manualCardH,
                    "the card must be sized to the manual card's content (no dead space)");

            // 2. Switch to the linked profile card.
            SwingUtilities.invokeAndWait(checkbox::doClick);
            settle();
            assertEquals(profile, currentCard(card), "the profile card must be active after the check");
            int profileCardH = card.getHeight();
            assertTrue(profileCardH < manualCardH,
                    "the card must shrink for the shorter profile card (was " + manualCardH + ", now " + profileCardH + ")");
            assertEquals(maxVisibleBottom(profile), profileCardH,
                    "the card must be sized to the profile card's content (no dead space under the JDBC URL preview)");

            // 3. Switch back: the card must grow back to the manual height.
            SwingUtilities.invokeAndWait(checkbox::doClick);
            settle();
            assertEquals(manual, currentCard(card), "the manual card must be active after the uncheck");
            assertEquals(manualCardH, card.getHeight(), "the card must grow back to the manual card's height");
            assertEquals(maxVisibleBottom(manual), card.getHeight(),
                    "the card must again fit the manual card's content");
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

    /** The manual (JdbcManualConfigurationPanel) or the anonymous profile card. */
    private static JComponent visibleCardChild(JComponent card, boolean manual) throws Exception {
        JComponent[] out = new JComponent[1];
        SwingUtilities.invokeAndWait(() -> {
            for (Component c : card.getComponents()) {
                if (c instanceof JComponent jc && (jc instanceof JdbcManualConfigurationPanel) == manual)
                    out[0] = jc;
            }
        });
        return out[0];
    }

    /** The visible card of the CardLayout holder (the active one). */
    private static Component currentCard(JComponent card) throws Exception {
        Component[] out = new Component[1];
        SwingUtilities.invokeAndWait(() -> {
            for (Component c : card.getComponents())
                if (c.isVisible() && c.getHeight() > 0)
                    out[0] = c;
        });
        return out[0];
    }

    /** Max (y + height) of the visible direct children of a card. */
    private static int maxVisibleBottom(Container card) throws Exception {
        int[] out = new int[1];
        SwingUtilities.invokeAndWait(() -> {
            int bottom = 0;
            for (Component c : card.getComponents()) {
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
