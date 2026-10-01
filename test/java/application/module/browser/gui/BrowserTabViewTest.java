package application.module.browser.gui;

import application.module.browser.config.BrowserSettings;
import application.module.browser.engine.WebBrowserHost;
import application.module.browser.model.tab.BrowserTab;
import application.module.browser.model.tab.TabController;
import application.module.browser.model.tab.TabSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.awt.Component;
import javax.swing.JLabel;
import javax.swing.JPanel;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BrowserTabView} — the self-contained per-tab
 * entity: its own toolbar (omnibox), its own CEF browser (a plain
 * component stands in for the windowed canvas) and its own
 * discard/restore/dispose lifecycle. The contract under test: every
 * intent and every piece of UI state belongs to the tab's own objects —
 * no tab may ever touch another tab's browser.
 */
@DisplayName("BrowserTabView Tests")
class BrowserTabViewTest {

    /** A JCEF stand-in: a plain canvas + the navigation calls recorded. */
    private static final class FakeBrowser implements WebBrowserHost {

        final JPanel canvas = new JPanel();
        String lastUrl;
        int backCalls;
        int forwardCalls;
        int reloadCalls;
        int stopCalls;
        boolean disposed;

        @Override
        public Component getUiComponent() {
            return canvas;
        }

        @Override
        public void loadUrl(String url) {
            lastUrl = url;
        }

        @Override
        public void goBack() {
            backCalls++;
        }

        @Override
        public void goForward() {
            forwardCalls++;
        }

        @Override
        public void reload() {
            reloadCalls++;
        }

        @Override
        public void stop() {
            stopCalls++;
        }

        @Override
        public void dispose() {
            disposed = true;
            java.awt.Container parent = (java.awt.Container) canvas.getParent();
            if (parent != null) {
                parent.remove(canvas);
            }
        }
    }

    private record Harness(TabController controller, BrowserTabView view, BrowserTab tab,
                           FakeBrowser browser) {
        static Harness newHarness(String url) {
            TabController controller = new TabController();
            String id = controller.openTab(url, TabSource.USER);
            BrowserTab tab = controller.getTab(id).orElseThrow();
            FakeBrowser browser = new FakeBrowser();
            BrowserTabView view = new BrowserTabView(tab, controller, t -> browser,
                    BrowserSettings::new, null, null);
            return new Harness(controller, view, tab, browser);
        }
    }

    /** True when {@code target} is hosted somewhere under {@code root}. */
    private static boolean hasComponent(java.awt.Container root, Component target) {
        for (Component c : root.getComponents()) {
            if (c == target) {
                return true;
            }
            if (c instanceof java.awt.Container child && hasComponent(child, target)) {
                return true;
            }
        }
        return false;
    }

    /** The number of JLabels under {@code root} (the toolbar's title label + placeholders). */
    private static int countJLabels(java.awt.Container root) {
        int count = 0;
        for (Component c : root.getComponents()) {
            if (c instanceof JLabel) {
                count++;
            }
            if (c instanceof java.awt.Container child) {
                count += countJLabels(child);
            }
        }
        return count;
    }

    // ====================================================================
    // Construction and ownership
    // ====================================================================

    @Nested
    @DisplayName("Construction and ownership")
    class ConstructionTests {

        @Test
        @DisplayName("the view hosts its own browser canvas and knows its tab")
        void constructor_hostsOwnCanvas() {
            Harness h = Harness.newHarness("signum://newtab");

            assertTrue(hasComponent(h.view(), h.browser().canvas),
                    "the tab's own canvas must be hosted in the view");
            assertSame(h.tab().getId(), h.view().getTabId());
            assertEquals(1, h.controller().getTabs().size());
        }
    }

    // ====================================================================
    // Intent routing (always the tab's own browser)
    // ====================================================================

    @Nested
    @DisplayName("Intent routing")
    class IntentTests {

        @Test
        @DisplayName("every intent hits the tab's own browser")
        void intents_delegateToOwnBrowser() {
            Harness h = Harness.newHarness("signum://newtab");

            h.view().navigate("https://example.com/");
            h.view().back();
            h.view().forward();
            h.view().reload();
            h.view().stop();

            assertEquals("https://example.com/", h.browser().lastUrl);
            assertEquals(1, h.browser().backCalls);
            assertEquals(1, h.browser().forwardCalls);
            assertEquals(1, h.browser().reloadCalls);
            assertEquals(1, h.browser().stopCalls);
        }

        @Test
        @DisplayName("one tab's intent never touches another tab's browser")
        void intents_neverCrossTabs() {
            TabController controller = new TabController();
            String idA = controller.openTab("signum://tabA", TabSource.USER);
            String idB = controller.openTab("signum://tabB", TabSource.USER);
            BrowserTab tabA = controller.getTab(idA).orElseThrow();
            BrowserTab tabB = controller.getTab(idB).orElseThrow();
            FakeBrowser browserA = new FakeBrowser();
            FakeBrowser browserB = new FakeBrowser();
            BrowserTabView viewA = new BrowserTabView(tabA, controller,
                    t -> t.getId().equals(idA) ? browserA : browserB,
                    BrowserSettings::new, null, null);
            BrowserTabView viewB = new BrowserTabView(tabB, controller,
                    t -> t.getId().equals(idA) ? browserA : browserB,
                    BrowserSettings::new, null, null);

            viewA.navigate("signum://changed");

            assertEquals("signum://changed", browserA.lastUrl,
                    "tab A's own browser must navigate");
            assertNull(browserB.lastUrl, "tab B's browser must stay untouched");
            assertNotEquals(browserA, browserB);
        }
    }

    // ====================================================================
    // Discard / restore (T10)
    // ====================================================================

    @Nested
    @DisplayName("Discard and restore")
    class DiscardTests {

        @Test
        @DisplayName("discard releases the browser and shows the placeholder")
        void discard_releasesBrowser_andShowsPlaceholder() {
            Harness h = Harness.newHarness("signum://newtab");
            int labelsBefore = countJLabels(h.view());

            h.view().discard();

            assertTrue(h.browser().disposed, "the CEF browser must be released");
            assertFalse(hasComponent(h.view(), h.browser().canvas),
                    "the canvas must leave the content slot");
            assertEquals(labelsBefore + 1, countJLabels(h.view()),
                    "the discarded placeholder label takes the canvas's place");
        }

        @Test
        @DisplayName("intents on a discarded tab are safe no-ops")
        void intents_onDiscardedTab_noop() {
            Harness h = Harness.newHarness("signum://newtab");
            h.view().discard();

            h.view().navigate("https://late.example/");
            h.view().reload();

            assertTrue(h.browser().disposed);
            assertNull(h.browser().lastUrl, "a released browser must not be navigated");
            assertEquals(0, h.browser().reloadCalls);
        }

        @Test
        @DisplayName("activating a discarded tab recreates its browser transparently")
        void activate_discardedTab_restoresCanvasFromFactory() {
            TabController controller = new TabController();
            String id = controller.openTab("signum://newtab", TabSource.USER);
            BrowserTab tab = controller.getTab(id).orElseThrow();
            FakeBrowser first = new FakeBrowser();
            FakeBrowser second = new FakeBrowser();
            final FakeBrowser[] created = {first, second};
            final int[] calls = {0};
            BrowserTabView view = new BrowserTabView(tab, controller, t -> {
                FakeBrowser next = created[Math.min(calls[0], 1)];
                calls[0]++;
                return next;
            }, BrowserSettings::new, null, null);
            assertTrue(hasComponent(view, first.canvas));

            view.discard();
            controller.setDiscarded(id, true);
            view.activate();

            assertEquals(2, calls[0], "the factory must run again on restore");
            assertTrue(first.disposed && !second.disposed);
            assertTrue(hasComponent(view, second.canvas),
                    "the fresh canvas must replace the placeholder");
            assertFalse(hasComponent(view, first.canvas),
                    "the released canvas must stay out of the content slot");
        }

        @Test
        @DisplayName("activate on a live tab does not recreate the browser")
        void activate_liveTab_keepsBrowser() {
            TabController controller = new TabController();
            String id = controller.openTab("signum://newtab", TabSource.USER);
            BrowserTab tab = controller.getTab(id).orElseThrow();
            FakeBrowser browser = new FakeBrowser();
            final int[] calls = {0};
            BrowserTabView view = new BrowserTabView(tab, controller, t -> {
                calls[0]++;
                return browser;
            }, BrowserSettings::new, null, null);

            view.activate();

            assertEquals(1, calls[0], "a live tab keeps its engine on activation");
        }
    }

    // ====================================================================
    // Dispose
    // ====================================================================

    @Nested
    @DisplayName("Dispose")
    class DisposeTests {

        @Test
        @DisplayName("dispose tears the browser down and empties the view")
        void dispose_tearsEverythingDown() {
            Harness h = Harness.newHarness("signum://newtab");

            h.view().dispose();

            assertTrue(h.browser().disposed);
            assertEquals(0, h.view().getComponentCount(),
                    "the closed tab's view must be empty (toolbar + content gone)");
        }

        @Test
        @DisplayName("intents after dispose are safe no-ops")
        void intents_afterDispose_noop() {
            Harness h = Harness.newHarness("signum://newtab");
            h.view().dispose();

            h.view().navigate("https://after.example/");
            h.view().reload();

            assertFalse(h.browser().lastUrl != null,
                    "a disposed browser must not receive navigation");
            assertEquals(0, h.browser().reloadCalls);
        }
    }
}