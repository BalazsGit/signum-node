package application.module.browser.gui;

import application.module.browser.model.tab.BrowserTab;
import application.module.browser.model.tab.TabController;
import application.module.browser.model.tab.TabSource;
import application.utils.gui.SpinnerIcon;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import javax.swing.Icon;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Container;
import java.util.concurrent.Callable;
import java.util.function.IntConsumer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BrowserTabPane} — the single tabbed pane that owns
 * the browser's tab row and the page content: the pane reconciles with the
 * {@link TabController} (the tab SSOT), the FlatLaf client properties wire
 * the native row (fixed width, close X, trailing "+"), and tab
 * selection/close route back to the controller.
 */
@DisplayName("BrowserTabPane Tests")
class BrowserTabPaneTest {

    private TabController controller;
    private BrowserTabPane pane;

    @BeforeEach
    void setUp() {
        controller = new TabController();
        pane = new BrowserTabPane(controller);
    }

    @AfterEach
    void tearDown() {
        pane.dispose();
    }

    private static void onEdt(Runnable action) {
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static <T> T onEdt(Callable<T> action) {
        final Object[] out = new Object[1];
        final Throwable[] err = new Throwable[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    out[0] = action.call();
                } catch (Throwable t) {
                    err[0] = t;
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (err[0] != null) {
            throw new RuntimeException(err[0]);
        }
        return (T) out[0];
    }

    /** Opens a tab in the controller and hosts a dummy view for it. */
    private BrowserTab openTab(String url) {
        String id = controller.openTab(url, TabSource.USER);
        BrowserTab tab = controller.getTab(id).orElseThrow();
        pane.addTab(tab, new JPanel());
        return tab;
    }

    @Nested
    @DisplayName("Reconciliation")
    class ReconciliationTests {

        @Test
        @DisplayName("addTab hosts the view as the tab's component with the display title")
        void addTab_hostsViewAsTabComponent() {
            String id = controller.openTab("https://example.com/", TabSource.USER);
            BrowserTab tab = controller.getTab(id).orElseThrow();
            JPanel view = new JPanel();
            onEdt(() -> pane.addTab(tab, view));

            assertEquals(1, pane.tabCount());
            assertEquals("example.com", pane.titleAt(0),
                    "the tab title is the URL's host while the page has no title");
            assertSame(view, pane.getComponentAt(0),
                    "the tab's component IS the tab view (its toolbar + render surface)");
        }

        @Test
        @DisplayName("the new tab page gets the localized New-Tab title")
        void addTab_newTabTitle() {
            onEdt(() -> openTab(TabController.NEW_TAB_URL));
            assertEquals("New Tab", pane.titleAt(0));
        }

        @Test
        @DisplayName("refresh restores the controller's order after a model move")
        void refresh_restoresModelOrder() {
            onEdt(() -> openTab("https://a.example/"));
            onEdt(() -> openTab("https://b.example/"));
            assertTrue(controller.moveTab(1, 0), "the model move must succeed");

            onEdt(pane::refresh);
            assertEquals("b.example", pane.titleAt(0), "the pane must follow the model order");
            assertEquals("a.example", pane.titleAt(1));
        }

        @Test
        @DisplayName("removeTab removes the tab from the pane")
        void removeTab_removesFromPane() {
            BrowserTab a = onEdt(() -> openTab("https://a.example/"));
            BrowserTab b = onEdt(() -> openTab("https://b.example/"));
            assertEquals(2, pane.tabCount());

            onEdt(() -> {
                pane.removeTab(a.getId());
                pane.removeTab(b.getId());
            });
            assertEquals(0, pane.tabCount());
        }

        @Test
        @DisplayName("refresh drops tabs that the controller closed")
        void refresh_dropsClosedTabs() {
            BrowserTab a = onEdt(() -> openTab("https://a.example/"));
            BrowserTab b = onEdt(() -> openTab("https://b.example/"));
            controller.closeTab(a.getId());

            onEdt(pane::refresh);
            assertEquals(1, pane.tabCount());
            assertEquals("b.example", pane.titleAt(0));
        }

        @Test
        @DisplayName("showTab selects the tab's component")
        void showTab_selectsTheTab() {
            onEdt(() -> openTab("https://a.example/"));
            BrowserTab b = onEdt(() -> openTab("https://b.example/"));

            onEdt(() -> pane.showTab(b.getId()));
            assertEquals(1, pane.selectedTabIndex());
        }

        @Test
        @DisplayName("a user tab selection routes to the controller (the SSOT stays the master)")
        void userSelection_activatesInController() {
            onEdt(() -> openTab("https://a.example/"));
            onEdt(() -> openTab("https://b.example/"));
            assertEquals(1, controller.getActiveIndex(), "openTab activates the newest tab");

            onEdt(() -> pane.setSelectedIndex(0));
            assertEquals(0, controller.getActiveIndex(),
                    "selecting a tab must activate it in the controller");
        }
    }

    @Nested
    @DisplayName("Native tab row wiring (FlatLaf client properties)")
    class NativeRowTests {

        @Test
        @DisplayName("tabs are fixed-width (220 px) and closable, with a close icon")
        void fixedWidthAndClosable() {
            assertEquals(BrowserTabPane.TAB_WIDTH, pane.getClientProperty("JTabbedPane.minimumTabWidth"));
            assertEquals(BrowserTabPane.TAB_WIDTH, pane.getClientProperty("JTabbedPane.maximumTabWidth"));
            assertEquals(Boolean.TRUE, pane.getClientProperty("JTabbedPane.tabClosable"));
            assertNotNull(pane.getClientProperty("TabbedPane.closeIcon"),
                    "the close X glyph must be installed for the L&F to paint");
        }

        @Test
        @DisplayName("the trailing new-tab \"+\" lives in the row via trailingComponent")
        void trailingNewTabButton() {
            Container trailing = (Container) pane.getClientProperty("JTabbedPane.trailingComponent");
            assertNotNull(trailing,
                    "the \"+\" must be a trailing component inside the tab row (no dead band)");
            assertTrue(trailing.getComponent(0) instanceof javax.swing.JButton button
                            && button == pane.newTabButton(),
                    "the trailing container must hold the new-tab button");
        }

        @Test
        @DisplayName("clicking the \"+\" opens a new tab through the controller")
        void plusClick_opensNewTab() {
            assertEquals(0, controller.size());
            onEdt(() -> {
                pane.newTabButton().doClick();
            });
            assertEquals(1, controller.size());
        }

        @Test
        @DisplayName("the close callback closes the tab at its index through the controller")
        void closeCallback_closesTabAtIndex() {
            onEdt(() -> openTab("https://a.example/"));
            onEdt(() -> openTab("https://b.example/"));
            assertEquals(2, controller.size());

            IntConsumer callback = (IntConsumer) pane.getClientProperty("JTabbedPane.tabCloseCallback");
            assertNotNull(callback, "the L&F close X must be wired to a callback");
            onEdt(() -> callback.accept(0));
            assertEquals(1, controller.size(), "the callback must close the tab at its index");
            onEdt(pane::refresh); // the panel's REMOVED handling syncs the pane
            assertEquals("b.example", pane.titleAt(0));
        }
    }

    @Nested
    @DisplayName("Per-tab state (title / icon / tooltip)")
    class TabStateTests {

        @Test
        @DisplayName("a loading tab shows the spinner icon, an idle tab the placeholder tile")
        void loadingSpinnerIcon() {
            onEdt(() -> openTab("https://a.example/"));
            controller.setLoading(controller.getTabs().get(0).getId(), true);
            onEdt(pane::refresh);

            Icon icon = onEdt(() -> pane.getIconAt(0));
            assertInstanceOf(SpinnerIcon.class, icon,
                    "a loading tab must show the spinner instead of the placeholder");

            controller.setLoading(controller.getTabs().get(0).getId(), false);
            onEdt(pane::refresh);
            Icon idle = onEdt(() -> pane.getIconAt(0));
            assertNotSame(icon, idle, "when the load ends the placeholder comes back");
        }

        @Test
        @DisplayName("a discarded tab's tooltip carries the restore note")
        void discardedTooltip() {
            onEdt(() -> openTab("https://a.example/"));
            controller.setDiscarded(controller.getTabs().get(0).getId(), true);
            onEdt(pane::refresh);

            String tooltip = onEdt(() -> pane.getToolTipTextAt(0));
            assertNotNull(tooltip);
            assertTrue(tooltip.contains("unloaded"),
                    "the tooltip must hint that the tab was discarded (actual: " + tooltip + ")");
        }
    }
}