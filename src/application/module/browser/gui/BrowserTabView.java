package application.module.browser.gui;

import application.module.browser.config.BrowserSettings;
import application.module.browser.engine.WebBrowserHost;
import application.module.browser.gui.toolbar.NavigationToolbar;
import application.module.browser.gui.bookmarks.BookmarksBar;
import application.module.browser.model.bookmarks.BookmarkStore;
import application.module.browser.model.history.HistoryStore;
import application.module.browser.model.tab.BrowserTab;
import application.module.browser.model.tab.TabController;
import application.module.browser.model.tab.TabEvent;
import application.module.browser.model.tab.TabEventListener;
import application.utils.i18n.I18n;

import java.awt.BorderLayout;
import java.awt.FlowLayout;
import java.awt.event.HierarchyEvent;
import java.util.function.Supplier;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.Timer;

/**
 * One browser tab as a self-contained component — the tab is an entity
 * (the same concept the application and node tabs follow): its own
 * {@link NavigationToolbar} (with its own {@code Omnibox}) above its own
 * content slot, and its own CEF browser ({@link WebBrowserHost}) that it
 * creates, drives and tears down.
 * <p>
 * Shared objects stay shared on purpose: the engine, the
 * {@link TabController} (the tab list's single source of truth) and the
 * settings/history/bookmark stores are module-level services injected into
 * every tab. Everything that belongs to the page — the URL bar text, the
 * navigation state, the security icon, the progress, the render surface —
 * lives here. Switching tabs is therefore only a matter of which tab view
 * is in front: the content shown and every input always belong to the
 * active tab; there is no shared toolbar that has to mirror anything.
 * <p>
 * T10/D13: a discarded tab keeps this view (with a placeholder in the
 * content slot) until it is activated again, when the browser is recreated
 * transparently from the tab's URL.
 * <p>
 * Must be constructed on the EDT (windowed CEF — see {@code WebBrowser}).
 */
public final class BrowserTabView extends JPanel {

    /**
     * Creates the tab's CEF browser (BrowserPanel wires the engine in).
     * One browser is created per call — the view calls it at construction
     * and again when a discarded tab is restored.
     */
    @FunctionalInterface
    public interface BrowserFactory {
        WebBrowserHost create(BrowserTab tab);
    }

    private final BrowserTab tab;
    private final TabController controller;
    private final BrowserFactory browserFactory;
    private final TabEventListener tabEvents =
            event -> SwingUtilities.invokeLater(() -> onTabEvent(event));
    private final NavigationToolbar toolbar;
    /**
     * F4 (B4): this tab's bookmarks bar — one shared view of the shared
     * bookmark store per tab, sitting directly below this tab's own
     * toolbar (the omnibox row), like Chrome's bar below the URL field.
     * The visibility is the persisted {@code showBookmarksBar} setting.
     */
    private final BookmarksBar bookmarksBar;
    private final JPanel contentHost = new JPanel(new BorderLayout());
    /** The tab's CEF browser; {@code null} while the tab is discarded. */
    private WebBrowserHost browser;
    /** The T10 placeholder (created once, reused across discard/restore cycles). */
    private JComponent discardPlaceholder;
    /** True when the CEF browser was created while the tab pane was not showing
     *  (the startup session restore); it is then transparently rebuilt on the
     *  first show so the windowed canvas gets a valid native-window size. */
    private boolean createdWhileHidden;
    /**
     * The logical visibility of this tab's bookmarks bar (the persisted
     * {@code showBookmarksBar} setting): the bar's PREFERRED HEIGHT — not
     * its {@code visible} flag — is what implements it, so the toggle can
     * animate the height (slide in / slide out).
     */
    private boolean bookmarksBarVisible;
    /** The bookmarks-bar slide in/out animation (see {@link #startBarAnimation}). */
    private Timer barAnimation;

    public BrowserTabView(BrowserTab tab, TabController controller, BrowserFactory browserFactory,
                          Supplier<BrowserSettings> settings, HistoryStore history,
                          BookmarkStore bookmarks) {
        super(new BorderLayout());
        this.tab = tab;
        this.controller = controller;
        this.browserFactory = browserFactory;
        // A neutral focus target: when a demoted tab is switched away from,
        // the content panel moves the keyboard focus here instead of leaving
        // it inside the demoted tab's omnibox/page (input must follow the tab).
        setFocusable(true);
        this.toolbar = new NavigationToolbar(this, tab, settings, history, bookmarks);
        // The bookmarks bar sits BELOW this tab's toolbar row, like Chrome's
        // bar below the URL field; bookmarks open in THIS tab. Both must be
        // nested in ONE container: a BorderLayout keeps a single component
        // per side, so a second NORTH add would silently REPLACE the toolbar
        // in the NORTH slot and the toolbar (omnibox) would never be laid
        // out (it would stay 0×0 and be invisible).
        this.bookmarksBar = new BookmarksBar(bookmarks,
                this::navigate,
                this::navigate);
        this.bookmarksBarVisible = settings.get().isShowBookmarksBar();
        if (!bookmarksBarVisible) {
            // Start collapsed: the bar's preferred height is 0 (the show/hide
            // toggle then animates it, see setBookmarksBarVisible).
            bookmarksBar.setPreferredSize(new java.awt.Dimension(-1, 0));
        }
        // The header area, bottom to top: the toolbar's progress bar (the
        // FULL header width, below EVERYTHING — so it never pushes the bar),
        // the bookmarks bar (whose height is what the show/hide animates),
        // and the toolbar row on top.
        JPanel barArea = new JPanel(new BorderLayout());
        barArea.setOpaque(false);
        barArea.add(bookmarksBar, BorderLayout.NORTH);
        barArea.add(toolbar.progressIndicator(), BorderLayout.SOUTH);
        JPanel topArea = new JPanel(new BorderLayout());
        topArea.add(toolbar, BorderLayout.NORTH);
        topArea.add(barArea, BorderLayout.CENTER);
        add(topArea, BorderLayout.NORTH);
        add(contentHost, BorderLayout.CENTER);
        this.browser = browserFactory.create(tab);
        contentHost.add((JComponent) browser.getUiComponent(), BorderLayout.CENTER);
        // Windowed-JCEF fix: if this browser was created while the tab pane was
        // not showing (the startup session restore runs before the browser tab
        // is visible), its windowed canvas keeps a mis-sized native window that
        // flickers on every repaint and can cover the tab strip. Rebuild it the
        // first time this view actually becomes visible (see
        // markCreatedWhileHidden / recreateBrowserIfHidden).
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) == 0) {
                return;
            }
            if (isShowing()) {
                if (createdWhileHidden) {
                    recreateBrowserIfHidden();
                }
            } else {
                // Switched away: never leave the suggestion popup open over
                // another tab's content (the field's focusLost usually covers
                // this; this is the belt-and-braces case for a programmatic
                // switch where the focus move is not guaranteed).
                toolbar.hideOmniboxPopup();
            }
        });
        controller.addListener(tabEvents);
    }

    /** @return the id of the tab this view belongs to. */
    public String getTabId() {
        return tab.getId();
    }

    /** @return the tab's model (read-only reference; the controller mutates it). */
    public BrowserTab getTab() {
        return tab;
    }

    /** @return the tab's own toolbar (its omnibox, star, security icon...). */
    public NavigationToolbar getToolbar() {
        return toolbar;
    }

    /** @return the tab's bookmarks bar (below its toolbar row). */
    public BookmarksBar getBookmarksBar() {
        return bookmarksBar;
    }

    /**
     * Shows/hides the tab's bookmarks bar (the Ctrl+Shift+B toggle and the
     * settings' "Show bookmarks bar") with a slide animation: the bar's
     * preferred height eases between 0 and its natural height, so the bar
     * visibly rolls down / up between the toolbar row and the header's
     * progress bar instead of popping in and out.
     */
    public void setBookmarksBarVisible(boolean visible) {
        if (visible == bookmarksBarVisible && !isBarAnimating()) {
            return;
        }
        bookmarksBarVisible = visible;
        startBarAnimation(visible);
    }

    /** @return true while the bookmarks-bar slide animation is running. */
    private boolean isBarAnimating() {
        return barAnimation != null && barAnimation.isRunning();
    }

    /**
     * Animates the bookmarks bar's preferred height between 0 (hidden) and
     * its natural FlowLayout height (shown) over ~240 ms with an ease-out
     * curve. A toggle mid-animation continues from the CURRENT height to
     * the new target. When the bar is shown the forced size is dropped at
     * the end (back to the natural size); when hidden it stays pinned at 0.
     */
    private void startBarAnimation(boolean visible) {
        if (barAnimation != null) {
            barAnimation.stop();
        }
        int from = bookmarksBar.getPreferredSize().height;
        int to = visible ? bookmarksBar.getLayout().preferredLayoutSize(bookmarksBar).height : 0;
        final int steps = 15;
        int[] step = {0};
        barAnimation = new Timer(16, e -> {
            step[0]++;
            double t = Math.min(1.0, step[0] / (double) steps);
            double eased = 1.0 - (1.0 - t) * (1.0 - t); // ease-out
            int h = Math.max(0, (int) Math.round(from + (to - from) * eased));
            bookmarksBar.setPreferredSize(new java.awt.Dimension(-1, h));
            revalidate();
            repaint();
            if (step[0] >= steps) {
                barAnimation.stop();
                if (visible) {
                    bookmarksBar.setPreferredSize(null); // the natural height again
                } else {
                    bookmarksBar.setPreferredSize(new java.awt.Dimension(-1, 0));
                }
                revalidate();
                repaint();
            }
        });
        barAnimation.setCoalesce(true);
        barAnimation.start();
    }

    /** Rebuilds the tab's bookmarks bar from the shared store (after any bookmark change). */
    public void refreshBookmarksBar() {
        bookmarksBar.refresh();
    }

    // ------------------------------------------------------------------
    // Navigation intents — always this tab's browser (no-op when discarded)
    // ------------------------------------------------------------------

    public void navigate(String url) {
        if (browser != null) {
            browser.loadUrl(url);
        }
    }

    public void back() {
        if (browser != null) {
            browser.goBack();
        }
    }

    public void forward() {
        if (browser != null) {
            browser.goForward();
        }
    }

    public void reload() {
        if (browser != null) {
            browser.reload();
        }
    }

    public void stop() {
        if (browser != null) {
            browser.stop();
        }
    }

    /** N9: Ctrl+L — focus this tab's omnibox. */
    public void focusOmnibox() {
        toolbar.focusOmnibox();
    }

    /** N9: Esc — the omnibox consumes it while focused, else the load stops. */
    public void onEscape() {
        toolbar.onEscape();
    }

    /** F4 (B1): Ctrl+D — toggles this tab's page bookmark. */
    public void toggleBookmark() {
        toolbar.toggleBookmark();
    }

    // ------------------------------------------------------------------
    // Lifecycle (TabController events → this view)
    // ------------------------------------------------------------------

    /**
     * ACTIVATED: this tab is in front again — recreate its engine
     * transparently when it was discarded (T10) and re-sync the toolbar.
     */
    public void activate() {
        if (tab.isDiscarded()) {
            restore();
        }
        toolbar.sync(tab);
    }

    /**
     * T10/D13: releases the CEF engine; a placeholder takes the content
     * slot until {@link #activate()} restores the tab.
     */
    public void discard() {
        if (browser == null) {
            return; // already discarded
        }
        WebBrowserHost disposed = browser;
        browser = null;
        disposed.dispose(); // removes its canvas from the content host
        JComponent placeholder = discardPlaceholder;
        if (placeholder == null) {
            placeholder = newDiscardPlaceholder();
            discardPlaceholder = placeholder;
        }
        contentHost.add(placeholder, BorderLayout.CENTER);
        contentHost.revalidate();
        contentHost.repaint();
        toolbar.sync(tab);
    }

    /** T10: the transparent restore — a fresh browser from the tab's URL. */
    private void restore() {
        browser = browserFactory.create(tab);
        if (discardPlaceholder != null) {
            contentHost.remove(discardPlaceholder);
        }
        contentHost.add((JComponent) browser.getUiComponent(), BorderLayout.CENTER);
        contentHost.revalidate();
        contentHost.repaint();
        createdWhileHidden = false; // a fresh, (re)created browser — no rebuild
    }

    /**
     * Windowed-JCEF fix: called by the panel right after this view is added to
     * the tab pane, when the pane is <em>not</em> showing (the startup session
     * restore). Flags the browser for a transparent rebuild on first show.
     */
    public void markCreatedWhileHidden() {
        createdWhileHidden = true;
    }

    /**
     * Windowed-JCEF fix: the first time this view becomes visible, rebuild the
     * browser if it was originally created while the tab pane was hidden. The
     * fresh windowed canvas gets a valid native-window size, which is what
     * stops the flicker and the tab-strip covering. Runs at most once.
     */
    private void recreateBrowserIfHidden() {
        if (!createdWhileHidden || browser == null) {
            return;
        }
        createdWhileHidden = false;
        WebBrowserHost old = browser;
        browser = null;
        old.dispose(); // removes the old (mis-sized) canvas from the content host
        browser = browserFactory.create(tab);
        contentHost.add((JComponent) browser.getUiComponent(), BorderLayout.CENTER);
        contentHost.revalidate();
        contentHost.repaint();
    }

    /**
     * REMOVED / engine shutdown: drops the tab-event listener, stops the
     * toolbar's animations, uninstalls the omnibox's global hooks and tears
     * the CEF browser down. Idempotent enough for the close-callback race.
     */
    public void dispose() {
        if (barAnimation != null) {
            barAnimation.stop();
        }
        controller.removeListener(tabEvents);
        toolbar.dispose();
        if (browser != null) {
            WebBrowserHost disposed = browser;
            browser = null;
            disposed.dispose();
        }
        removeAll();
    }

    /** The stand-in shown for a discarded tab's content (T10). */
    private static JComponent newDiscardPlaceholder() {
        JLabel label = new JLabel(I18n.get("browser.tab.discarded"), SwingConstants.CENTER);
        label.setBorder(BorderFactory.createEmptyBorder(40, 20, 40, 20));
        JPanel placeholder = new JPanel(new FlowLayout());
        placeholder.setOpaque(false);
        placeholder.add(label);
        return placeholder;
    }

    private void onTabEvent(TabEvent event) {
        if (!event.getTab().getId().equals(tab.getId())) {
            return; // only this tab's events drive this view
        }
        switch (event.getType()) {
            case UPDATED, ACTIVATED -> toolbar.sync(tab);
            default -> {
                // ADDED: already synced at construction; MOVED/REMOVED are
                // not toolbar state (REMOVED is followed by dispose()).
            }
        }
    }
}