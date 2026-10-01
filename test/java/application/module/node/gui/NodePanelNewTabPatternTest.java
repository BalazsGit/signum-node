package application.module.node.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import javax.swing.JTabbedPane;
import javax.swing.SwingUtilities;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Verifies that the Node panel's profile tab strip follows the new-browsertab pattern: the
 * new-profile "+" is the tab row's trailing component (not a dedicated "+" tab) and the
 * profile tabs are closable via the native "X". The tab SIZING follows the app-wide
 * convention (preferred width, no fixed tab width) rather than the browser strip's fixed
 * width, so the profile tabs match the other JTabbedPanes (logging/console/configuration).
 * <p>
 * These client properties are set synchronously in the {@link NodePanel} constructor, so the
 * assertions are deterministic regardless of the (async) profile loading.
 */
@DisplayName("NodePanel new-browsertab tab strip")
class NodePanelNewTabPatternTest {

    @Test
    @DisplayName("profile tab strip uses a trailing new-profile button and closable tabs, with app-wide sizing")
    void profileTabStrip_usesTrailingPlus_closableTabs_appWideSizing() throws Exception {
        final AtomicReference<JTabbedPane> paneRef = new AtomicReference<>();
        final AtomicReference<Throwable> error = new AtomicReference<>();

        SwingUtilities.invokeAndWait(() -> {
            try {
                jiconfont.swing.IconFontSwing.register(
                        jiconfont.icons.font_awesome.FontAwesome.getIconFont());
                NodePanel panel = new NodePanel();
                paneRef.set(panel.getProfileTabbedPane());
            } catch (Throwable t) {
                error.set(t);
            }
        });

        assertNull(error.get(), "NodePanel must construct: "
                + (error.get() != null ? error.get() : ""));
        JTabbedPane pane = paneRef.get();
        assertNotNull(pane, "the profile tabbed pane must exist");

        // The new-profile "+" is the tab row's trailing component (the new-browsertab
        // pattern), not a dedicated "+" tab.
        assertNotNull(pane.getClientProperty("JTabbedPane.trailingComponent"),
                "the new-profile '+' must be the tab row's trailing component");
        // Profile tabs are closable via the native close "X" (routes to the delete flow).
        assertEquals(Boolean.TRUE, pane.getClientProperty("JTabbedPane.tabClosable"),
                "profile tabs must be closable (native 'X')");
        assertNotNull(pane.getClientProperty("JTabbedPane.tabCloseCallback"),
                "a close callback must be wired to the tab close 'X'");
        // No fixed tab width: the profile tabs follow the app-wide preferred-width sizing,
        // like the other JTabbedPanes (logging/console/configuration).
        assertNull(pane.getClientProperty("JTabbedPane.minimumTabWidth"),
                "the profile tabs must not pin a fixed width (app-wide preferred-width sizing)");
        assertNull(pane.getClientProperty("JTabbedPane.maximumTabWidth"),
                "the profile tabs must not pin a fixed width (app-wide preferred-width sizing)");
    }
}