package application.module.browser.gui.toolbar;

import application.module.browser.config.BrowserSettings;
import application.module.browser.engine.WebBrowserRegistry;
import application.module.browser.engine.security.CertificateInspector;
import application.module.browser.gui.dialogs.CertificateDetailsDialog;
import application.module.browser.model.history.HistoryEntry;
import application.module.browser.model.history.HistoryStore;
import application.module.browser.model.tab.BrowserTab;
import application.module.browser.model.tab.TabController;
import application.module.browser.model.tab.TabEvent;
import application.module.browser.util.UrlUtils;
import application.utils.gui.GuiConstants;
import application.utils.i18n.I18n;

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Container;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GradientPaint;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Window;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;

/**
 * The navigation toolbar of the browser main tab (F2): back/forward/reload-
 * stop/home buttons (N3, N5), the {@link Omnibox} (N1/N2), the
 * {@link SecurityIcon} (S1/S2/S4) and the thin indeterminate progress bar
 * (N4/A3) below the row.
 * <p>
 * Pure view: every state comes from the {@link TabController} (tab events,
 * pumped to the EDT) and every intent goes through the controller or the
 * {@link WebBrowserRegistry}. Must be constructed on the EDT.
 */
public final class NavigationToolbar extends JPanel {

    private final TabController controller;
    private final WebBrowserRegistry registry;
    private final Supplier<BrowserSettings> settings;
    private final HistoryStore history;
    private final JButton back;
    private final JButton forward;
    private final JButton reloadStop;
    private final Omnibox omnibox;
    private final SecurityIcon securityIcon;
    private final FadingLabel titleLabel;
    private final ProgressBar progressBar;
    private final CertificateInspector inspector = new CertificateInspector();
    private final ExecutorService certExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "browser-cert-inspector");
        t.setDaemon(true);
        return t;
    });

    public NavigationToolbar(TabController controller, WebBrowserRegistry registry,
                             Supplier<BrowserSettings> settings, HistoryStore history) {
        super(new BorderLayout(0, 2));
        this.controller = controller;
        this.registry = registry;
        this.settings = settings;
        this.history = history;

        this.back = flatNavButton("\u25C0", I18n.get("browser.nav.back.tooltip"));
        this.forward = flatNavButton("\u25B6", I18n.get("browser.nav.forward.tooltip"));
        this.reloadStop = flatNavButton("\u27F3", I18n.get("browser.nav.reload.tooltip"));
        JButton home = flatNavButton("\u2302", I18n.get("browser.nav.home.tooltip"));
        back.addActionListener(e -> registry.back(activeTabId()));
        forward.addActionListener(e -> registry.forward(activeTabId()));
        reloadStop.addActionListener(e -> {
            String tabId = activeTabId();
            controller.getTab(tabId).ifPresent(tab -> {
                if (tab.isLoading()) {
                    registry.stop(tabId);
                } else {
                    registry.reload(tabId);
                }
            });
        });
        home.addActionListener(e -> navigateToHomepage());

        JPanel navButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        navButtons.setOpaque(false);
        navButtons.add(back);
        navButtons.add(forward);
        navButtons.add(reloadStop);
        navButtons.add(home);

        this.omnibox = new Omnibox(this::navigate, this::suggestFor);
        this.securityIcon = new SecurityIcon(this::openCertificateDialog);
        this.titleLabel = new FadingLabel("", 0.8f);
        titleLabel.setToolTipText(I18n.get("browser.nav.title.tooltip"));

        JPanel east = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        east.setOpaque(false);
        east.add(securityIcon);
        east.add(titleLabel);

        JPanel row = new JPanel(new BorderLayout(6, 0));
        row.add(navButtons, BorderLayout.WEST);
        row.add(omnibox, BorderLayout.CENTER);
        row.add(east, BorderLayout.EAST);
        add(row, BorderLayout.CENTER);

        this.progressBar = new ProgressBar();
        add(progressBar, BorderLayout.SOUTH);

        controller.addListener(event ->
                SwingUtilities.invokeLater(() -> onTabEvent(event)));
        back.setEnabled(false);
        forward.setEnabled(false);
    }

    // ------------------------------------------------------------------
    // Intents (omnibox, security icon)
    // ------------------------------------------------------------------

    /** N9: Ctrl+L — focus the omnibox. */
    public void focusOmnibox() {
        omnibox.focusAndSelect();
    }

    /**
     * Esc routing (N9): the omnibox consumes the key when it has focus
     * (closes the popup); otherwise the toolbar stops the active load.
     */
    public void onEscape() {
        if (omnibox.consumeEscape()) {
            return;
        }
        registry.stop(activeTabId());
    }

    private void navigate(String input) {
        String target = UrlUtils.normalize(input, settings.get().getSearchEngineTemplate());
        if (target == null) {
            return;
        }
        String tabId = activeTabId();
        if (tabId != null) {
            registry.navigate(tabId, target);
        }
    }

    private void navigateToHomepage() {
        String tabId = activeTabId();
        String homepage = settings.get().getHomepage();
        if (tabId != null && homepage != null && !homepage.isBlank()) {
            registry.navigate(tabId, homepage);
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

    /**
     * N2 suggestion rows for the current input. F3: history matches first
     * (the most recent visits for the input), then the normalized-URL entry
     * (when the input looks navigable) and the search entry; F4 appends
     * bookmarks to this same list.
     */
    private List<OmniboxPopup.Suggestion> suggestFor(Omnibox box, String input) {
        List<OmniboxPopup.Suggestion> items = new ArrayList<>();
        String normalized = UrlUtils.normalize(input, settings.get().getSearchEngineTemplate());
        for (HistoryEntry entry :
                history.search(input, 0L, Long.MAX_VALUE, HISTORY_SUGGESTIONS)) {
            if (normalized != null && normalized.equals(entry.getUrl())) {
                continue; // the normalized-URL row below already covers this one
            }
            String label = (entry.getTitle() == null || entry.getTitle().isBlank())
                    ? entry.getUrl()
                    : entry.getTitle();
            items.add(new OmniboxPopup.Suggestion(OmniboxPopup.Suggestion.Kind.URL,
                    I18n.get("browser.omnibox.suggestion.visit", label), entry.getUrl()));
        }
        if (normalized != null
                && (UrlUtils.isWebUrl(input) || UrlUtils.looksLikeHost(input))) {
            items.add(new OmniboxPopup.Suggestion(OmniboxPopup.Suggestion.Kind.URL,
                    I18n.get("browser.omnibox.suggestion.visit", normalized), normalized));
        }
        items.add(new OmniboxPopup.Suggestion(OmniboxPopup.Suggestion.Kind.SEARCH,
                I18n.get("browser.omnibox.suggestion.search", input), normalized));
        return items;
    }

    // ------------------------------------------------------------------
    // Tab events (controller → toolbar, on the EDT)
    // ------------------------------------------------------------------

    private void onTabEvent(TabEvent event) {
        switch (event.getType()) {
            case ADDED, ACTIVATED -> syncForActive();
            case UPDATED -> {
                BrowserTab active = controller.getActiveTab().orElse(null);
                if (active != null && active.getId().equals(event.getTab().getId())) {
                    syncForActive();
                }
            }
            case REMOVED -> syncForActive();
            case MOVED -> {
                // the toolbar does not render tab order
            }
        }
    }

    /** Refreshes every toolbar widget from the active tab's model state. */
    private void syncForActive() {
        BrowserTab tab = controller.getActiveTab().orElse(null);
        if (tab == null) {
            back.setEnabled(false);
            forward.setEnabled(false);
            progressBar.setActive(false);
            return;
        }
        omnibox.showUrl(tab.getUrl());
        back.setEnabled(tab.canGoBack());
        forward.setEnabled(tab.canGoForward());
        if (tab.isLoading()) {
            reloadStop.setText("\u2715");
            reloadStop.setToolTipText(I18n.get("browser.nav.stop.tooltip"));
        } else {
            reloadStop.setText("\u27F3");
            reloadStop.setToolTipText(I18n.get("browser.nav.reload.tooltip"));
        }
        securityIcon.update(tab.getSslStatus(), tab.getUrl());
        String title = tab.getTitle();
        titleLabel.setTextAnimated(title == null || title.isBlank() ? "" : title);
        progressBar.setActive(tab.isLoading());
    }

    private String activeTabId() {
        return controller.getActiveTab().map(BrowserTab::getId).orElse(null);
    }

    /**
     * A compact toolbar button in the application's native L&F style (the
     * glyphs are font-based, sized by the UI font — no custom painting).
     */
    private static JButton flatNavButton(String text, String tooltip) {
        JButton button = new JButton(text);
        // The glyph follows the app's toolbar icon size (Appearance settings) —
        // the same convention as the other modules' FontAwesome icons — instead
        // of the raw (smaller) button font.
        button.setFont(button.getFont().deriveFont(GuiConstants.getToolBarIconSize()));
        button.setFocusable(false);
        button.setMargin(new java.awt.Insets(0, 6, 0, 6));
        button.setPreferredSize(new Dimension(30, 30));
        button.setToolTipText(tooltip);
        return button;
    }

    /**
     * A6: the active tab's title with a 200 ms fade-in on every change
     * (rendered with an alpha composite while animating).
     */
    private static final class FadingLabel extends JLabel {

        private int alpha = 255;
        private Timer animation;

        FadingLabel(String text, float opacity) {
            super(text);
            setOpaque(false);
            // the UI's default label font (app-consistent)
            setForeground(faint());
            alpha = (int) (opacity * 255);
        }

        void setTextAnimated(String text) {
            if (text.equals(getText())) {
                return;
            }
            setText(text);
            if (text.isEmpty()) {
                return;
            }
            alpha = 0;
            if (animation == null) {
                animation = new Timer(16, e -> {
                    alpha = Math.min(255, alpha + 30);
                    if (alpha >= 255) {
                        animation.stop();
                    }
                    repaint();
                });
            }
            animation.start();
        }

        @Override
        protected void paintComponent(Graphics g) {
            if (alpha >= 255 || getText().isEmpty()) {
                super.paintComponent(g);
                return;
            }
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha / 255f));
            super.paintComponent(g2);
            g2.dispose();
        }

        private static Color faint() {
            Color c = UIManager.getColor("Label.disabledForeground");
            return c != null ? c : Color.GRAY;
        }
    }

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
}