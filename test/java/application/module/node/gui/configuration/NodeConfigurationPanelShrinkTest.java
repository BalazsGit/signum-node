package application.module.node.gui.configuration;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import application.module.node.profile.NodeProfile;
import java.awt.Component;
import java.awt.Window;
import java.util.concurrent.atomic.AtomicReference;

import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JViewport;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Non-wizard input column shrink test")
class NodeConfigurationPanelShrinkTest {

    private static final java.nio.file.Path PROFILE = java.nio.file.Path.of("test/node.properties");
    private static final AtomicReference<Window> windowRef = new AtomicReference<>();

    @AfterEach
    void tearDown() {
        Window w = windowRef.getAndSet(null);
        if (w != null) {
            SwingUtilities.invokeLater(w::dispose);
        }
    }

    @Test
    @DisplayName("input column shrinks when the scroll pane narrows")
    void inputColumnShrinksOnNarrow() throws Exception {
        final AtomicReference<NodeConfigurationPanel> panelRef = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            try {
                jiconfont.swing.IconFontSwing.register(
                        jiconfont.icons.font_awesome.FontAwesome.getIconFont());
                JFrame owner = new JFrame("shrink-test-owner");
                NodeConfigurationPanel panel = new NodeConfigurationPanel(null, "./conf", null, null, "node");
                owner.add(panel);
                owner.setSize(1000, 700);
                owner.setVisible(true);
                windowRef.set(owner);
                panelRef.set(panel);
            } catch (Throwable t) {
                error.set(t);
            }
        });
        assertNotNull(panelRef.get(), "panel constructable: " + error.get());
        SwingUtilities.invokeAndWait(() -> { });

        NodeConfigurationPanel panel = panelRef.get();

        final JScrollPane[] spHolder = new JScrollPane[1];
        SwingUtilities.invokeAndWait(() -> {
            try {
                java.lang.reflect.Field f = panel.getClass().getDeclaredField("categoryTabbedPane");
                f.setAccessible(true);
                JTabbedPane tabs = (JTabbedPane) f.get(panel);
                spHolder[0] = (JScrollPane) tabs.getComponentAt(0);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        SwingUtilities.invokeAndWait(() -> {
            JScrollPane sp = spHolder[0];
            JViewport vp = sp.getViewport();
            JPanel view = (JPanel) vp.getView();

            System.out.println("Panel preferred: " + view.getPreferredSize());
            System.out.println("Panel minimum:   " + view.getMinimumSize());
            System.out.println("Panel actual:    " + view.getSize());
            System.out.println("Viewport size:   " + vp.getSize());
            System.out.println("Scroll pane size:" + sp.getSize());
            System.out.println("Components in view:");
            for (Component c : view.getComponents()) {
                if (c instanceof JComboBox) {
                    System.out.println("  COMBO: pref=" + c.getPreferredSize() + " actual=" + c.getSize());
                } else if (c instanceof javax.swing.JLabel) {
                    System.out.println("  LABEL: pref=" + c.getPreferredSize() + " actual=" + c.getSize() + " text=" + ((javax.swing.JLabel) c).getText());
                }
            }
        });

        final int[] wide = new int[3];
        final int[] narrow = new int[3];

        SwingUtilities.invokeAndWait(() -> {
            JScrollPane sp = spHolder[0];
            JViewport vp = sp.getViewport();
            JPanel view = (JPanel) vp.getView();

            System.out.println("BEFORE: view.pref=" + view.getPreferredSize()
                    + " view.size=" + view.getSize()
                    + " vp.size=" + vp.getSize()
                    + " sp.size=" + sp.getSize());

            // WIDE: set viewport to 800
            vp.setSize(800, 600);
            vp.doLayout();
            view.doLayout();
            wide[0] = view.getWidth();
            for (Component c : view.getComponents()) {
                if (c instanceof JComboBox) { wide[1] = c.getWidth(); break; }
            }
            for (Component c : view.getComponents()) {
                if (c instanceof javax.swing.JLabel) { wide[2] = c.getWidth(); break; }
            }
            System.out.println("WIDE(800): view=" + view.getWidth()
                    + " input=" + wide[1] + " label=" + wide[2]
                    + " vp=" + vp.getWidth() + " view.pref=" + view.getPreferredSize().width);

            // NARROW: set viewport to 400
            vp.setSize(400, 600);
            vp.doLayout();
            view.doLayout();
            narrow[0] = view.getWidth();
            for (Component c : view.getComponents()) {
                if (c instanceof JComboBox) { narrow[1] = c.getWidth(); break; }
            }
            for (Component c : view.getComponents()) {
                if (c instanceof javax.swing.JLabel) { narrow[2] = c.getWidth(); break; }
            }
            System.out.println("NARROW(400): view=" + view.getWidth()
                    + " input=" + narrow[1] + " label=" + narrow[2]
                    + " vp=" + vp.getWidth() + " view.pref=" + view.getPreferredSize().width);
        });

        System.out.println("=== SHRINK TEST ===");
        System.out.println("WIDE:   view=" + wide[0] + " input=" + wide[1] + " label=" + wide[2]);
        System.out.println("NARROW: view=" + narrow[0] + " input=" + narrow[1] + " label=" + narrow[2]);
        System.out.println("DELTA:  view=" + (narrow[0]-wide[0]) + " input=" + (narrow[1]-wide[1]) + " label=" + (narrow[2]-wide[2]));
        System.out.println("==================");

        assertTrue(narrow[0] < wide[0], "view must shrink: " + wide[0] + " -> " + narrow[0]);
        assertTrue(narrow[1] < wide[1], "INPUT must shrink: " + wide[1] + " -> " + narrow[1]);
        assertTrue(Math.abs(wide[2]-narrow[2]) < Math.abs(wide[1]-narrow[1]),
                "label shrink must be < input shrink");
    }
}