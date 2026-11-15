package application.module.node.gui.configuration;

import application.module.node.props.Props;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NodeConfigurationPanelShrinkTest {

    private static final String PROFILE = "shrink-test";

    @org.junit.jupiter.api.BeforeEach
    void setUp() {
        jiconfont.swing.IconFontSwing.register(
                jiconfont.icons.font_awesome.FontAwesome.getIconFont());
    }

    /** Creates the panel and waits for the async initUI() to finish. */
    private static NodeConfigurationPanel buildPanel() throws Exception {
        final NodeConfigurationPanel[] ref = new NodeConfigurationPanel[1];
        final Throwable[] error = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                ref[0] = new NodeConfigurationPanel(null, "./conf", null, null, PROFILE);
            } catch (Throwable t) {
                error[0] = t;
            }
        });
        if (error[0] != null) throw new RuntimeException(error[0]);
        // initUI() is posted via invokeLater; wait for it to complete
        final CountDownLatch latch = new CountDownLatch(1);
        SwingUtilities.invokeLater(() -> latch.countDown());
        assertTrue(latch.await(5, TimeUnit.SECONDS), "initUI did not complete");
        return ref[0];
    }
    @Test
    void inputColumnShrinksWhenPortIsValid() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        NodeConfigurationPanel panel = buildPanel();
        final Throwable[] error = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                JFrame frame = new JFrame("t");
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                frame.add(panel);
                frame.setSize(1000, 600);
                frame.setLocation(0, 0);
                frame.setVisible(true);
                JTabbedPane tabs = findComp(panel, c -> c instanceof JTabbedPane);
                assertNotNull(tabs, "no JTabbedPane");
                JScrollPane sp = (JScrollPane) tabs.getComponentAt(0);
                JPanel cat = findCat(sp);
                JComponent vw = (JComponent) sp.getViewport().getView();
                vw.setSize(943, 500); vw.doLayout();
                int wIn = inW(cat), wLab = labW(cat), wVp = 943;
                vw.setSize(483, 500); vw.doLayout();
                int nIn = inW(cat), nLab = labW(cat), nVp = 483;
                System.out.printf("VALID: WIDE in=%d lab=%d vp=%d -> NAR in=%d lab=%d vp=%d%n",
                        wIn, wLab, wVp, nIn, nLab, nVp);
                assertTrue(nVp < wVp, "vp narrow");
                assertTrue(nIn < wIn, "input shrink: " + wIn + " -> " + nIn);
                assertEquals(wLab, nLab, "label fixed");
                frame.dispose();
            } catch (Throwable t) { error[0] = t; }
        });
        if (error[0] != null) throw new RuntimeException(error[0]);
    }

    @Test
    void inputColumnShrinksWhenPortIsInvalid() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(GraphicsEnvironment.isHeadless());
        NodeConfigurationPanel panel = buildPanel();
        final Throwable[] error = new Throwable[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                JFrame frame = new JFrame("t");
                frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
                frame.add(panel);
                frame.setSize(1000, 600);
                frame.setLocation(0, 0);
                frame.setVisible(true);
                JTabbedPane tabs = findComp(panel, c -> c instanceof JTabbedPane);
                assertNotNull(tabs, "no JTabbedPane");
                JScrollPane sp = (JScrollPane) tabs.getComponentAt(0);
                JPanel cat = findCat(sp);
                JComboBox<?> combo = findComp(cat, c ->
                        c instanceof JComboBox<?> cb && cb.isEditable()
                        && isPort(cb));
                assertNotNull(combo, "port combo");
                JTextField ed = (JTextField) combo.getEditor().getEditorComponent();
                ed.setText("99999");
                Method mv = NodeConfigurationPanel.class
                        .getDeclaredMethod("updatePortValidation",
                                application.module.node.props.Prop.class);
                mv.setAccessible(true);
                mv.invoke(panel, Props.API_PORT);
                sleep(500);
                JTextArea vLabel = findComp(cat, c ->
                        c instanceof JTextArea ta && !ta.isEditable() && ta.isVisible());
                assertNotNull(vLabel, "validation label");
                assertTrue(vLabel.isVisible(), "label visible");
                System.out.println("Validation: '" + vLabel.getText()
                        + "' prefW=" + vLabel.getPreferredSize().width);
                JComponent vw2 = (JComponent) sp.getViewport().getView();
                vw2.setSize(943, 500); vw2.doLayout();
                int wIn = inW(cat), wLab = labW(cat), wVp = 943;
                vw2.setSize(483, 500); vw2.doLayout();
                int nIn = inW(cat), nLab = labW(cat), nVp = 483;
                System.out.printf("INVALID: WIDE in=%d lab=%d vp=%d -> NAR in=%d lab=%d vp=%d%n",
                        wIn, wLab, wVp, nIn, nLab, nVp);
                assertTrue(nVp < wVp, "vp narrow");
                assertTrue(nIn < wIn, "input shrink invalid: " + wIn + " -> " + nIn);
                assertEquals(wLab, nLab, "label fixed invalid");
                frame.dispose();
            } catch (Throwable t) { error[0] = t; }
        });
        if (error[0] != null) throw new RuntimeException(error[0]);
    }
    private static JPanel findCat(JScrollPane sp) {
        JComponent v = (JComponent) sp.getViewport().getView();
        if (v instanceof JPanel pnl && pnl.getLayout() instanceof MigLayout) return pnl;
        for (Component c : v.getComponents()) {
            if (c instanceof JPanel pnl && pnl.getLayout() instanceof MigLayout) return pnl;
        }
        fail("no cat panel. view=" + v.getClass().getName());
        return null;
    }

    private static int inW(JPanel cat) {
        for (Component c : cat.getComponents()) {
            if (c instanceof JLabel || c instanceof JSeparator) continue;
            if (c instanceof JTextArea ta && !ta.isEditable()) continue;
            if (c instanceof JButton) continue;
            if (c.isVisible() && c.getWidth() > 0) return c.getWidth();
        }
        fail("no input");
        return 0;
    }

    private static int labW(JPanel cat) {
        for (Component c : cat.getComponents()) {
            if (c instanceof JLabel l && l.getWidth() > 0) return l.getWidth();
        }
        fail("no label");
        return 0;
    }

    @SuppressWarnings("unchecked")
    private static <T extends JComponent> T findComp(Container root,
                                                     Predicate<Component> f) {
        for (Component c : root.getComponents()) {
            if (f.test(c) && c instanceof JComponent jc) return (T) jc;
            if (c instanceof Container ct) {
                T r = findComp(ct, f);
                if (r != null) return r;
            }
        }
        return null;
    }

    private static boolean isPort(JComboBox<?> combo) {
        Container p = combo.getParent();
        if (p == null) return false;
        for (Component c : p.getComponents()) {
            if (c instanceof JLabel l && l.getText().toLowerCase().contains("port"))
                return true;
        }
        return false;
    }

    private static void sleep(long ms) {
        try { Thread.sleep(ms); } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}