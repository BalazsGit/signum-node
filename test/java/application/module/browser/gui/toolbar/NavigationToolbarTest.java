package application.module.browser.gui.toolbar;

import application.module.browser.config.BrowserSettings;
import application.module.browser.engine.WebBrowserHost;
import application.module.browser.gui.BrowserTabView;
import application.module.browser.model.bookmarks.Bookmark;
import application.module.browser.model.bookmarks.BookmarkStore;
import application.module.browser.model.tab.TabController;
import application.module.browser.model.tab.TabSource;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;
import java.awt.Container;
import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the navigation toolbar's star (bookmark) behavior: web
 * pages (http/https) AND the built-in internal pages (signum:// — settings,
 * history, bookmarks, downloads, about) are bookmarkable; other non-web
 * schemes (e.g. file:) are not.
 */
@DisplayName("NavigationToolbar bookmark Tests")
class NavigationToolbarTest {

    /** A JCEF stand-in: a plain canvas (no navigation is under test here). */
    private static final class FakeBrowser implements WebBrowserHost {

        final JPanel canvas = new JPanel();

        @Override
        public Component getUiComponent() {
            return canvas;
        }

        @Override
        public void loadUrl(String url) {
            // no-op
        }

        @Override
        public void goBack() {
            // no-op
        }

        @Override
        public void goForward() {
            // no-op
        }

        @Override
        public void reload() {
            // no-op
        }

        @Override
        public void stop() {
            // no-op
        }

        @Override
        public void dispose() {
            Container parent = (Container) canvas.getParent();
            if (parent != null) {
                parent.remove(canvas);
            }
        }
    }

    private record Harness(BrowserTabView view, BookmarkStore bookmarks) {

        static Harness newHarness(String url) {
            try {
                BookmarkStore bookmarks = newBookmarkStore();
                final BrowserTabView[] holder = new BrowserTabView[1];
                SwingUtilities.invokeAndWait(() -> {
                    TabController controller = new TabController();
                    String id = controller.openTab(url, TabSource.USER);
                    var tab = controller.getTab(id).orElseThrow();
                    holder[0] = new BrowserTabView(tab, controller, t -> new FakeBrowser(),
                            BrowserSettings::new, null, bookmarks);
                });
                return new Harness(holder[0], bookmarks);
            } catch (Exception e) {
                throw new AssertionError("harness construction failed for " + url, e);
            }
        }
    }

    private static BookmarkStore newBookmarkStore() {
        try {
            return new BookmarkStore(Files.createTempDirectory("navigation-toolbar-test")
                    .resolve("bookmarks.json"));
        } catch (IOException e) {
            throw new java.io.UncheckedIOException(e);
        }
    }

    @Test
    @DisplayName("an internal (signum://) page can be bookmarked with the star")
    void internalPageIsBookmarkable() {
        Harness h = Harness.newHarness("signum://settings");

        h.view().getToolbar().toggleBookmark();

        List<?> saved = h.bookmarks().findByUrl("signum://settings");
        assertEquals(1, saved.size(), "the star must save the internal page");
    }

    @Test
    @DisplayName("every built-in internal page is bookmarkable")
    void allInternalPagesAreBookmarkable() {
        for (String url : new String[]{"signum://settings", "signum://history",
                "signum://bookmarks", "signum://downloads", "signum://about"}) {
            Harness h = Harness.newHarness(url);
            h.view().getToolbar().toggleBookmark();
            assertEquals(1, h.bookmarks().findByUrl(url).size(),
                    url + " must be bookmarkable");
        }
    }

    @Test
    @DisplayName("a web page can be bookmarked with the star")
    void webPageIsBookmarkable() {
        Harness h = Harness.newHarness("https://example.com/");

        h.view().getToolbar().toggleBookmark();

        assertEquals(1, h.bookmarks().findByUrl("https://example.com/").size(),
                "the star must save the web page");
    }

    @Test
    @DisplayName("a starred page lands on the bookmarks bar (Chrome-style favorite)")
    void starredPageIsPlacedOnTheBar() {
        Harness h = Harness.newHarness("https://example.com/");

        h.view().getToolbar().toggleBookmark();

        List<Bookmark> saved = h.bookmarks().findByUrl("https://example.com/");
        assertEquals(1, saved.size(), "the star must save the web page");
        assertTrue(h.bookmarks().isInBar(saved.get(0).getId()),
                "the star must put the favorite on the bookmarks bar");
    }

    @Test
    @DisplayName("other non-web schemes stay non-bookmarkable")
    void otherSchemesAreNotBookmarkable() {
        Harness h = Harness.newHarness("file:///C:/temp/page.html");

        h.view().getToolbar().toggleBookmark();

        assertTrue(h.bookmarks().findByUrl("file:///C:/temp/page.html").isEmpty(),
                "file: pages must not be bookmarkable");
    }
}