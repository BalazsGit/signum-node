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
 * Verifies that the Node panel's profile tab strip follows the new-browsertab pattern
 * (the same native FlatLaf client-property setup the browser's {@code BrowserTabPane}
 * uses): the new-profile "+" is the tab row's trailing component (not a dedicated "+"
 * tab), profile tabs are closable via the native "X", and the tabs are fixed-width.
 * <p>
 * These client properties are set synchronously in the {@link NodePanel} constructor, so the
 * assertions are deterministic regardless of the (async) profile loading.
 */
@DisplayName("NodePanel new-browsertab tab strip")
class NodePanelNewTabPatternTest {

    @Test
    @DisplayName("profile tab strip uses a trailing new-profile button, closable tabs and fixed width")
    void profileTabStrip_usesTrailingPlus_closableTabs_fixedWidth() throws Exception {
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
        // Fixed tab width (the browser strip's policy).
        assertNotNull(pane.getClientProperty("JTabbedPane.minimumTabWidth"),
                "the profile tabs must have a fixed width");
        assertNotNull(pane.getClientProperty("JTabbedPane.maximumTabWidth"),
                "the profile tabs must have a fixed width");
    }
}