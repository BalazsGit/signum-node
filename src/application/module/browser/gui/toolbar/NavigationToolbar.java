package application.module.browser.gui.toolbar;

import application.module.appearance.AppearanceModule;
import application.module.browser.config.BrowserSettings;
import application.module.browser.engine.scheme.SettingsPageRenderer;
import application.module.browser.engine.security.CertificateInspector;
import application.module.browser.gui.BrowserTabView;
import application.module.browser.gui.bookmarks.BookmarkDialog;
import application.module.browser.gui.dialogs.CertificateDetailsDialog;
import application.module.browser.model.bookmarks.Bookmark;
import application.module.browser.model.bookmarks.BookmarkStore;
import application.module.browser.model.history.HistoryEntry;
import application.module.browser.model.history.HistoryStore;
import application.module.browser.model.tab.BrowserTab;
import application.module.browser.util.UrlUtils;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.HoverScaleIcon;
import application.utils.i18n.I18n;

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import net.miginfocom.swing.MigLayout;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

/**
 * The navigation toolbar of <em>one</em> browser tab (F2): back/forward/
 * reload-stop/home buttons (N3, N5), the {@link Omnibox} (N1/N2) with the
 * {@link SecurityIcon} (S1/S2/S4) embedded in its field's left side and
 * the bookmark star embedded in its field's right side (B1, always
 * available), the settings gear and the thin
 * indeterminate progress bar (N4/A3) below the row.
 * <p>
 * One toolbar exists per tab (owned by the tab's {@code BrowserTabView}): it
 * renders <em>its</em> tab's state and routes every intent to <em>its</em>
 * tab's browser — there is no shared toolbar that mirrors the active tab.
 * Pure view: its state is refreshed by {@link #sync(BrowserTab)} (driven by
 * the owning view on the tab's events, on the EDT). Must be constructed on
 * the EDT.
 */
public final class NavigationToolbar extends JPanel {

    /** The owning tab view — the navigation intents (its own tab's browser). */
    private final BrowserTabView view;
    /** The model of the tab this toolbar belongs to (mutated in place). */
    private final BrowserTab tab;
    private final Supplier<BrowserSettings> settings;
    private final HistoryStore history;
    private final BookmarkStore bookmarks;
    private final JButton back;
    private final JButton forward;
    private final JButton reloadStop;
    private final JButton home;
    private final JButton settingsButton;
    private final Omnibox omnibox;
    /**
     * Appearance change hook (uninstalled in {@link #dispose()}): rebuilds
     * every toolbar glyph at the new app icon size — the same pattern the
     * node profile toolbar uses, so the browser icons never drift away from
     * the node/profile icon size after an appearance change.
     */
    private final Runnable appearanceListener =
            () -> SwingUtilities.invokeLater(this::refreshIconSizes);
    private final SecurityIcon securityIcon;
    private final ProgressBar progressBar;
    /** F4 (B1): the star button of the omnibox row (fade-in on toggle). */
    private final StarButton star;
    private final CertificateInspector inspector = new CertificateInspector();
    /**
     * The toolbar renders its own FontAwesome glyphs: the font must be
     * registered with IconFontSwing before the first icon build (in the app
     * some other icon does it first; a standalone toolbar — unit tests —
     * must not depend on that side effect). The same once-only guard
     * GuiIcons uses (double registration corrupts the glyph metrics).
     */
    private static final java.util.concurrent.atomic.AtomicBoolean FONT_REGISTERED =
            new java.util.concurrent.atomic.AtomicBoolean(false);
    private final ExecutorService certExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "browser-cert-inspector");
        t.setDaemon(true);
        return t;
    });

    public NavigationToolbar(BrowserTabView view, BrowserTab tab,
                             Supplier<BrowserSettings> settings, HistoryStore history,
                             BookmarkStore bookmarks) {
        super(new BorderLayout(0, 2));
        this.view = view;
        this.tab = tab;
        this.settings = settings;
        this.history = history;
        this.bookmarks = bookmarks;

        this.back = flatNavButton(FontAwesome.ANGLE_LEFT, I18n.get("browser.nav.back.tooltip"), CHEVRON_SCALE);
        this.forward = flatNavButton(FontAwesome.ANGLE_RIGHT, I18n.get("browser.nav.forward.tooltip"), CHEVRON_SCALE);
        this.reloadStop = flatNavButton(FontAwesome.REFRESH, I18n.get("browser.nav.reload.tooltip"));
        this.home = flatNavButton(FontAwesome.HOME, I18n.get("browser.nav.home.tooltip"), HOME_SCALE);
        back.addActionListener(e -> view.back());
        forward.addActionListener(e -> view.forward());
        reloadStop.addActionListener(e -> {
            if (tab.isLoading()) {
                view.stop();
            } else {
                view.reload();
            }
        });
        home.addActionListener(e -> navigateToHomepage());

        // aligny center: the icons sit vertically centered against the
        // omnibox field (FlowLayout would top-align them and look off-center)
        JPanel navButtons = new JPanel(new MigLayout("insets 0, gap 2, aligny center"));
        navButtons.setOpaque(false);
        navButtons.add(back);
        navButtons.add(forward);
        navButtons.add(reloadStop);
        navButtons.add(home);

        // S1/S4: the lock is embedded inside the omnibox's field (Chrome-style)
        this.securityIcon = new SecurityIcon(this::openCertificateDialog);
        // F4 (B1): the star toggles the current page's bookmark — embedded
        // in the omnibox's field's RIGHT side (Chrome-style), so it is
        // available on every page type: web pages (http/https) AND the
        // built-in internal pages (signum://) are bookmarkable; the toolbar
        // sync keeps the star visible but inert on other (non-bookmarkable)
        // schemes.
        this.star = new StarButton();
        star.addActionListener(e -> toggleBookmark());
        this.omnibox = new Omnibox(this::navigate, this::suggestFor, securityIcon, star);

        // F6: the settings gear — opens signum://settings in this tab.
        this.settingsButton = flatNavButton(FontAwesome.COG, I18n.get("browser.nav.settings.tooltip"));
        settingsButton.addActionListener(e -> view.navigate(SettingsPageRenderer.PAGE_URL));

        JPanel east = new JPanel(new MigLayout("insets 0, gap 0, aligny center"));
        east.setOpaque(false);
        east.add(settingsButton);

        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.add(navButtons, BorderLayout.WEST);
        row.add(omnibox, BorderLayout.CENTER);
        row.add(east, BorderLayout.EAST);
        add(row, BorderLayout.CENTER);

        this.progressBar = new ProgressBar();
        add(progressBar, BorderLayout.SOUTH);

        // No controller listener of its own — the owning view drives
        // sync(tab) on this tab's events (the toolbar is per-tab, not
        // active-tab-driven).
        back.setEnabled(false);
        forward.setEnabled(false);
        sync(tab);

        AppearanceModule.registerAppearanceListener(appearanceListener);
    }

    /**
     * Appearance change hook (the NodeToolbar#updateStyles pattern): the
     * glyphs were built at the old toolbar icon size — rebuild them at the
     * current app size so the browser toolbar icons stay exactly the same
     * size as the node/profile toolbar icons (start, pause, ...) after any
     * appearance change.
     */
    private void refreshIconSizes() {
        installIcon(back, FontAwesome.ANGLE_LEFT, CHEVRON_SCALE);
        installIcon(forward, FontAwesome.ANGLE_RIGHT, CHEVRON_SCALE);
        installIcon(reloadStop, tab.isLoading() ? FontAwesome.STOP_CIRCLE_O : FontAwesome.REFRESH);
        installIcon(home, FontAwesome.HOME, HOME_SCALE);
        installIcon(settingsButton, FontAwesome.COG);
        star.rebuildIcon();
        securityIcon.refreshSize();
        omnibox.applySecurityIconInsets();
        revalidate();
        repaint();
    }

    // ------------------------------------------------------------------
    // Intents (omnibox, security icon)
    // ------------------------------------------------------------------

    /** N9: Ctrl+L — focus the omnibox. */
    public void focusOmnibox() {
        omnibox.focusAndSelect();
    }

    /**
     * Tab-switch hook: dismisses the omnibox's suggestion popup if it is open,
     * so it never lingers over another tab's content when this tab is switched
     * away from. Idempotent.
     */
    public void hideOmniboxPopup() {
        omnibox.dismissPopup();
    }

    /**
     * F4 (B1): the star button / Ctrl+D — toggles this tab's page bookmark:
     * unbookmarked pages are saved immediately (name = the page title) with
     * a fade-in animation and land on the bookmarks bar (Chrome-style: a
     * starred favorite is a bar item); an already-bookmarked page opens the
     * edit dialog (rename/move/remove). Web pages (http/https) and the
     * built-in internal pages (signum://) are bookmarkable; other non-web
     * schemes are not.
     */
    public void toggleBookmark() {
        String url = tab.getUrl();
        if (!isBookmarkableUrl(url)) {
            return; // the star only bookmarks web and internal pages
        }
        List<Bookmark> existing = bookmarks.findByUrl(url);
        if (existing.isEmpty()) {
            String name = (tab.getTitle() == null || tab.getTitle().isBlank()) ? url : tab.getTitle();
            String id = bookmarks.newBookmark(BookmarkStore.ROOT_ID, name, url);
            if (id != null) {
                bookmarks.setInBar(id, true); // the star puts the favorite on the bar
                bookmarks.save();
                star.pulse(); // B1: the star's fade-in animation
                if (bookmarksChanged != null) {
                    bookmarksChanged.run();
                }
            }
        } else {
            Window owner = getTopLevelAncestor() instanceof Window w ? w : null;
            BookmarkDialog.show(owner, bookmarks, url, tab.getTitle(), existing.get(0));
            if (bookmarksChanged != null) {
                bookmarksChanged.run();
            }
        }
        sync(tab);
    }

    /**
     * @param url any URL (may be null)
     * @return {@code true} when the star may bookmark it: web pages
     *         (http/https) and the built-in internal pages (signum:// —
     *         settings, history, bookmarks, downloads, about)
     */
    private static boolean isBookmarkableUrl(String url) {
        String scheme = UrlUtils.scheme(url);
        return "http".equals(scheme) || "https".equals(scheme) || "signum".equals(scheme);
    }

    /**
     * Registers the callback fired after any bookmark change here (the
     * bookmarks bar listens to refresh itself).
     */
    private Runnable bookmarksChanged;

    public void onBookmarksChanged(Runnable changed) {
        this.bookmarksChanged = changed;
    }

    /**
     * Esc routing (N9): the omnibox consumes the key when it has focus
     * (closes the popup); otherwise the toolbar stops this tab's load.
     */
    public void onEscape() {
        if (omnibox.consumeEscape()) {
            return;
        }
        view.stop();
    }

    private void navigate(String input) {
        String target = UrlUtils.normalize(input, settings.get().getSearchEngineTemplate());
        if (target != null) {
            view.navigate(target);
        }
    }

    private void navigateToHomepage() {
        String homepage = settings.get().getHomepage();
        if (homepage != null && !homepage.isBlank()) {
            view.navigate(homepage);
        }
    }

    private void openCertificateDialog(String url) {
        if (url == null || !"https".equals(UrlUtils.scheme(url))) {
            return;
        }
        Container owner = getTopLevelAncestor();
        CertificateDetailsDialog.show(
                owner instanceof Window w ? w : null,
                url, inspector, certExecutor);
    }

    /** N2: how many history rows may fill the omnibox popup. */
    private static final int HISTORY_SUGGESTIONS = 6;
    /** N2 (F4): how many bookmark rows may fill the omnibox popup. */
    private static final int BOOKMARK_SUGGESTIONS = 4;

    /**
     * N2 suggestion rows for the current input. F3: history matches first
     * (the most recent visits for the input), then the normalized-URL entry
     * (when the input looks navigable) and the search entry; F4 appends
     * bookmarks to this same list.
     */
    private List<OmniboxPopup.Suggestion> suggestFor(Omnibox box, String input) {
        List<OmniboxPopup.Suggestion> items = new ArrayList<>();
        // One row per URL: the same page must never appear twice (history
        // keeps title variants of one URL, and the same page can also be a
        // bookmark and the normalized-URL row) — the first source wins.
        Set<String> seen = new HashSet<>();
        String normalized = UrlUtils.normalize(input, settings.get().getSearchEngineTemplate());
        // F9 (H6): the history rows are ranked by visits x freshness instead
        // of plain recency
        for (HistoryEntry entry :
                history.searchRanked(input, System.currentTimeMillis(), HISTORY_SUGGESTIONS)) {
            if (!seen.add(dedupeKey(entry.getUrl()))) {
                continue; // the same URL already has a row above
            }
            String label = (entry.getTitle() == null || entry.getTitle().isBlank())
                    ? entry.getUrl()
                    : entry.getTitle();
            items.add(new OmniboxPopup.Suggestion(OmniboxPopup.Suggestion.Kind.URL,
                    I18n.get("browser.omnibox.suggestion.visit", label), entry.getUrl()));
        }
        // F4 (N2): bookmark matches (the star glyph marks the kind in the popup)
        int bookmarksAdded = 0;
        for (Bookmark bookmark : bookmarks.search(input)) {
            if (bookmarksAdded >= BOOKMARK_SUGGESTIONS) {
                break;
            }
            if (!seen.add(dedupeKey(bookmark.getUrl()))) {
                continue; // the same page already has a row above
            }
            String label = (bookmark.getName() == null || bookmark.getName().isBlank())
                    ? bookmark.getUrl() : bookmark.getName();
            items.add(new OmniboxPopup.Suggestion(OmniboxPopup.Suggestion.Kind.BOOKMARK,
                    I18n.get("browser.omnibox.suggestion.visit", label), bookmark.getUrl()));
            bookmarksAdded++;
        }
        if (normalized != null
                && (UrlUtils.isWebUrl(input) || UrlUtils.looksLikeHost(input))
                && seen.add(dedupeKey(normalized))) {
            items.add(new OmniboxPopup.Suggestion(OmniboxPopup.Suggestion.Kind.URL,
                    I18n.get("browser.omnibox.suggestion.visit", normalized), normalized));
        }
        // The search row only when it would not duplicate an existing target
        // (a URL-like input normalizes to the same URL the URL rows cover).
        if (normalized == null || seen.add(dedupeKey(normalized))) {
            items.add(new OmniboxPopup.Suggestion(OmniboxPopup.Suggestion.Kind.SEARCH,
                    I18n.get("browser.omnibox.suggestion.search", input), normalized));
        }
        return items;
    }

    /**
     * Case-insensitive, trailing-slash-insensitive URL key for deduplicating
     * suggestion rows (the same page saved with/without a trailing slash, or
     * in mixed case, is one row).
     */
    private static String dedupeKey(String url) {
        if (url == null) {
            return "";
        }
        String key = url.trim().toLowerCase(Locale.ROOT);
        while (key.endsWith("/")) {
            key = key.substring(0, key.length() - 1);
        }
        return key;
    }

    // ------------------------------------------------------------------
    // State sync (owning view → toolbar, on the EDT)
    // ------------------------------------------------------------------

    /**
     * Refreshes every toolbar widget from the tab's model state. Driven by
     * the owning {@code BrowserTabView} on this tab's ADDED/UPDATED/ACTIVATED
     * events — the toolbar only ever renders its own tab.
     */
    public void sync(BrowserTab tab) {
        omnibox.showUrl(tab.getUrl());
        back.setEnabled(tab.canGoBack());
        forward.setEnabled(tab.canGoForward());
        // F4 (B1): the star mirrors this page's bookmark state. It is
        // embedded in the omnibox's right side, so it stays VISIBLE on
        // every page type — on bookmarkable pages (web and the built-in
        // signum:// internal pages) it reflects the store; on other
        // (non-bookmarkable) schemes it shows the outline star and its
        // click is a no-op (toggleBookmark guards the scheme). Previously
        // the star disappeared on such pages, leaving a gap and hiding
        // the affordance.
        boolean bookmarkable = isBookmarkableUrl(tab.getUrl());
        star.setVisible(true);
        if (bookmarkable) {
            star.setState(!bookmarks.findByUrl(tab.getUrl()).isEmpty());
        }
        if (tab.isLoading()) {
            installIcon(reloadStop, FontAwesome.STOP_CIRCLE_O);
            reloadStop.setToolTipText(I18n.get("browser.nav.stop.tooltip"));
        } else {
            installIcon(reloadStop, FontAwesome.REFRESH);
            reloadStop.setToolTipText(I18n.get("browser.nav.reload.tooltip"));
        }
        securityIcon.update(tab.getSslStatus(), tab.getUrl(), tab.isMixedContent());
        progressBar.setActive(tab.isLoading());
    }

    /**
     * Tab close: stops the widget animations and uninstalls the omnibox's
     * global dismissal hooks (one set exists per tab — a closing tab must
     * not leak the toolkit listener).
     */
    public void dispose() {
        AppearanceModule.removeAppearanceListener(appearanceListener);
        star.stopAnimation();
        omnibox.dispose();
    }

    /**
     * A compact icon-only navigation button styled exactly like the node
     * profile toolbar (NodeToolbar#createIconButton): a FontAwesome glyph at
     * the app's toolbar icon size (Appearance settings) that grows on hover
     * via {@link HoverScaleIcon} — the same "icon grows on rollover, no
     * layout shift" behaviour the node start/pause buttons use.
     */
    private static JButton flatNavButton(FontAwesome iconCode, String tooltip) {
        return flatNavButton(iconCode, tooltip, 1f);
    }

    /**
     * Same, but the glyph is rendered at {@code scale} × the toolbar icon
     * size (the chevron optical correction — see {@link #CHEVRON_SCALE}).
     */
    private static JButton flatNavButton(FontAwesome iconCode, String tooltip, float scale) {
        JButton button = new JButton();
        installIcon(button, iconCode, scale);
        button.setFocusable(false);
        button.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
        button.setOpaque(false);
        button.setContentAreaFilled(false);
        button.setToolTipText(tooltip);
        return button;
    }

    /** Sets the button glyph, keeping the normal/rollover (hover-grow) pair. */
    private static void installIcon(JButton button, FontAwesome iconCode) {
        installIcon(button, iconCode, 1f);
    }

    /**
     * Same, but the glyph is rendered at {@code scale} × the toolbar icon
     * size: the ANGLE_LEFT / ANGLE_RIGHT chevrons only occupy a small part
     * of the em box, so at the plain size they look clearly smaller than
     * the HOME / REFRESH glyphs. The scale makes them read the same size.
     */
    private static void installIcon(JButton button, FontAwesome iconCode, float scale) {
        if (FONT_REGISTERED.compareAndSet(false, true)) {
            IconFontSwing.register(FontAwesome.getIconFont());
        }
        float iconSize = GuiConstants.getToolBarIconSize() * scale;
        HoverScaleIcon.install(button,
                IconFontSwing.buildIcon(iconCode, iconSize, GuiColors.getButtonIcon()),
                IconFontSwing.buildIcon(iconCode, iconSize * HoverScaleIcon.DEFAULT_SCALE,
                        GuiColors.getButtonIcon()));
    }

    /** The optical size correction of the back/forward chevron glyphs. */
    private static final float CHEVRON_SCALE = 1.3f;

    /**
     * The optical size correction of the home glyph: the HOME glyph occupies
     * only a small part of its em box (the REFRESH glyph fills it), so at
     * the plain size the house reads clearly smaller than the reload arrow.
     */
    private static final float HOME_SCALE = 1.18f;

    /**
     * N4/A3: the thin (3 px) indeterminate progress bar under the row — the
     * pinned JCEF fork has no progress callback, so a moving gradient segment
     * signals the load state.
     */
    private final class ProgressBar extends JComponent {

        private int phase;
        private final Timer timer = new Timer(30, e -> {
            phase = (phase + 3) % 100;
            repaint();
        });

        ProgressBar() {
            setPreferredSize(new Dimension(100, 3));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, 3));
            setOpaque(false);
            setVisible(false);
            timer.setCoalesce(true);
        }

        void setActive(boolean active) {
            setVisible(active);
            if (active) {
                phase = 0;
                if (!timer.isRunning()) {
                    timer.start();
                }
            } else {
                timer.stop();
            }
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            int w = getWidth();
            if (w <= 0) {
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,
                    RenderingHints.VALUE_ANTIALIAS_ON);
            int segment = Math.max(30, w / 3);
            int x = -segment + (w + segment) * phase / 100;
            Color color = accent();
            g2.setPaint(new GradientPaint(x, 0, color, x + segment, 0,
                    new Color(color.getRed(), color.getGreen(), color.getBlue(), 0)));
            g2.fillRoundRect(x, 0, segment, 3, 3, 3);
            g2.dispose();
        }

        private static Color accent() {
            Color c = UIManager.getColor("Component.focusColor");
            return c != null ? c : new Color(0x4F, 0x8C, 0xFF);
        }
    }

    /**
     * F4 (B1) / F9 (A7): the omnibox star — a flat icon button in exactly
     * the same style as the other nav buttons (a FontAwesome glyph at the
     * app's toolbar icon size, hover-grow, no fill): the outline star
     * (☆, not bookmarked) becomes the filled star (★, bookmarked) with a
     * "puff" when a bookmark is added: the filled star fades in while
     * settling from a 140% scale (alpha + transform).
     */
    private static final class StarButton extends JButton {

        /** Outline star (☆) — the page is not bookmarked yet. */
        private static final FontAwesome OUTLINE = FontAwesome.STAR_O;
        /** Filled star (★) — the page is bookmarked. */
        private static final FontAwesome FILLED = FontAwesome.STAR;

        private boolean bookmarked;
        private int alpha = 255;
        /** A7: the puff scale factor (1.4 → 1.0 while fading in). */
        private float scale = 1f;
        private Timer animation;

        StarButton() {
            setFocusable(false);
            // The star is a flat inline glyph, not a "button" affordance: keep
            // the plain arrow cursor on hover (the JButton default hand cursor
            // read as inconsistent with the rest of the omnibox row).
            setCursor(java.awt.Cursor.getDefaultCursor());
            setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
            setOpaque(false);
            setContentAreaFilled(false);
            setState(false);
        }

        /** Tab close: the timer must not outlive the toolbar. */
        void stopAnimation() {
            if (animation != null) {
                animation.stop();
            }
        }

        /** Fills/empties the star without the animation (tab switching). */
        void setState(boolean bookmarked) {
            this.bookmarked = bookmarked;
            setToolTipText(I18n.get(bookmarked
                    ? "browser.nav.star.remove" : "browser.nav.star.add"));
            alpha = 255;
            scale = 1f;
            if (animation != null) {
                animation.stop();
            }
            rebuildIcon();
        }

        /** Appearance change: re-renders the glyph at the current app icon size. */
        void rebuildIcon() {
            installIcon(this, bookmarked ? FILLED : OUTLINE);
        }

        /** The A7 puff: the (now filled) star fades in while settling down. */
        void pulse() {
            setState(true);
            alpha = 0;
            scale = 1.4f;
            if (animation == null) {
                animation = new Timer(16, e -> {
                    alpha = Math.min(255, alpha + 30);
                    scale = Math.max(1f, scale - 0.08f);
                    if (alpha >= 255) {
                        animation.stop();
                        scale = 1f;
                    }
                    repaint();
                });
            }
            animation.start();
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (alpha >= 255 && scale <= 1f) {
                super.paintComponent(g);
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha / 255f));
            // A7: scale around the button center
            g2.translate(getWidth() / 2f, getHeight() / 2f);
            g2.scale(scale, scale);
            g2.translate(-getWidth() / 2f, -getHeight() / 2f);
            super.paintComponent(g2);
            g2.dispose();
        }
    }
}