package application.gui.shell;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.JTabbedPane;
import java.awt.Component;
import java.awt.Container;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link TabManager} — the module tabbed pane plus the
 * optional trailing button (the shutdown icon slot) at the end of the
 * tab row, hosted through FlatLaf's {@code JTabbedPane.trailingComponent}
 * client property.
 */
@DisplayName("TabManager Tests")
class TabManagerTest {

    // ====================================================================
    // Trailing button slot
    // ====================================================================

    @Nested
    @DisplayName("Trailing button slot")
    class TrailingButtonTests {

        @Test
        @DisplayName("the trailing button is installed as the pane's trailingComponent (inside the tab row)")
        void trailingButton_isInstalledAsTrailingComponent() {
            TabManager tm = new TabManager();
            JButton button = new JButton();
            tm.setTrailingComponent(button);

            JComponent comp = tm.getComponent();
            assertTrue(comp instanceof JTabbedPane,
                    "the exposed component is the module tabbed pane itself");
            JTabbedPane pane = (JTabbedPane) comp;
            Container trailing = (Container) pane.getClientProperty("JTabbedPane.trailingComponent");
            assertNotNull(trailing,
                    "the button must live in the row via the trailingComponent client property");
            assertSame(button, trailing.getComponent(0),
                    "the trailing container must hold the button");
        }

        @Test
        @DisplayName("a null trailing button installs nothing")
        void trailingButton_nullIsIgnored() {
            TabManager tm = new TabManager();
            tm.setTrailingComponent(null);
            JTabbedPane pane = (JTabbedPane) tm.getComponent();
            assertNull(pane.getClientProperty("JTabbedPane.trailingComponent"));
        }
    }

    // ====================================================================
    // Module tabs
    // ====================================================================

    @Nested
    @DisplayName("Module tabs")
    class ModuleTabTests {

        @Test
        @DisplayName("addModuleTab lands the tabs on the exposed pane")
        void addModuleTab_addsToThePane() {
            TabManager tm = new TabManager();
            tm.setTrailingComponent(new JButton());
            tm.addModuleTab("Node", new JPanel());
            tm.addModuleTab("Browser", new JPanel());

            JTabbedPane pane = (JTabbedPane) tm.getComponent();
            assertEquals(2, pane.getTabCount());
            assertEquals("Node", pane.getTitleAt(0));
            assertEquals("Browser", pane.getTitleAt(1));
            assertNotNull(pane.getComponentAt(0));
        }
    }
}