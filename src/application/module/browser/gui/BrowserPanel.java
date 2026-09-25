package application.module.browser.gui;

import application.module.browser.config.BrowserSettings;
import application.module.browser.config.BrowserSettingsRepository;
import application.module.browser.core.BrowserEngine;
import application.module.browser.core.BrowserEngineState;
import application.module.browser.core.JcefProcessBootstrap;
import application.module.browser.core.JcefProvisioner;
import application.module.browser.engine.CefDataCleaner;
import application.module.browser.engine.WebBrowserRegistry;
import application.module.browser.engine.handler.ActiveDownloadRegistry;
import application.module.browser.engine.scheme.AboutPageRenderer;
import application.module.browser.engine.scheme.BookmarkPageRenderer;
import application.module.browser.engine.scheme.DownloadsPageRenderer;
import application.module.browser.engine.scheme.HistoryPageRenderer;
import application.module.browser.engine.scheme.InternalPage;
import application.module.browser.engine.scheme.NewTabPageRenderer;
import application.module.browser.engine.scheme.SettingsPageRenderer;
import application.module.browser.gui.bookmarks.BookmarksBar;
import application.module.browser.gui.dialogs.ClearDataDialog;
import application.module.browser.gui.dialogs.JcefSetupDialog;
import application.module.browser.gui.downloads.DownloadShelf;
import application.module.browser.gui.toolbar.NavigationToolbar;
import application.module.browser.model.bookmarks.BookmarkStore;
import application.module.browser.model.download.DownloadManager;
import application.module.browser.model.history.HistoryStore;
import application.module.browser.model.session.SessionSnapshot;
import application.module.browser.model.session.SessionStore;
import application.module.browser.model.tab.BrowserTab;
import application.module.browser.model.tab.TabController;
import application.module.browser.model.tab.TabDiscardPolicy;
import application.module.browser.model.tab.TabEvent;
import application.module.browser.model.tab.TabSource;
import application.module.browser.gui.tabstrip.ChromeTabBar;
import application.module.browser.gui.theme.LafCssTheme;
import application.utils.i18n.I18n;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.List;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.JComponent;
import javax.swing.JFileChooser;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import javax.swing.KeyStroke;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.InputMap;
import javax.swing.ActionMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Root panel of the browser module (plan §4.1) — replaces the F0 smoke panel.
 * <p>
 * Layout: the {@link ChromeTabBar} on top, below it a card host that shows
 * either the {@link EngineReadyScreen} (A8: preparing / error / idle) or the
 * {@link ContentPanel} with the tabs' CEF components.
 * <p>
 * Wiring:
 * <ul>
 *   <li>{@link TabController} events (any thread) → EDT → tab bar + content;</li>
 *   <li>engine state (init thread) → EDT → card switch, session restore (T8),
 *       browser teardown on shutdown;</li>
 *   <li>keyboard (T1, T2, T4, T7, N9): Ctrl+T/W, Ctrl+Shift+T, Ctrl+Tab,
 *       Ctrl+1..9, F5/Ctrl+R, Alt+←/→, Esc.</li>
 * </ul>
 * Must be constructed on the EDT ({@code ApplicationKernel} calls
 * {@code Module.getUI()} there).
 */
public final class BrowserPanel extends JPanel {

    private static final Logger logger = LoggerFactory.getLogger(BrowserPanel.class);
    private static final String CARD_ENGINE = "engine";
    private static final String CARD_CONTENT = "content";
    private static final int SESSION_SAVE_DEBOUNCE_MS = 400;
    /** F9 (T10/D13): the period of the inactive-tab discard check. */
    private static final int DISCARD_CHECK_INTERVAL_MS = 60_000;

    private final BrowserEngine engine;
    private final Path confDir;
    private final TabController controller = new TabController();
    private final WebBrowserRegistry registry;
    private final HistoryStore historyStore;
    private final BookmarkStore bookmarkStore;
    private final SessionStore sessionStore;
    private final BrowserSettingsRepository settingsRepository;
    private final DownloadManager downloadManager;
    private final ActiveDownloadRegistry activeDownloads;
    private final DownloadShelf downloadShelf;
    private final ChromeTabBar tabBar;
    private final NavigationToolbar toolbar;
    private final BookmarksBar bookmarksBar;
    private final EngineReadyScreen readyScreen;
    private final ContentPanel contentPanel;
    /** F6/F9: the settings renderer (the clear-data + saved hooks are wired below). */
    private final SettingsPageRenderer settingsRenderer;
    /** F4 (B6): the bookmarks renderer (the export/import pickers are wired below). */
    private final BookmarkPageRenderer bookmarkRenderer;
    /** F9 (T10/D13): the periodic inactive-tab discard check (EDT timer). */
    private Timer discardTimer;
    private final CardLayout cards = new CardLayout();
    private final JPanel cardHost = new JPanel(cards);
    private volatile boolean initialTabsRestored;
    private Timer sessionSaveTimer;
    /** True once the JCEF setup dialog has been offered in this session. */
    private boolean jcefDialogOffered;

    public BrowserPanel(BrowserEngine engine, Path browserConfDir) {
        super(new BorderLayout());
        this.engine = engine;
        this.confDir = browserConfDir;
        this.settingsRepository = new BrowserSettingsRepository(
                browserConfDir.resolve("settings.json"));
        this.historyStore = new HistoryStore(browserConfDir.resolve("history.db"));
        // F3: the history page is a dynamic internal page (signum://history)
        // whose body is produced from this store at request time.
        InternalPage.registerRenderer(HistoryPageRenderer.PAGE,
                new HistoryPageRenderer(historyStore));
        // F4: the bookmarks store (JSON tree) + the manager page
        // (signum://bookmarks, the same dynamic-page mechanism).
        this.bookmarkStore = new BookmarkStore(browserConfDir.resolve("bookmarks.json"));
        this.bookmarkRenderer = new BookmarkPageRenderer(bookmarkStore);
        InternalPage.registerRenderer(BookmarkPageRenderer.PAGE, bookmarkRenderer);
        // F5: the download manager (D1 target paths, D2 state, downloads.json)
        // + the shared cancel hooks; the shelf at the bottom (D3, A9).
        this.downloadManager = new DownloadManager(
                browserConfDir.resolve("downloads.json"),
                () -> resolveDownloadsDir(settingsRepository.load()));
        this.activeDownloads = new ActiveDownloadRegistry();
        this.registry = new WebBrowserRegistry(engine, controller,
                settingsRepository::load, historyStore, downloadManager, activeDownloads);
        // F6: the settings + internal-page framework — the settings page
        // (C1/C2/C5, chooser pumped to the EDT by this panel), the downloads
        // page (D4, the manager's recent list) and the about page (C9) join
        // the signum:// registry; the LAF palette becomes the pages' CSS
        // variables (D6, A4).
        this.settingsRenderer = new SettingsPageRenderer(settingsRepository,
                defaultDownloadsDir(), this::pickDownloadsDir);
        InternalPage.registerRenderer(SettingsPageRenderer.PAGE, settingsRenderer);
        InternalPage.registerRenderer(DownloadsPageRenderer.PAGE,
                new DownloadsPageRenderer(downloadManager));
        InternalPage.registerRenderer(AboutPageRenderer.PAGE,
                new AboutPageRenderer(engine::cefVersion, JcefProcessBootstrap.mainArgs()));
        InternalPage.setThemeStyleProvider(LafCssTheme::styleBlock);
        this.sessionStore = new SessionStore(browserConfDir.resolve("session.json"));
        this.tabBar = new ChromeTabBar(controller);
        this.toolbar = new NavigationToolbar(controller, registry,
                settingsRepository::load, historyStore, bookmarkStore);
        this.bookmarksBar = new BookmarksBar(bookmarkStore,
                url -> {
                    String tabId = activeTabId();
                    if (tabId != null) {
                        registry.navigate(tabId, url);
                    }
                },
                page -> {
                    String tabId = activeTabId();
                    if (tabId != null) {
                        registry.navigate(tabId, page);
                    }
                });
        toolbar.onBookmarksChanged(bookmarksBar::refresh);
        this.readyScreen = new EngineReadyScreen();
        this.contentPanel = new ContentPanel();
        this.downloadShelf = new DownloadShelf(downloadManager, activeDownloads,
                url -> controller.openTab(url, TabSource.USER));

        cardHost.add(readyScreen, CARD_ENGINE);
        cardHost.add(contentPanel, CARD_CONTENT);
        JPanel north = new JPanel(new BorderLayout());
        north.add(tabBar, BorderLayout.NORTH);
        // F4 (B4): the bookmarks bar sits between the toolbar and the page,
        // its visibility is the persisted showBookmarksBar setting
        JPanel belowToolbar = new JPanel(new BorderLayout());
        belowToolbar.add(bookmarksBar, BorderLayout.NORTH);
        belowToolbar.add(toolbar, BorderLayout.SOUTH);
        bookmarksBar.setVisible(settingsRepository.load().isShowBookmarksBar());
        north.add(belowToolbar, BorderLayout.SOUTH);
        add(north, BorderLayout.NORTH);
        add(cardHost, BorderLayout.CENTER);
        // F5 (D3): the download shelf slides in at the bottom (A9); hidden
        // until the first active download or a Ctrl+J toggle.
        add(downloadShelf, BorderLayout.SOUTH);

        // Engine and CEF callbacks arrive off the EDT (plan §4.2) -> pump.
        controller.addListener(event -> SwingUtilities.invokeLater(() -> onTabEvent(event)));
        engine.addStateListener(state -> SwingUtilities.invokeLater(() -> onEngineState(state)));
        // The JCEF setup dialog belongs to the moment the user actually opens the
        // browser tab, not to app boot: the engine may already be FAILED while the
        // panel is still hidden, and a popup at boot (with no visible window to
        // center it on) was both noise and an L&F-timing crash risk.
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && isShowing()
                    && engine.getState() == BrowserEngineState.FAILED
                    && engine.getFailureKind() == BrowserEngine.FailureKind.MISSING_JCEF) {
                offerJcefDialogIfVisible();
            }
        });

        // F9: the new-tab page (H5) joins the dynamic internal pages; the
        // settings page's clear-data action (C6) and the bookmark
        // export/import pickers (B6) are implemented by this panel (EDT);
        // the max-tabs cap (C8) applies the saved value; the inactive-tab
        // discard policy (T10/D13) ticks in the background.
        InternalPage.registerRenderer(NewTabPageRenderer.PAGE,
                new NewTabPageRenderer(historyStore, settingsRepository::load));
        settingsRenderer.setClearDataAction(this::openClearDataDialog);
        settingsRenderer.setOnSaved(this::onSettingsSaved);
        bookmarkRenderer.setExportPicker(this::pickBookmarkExportFile);
        bookmarkRenderer.setImportPicker(this::pickBookmarkImportFile);
        controller.setMaxTabs(settingsRepository.load().getMaxTabs());
        startDiscardTimer();
        installKeyBindings();
        onEngineState(engine.getState());
    }

    // ------------------------------------------------------------------
    // Keyboard (T1, T2, T4, T7, N9 — Appendix B)
    // ------------------------------------------------------------------

    private void installKeyBindings() {
        // WHEN_ANCESTOR_OF_FOCUSED_COMPONENT (the codebase-wide idiom): a
        // plain JPanel's WHEN_IN_FOCUSED_WINDOW map is never consulted by
        // JComponent.processKeyBindings (only JRootPane checks that
        // condition) — F1 shipped with dead bindings.
        InputMap im = getInputMap(JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
        ActionMap am = getActionMap();
        bind(im, am, "browser.newTab", KeyStroke.getKeyStroke("ctrl T"), () -> controller.openNewTab());
        bind(im, am, "browser.closeTab", KeyStroke.getKeyStroke("ctrl W"),
                () -> controller.getActiveTab().ifPresent(t -> controller.closeTab(t.getId())));
        bind(im, am, "browser.restoreTab", KeyStroke.getKeyStroke("ctrl shift T"),
                () -> controller.restoreClosedTab());
        bind(im, am, "browser.nextTab", KeyStroke.getKeyStroke("ctrl TAB"), () -> controller.activateNext());
        bind(im, am, "browser.prevTab", KeyStroke.getKeyStroke("ctrl shift TAB"), () -> controller.activatePrevious());
        for (int i = 1; i <= 8; i++) {
            final int index = i - 1;
            bind(im, am, "browser.goto" + i, KeyStroke.getKeyStroke("ctrl " + i),
                    () -> controller.activateIndex(index));
        }
        bind(im, am, "browser.gotoLast", KeyStroke.getKeyStroke("ctrl 9"), () -> controller.activateLast());
        bind(im, am, "browser.focusOmnibox", KeyStroke.getKeyStroke("ctrl L"), toolbar::focusOmnibox);
        // F3 (H4): Ctrl+H opens the history page in the active tab.
        bind(im, am, "browser.history", KeyStroke.getKeyStroke("ctrl H"),
                () -> {
                    String tabId = activeTabId();
                    if (tabId != null) {
                        registry.navigate(tabId, "signum://history");
                    }
                });
        // F4 (B1): Ctrl+D toggles the active page's bookmark (the star).
        bind(im, am, "browser.toggleBookmark", KeyStroke.getKeyStroke("ctrl D"),
                toolbar::toggleBookmark);
        // F4 (B4): Ctrl+Shift+B shows/hides the bookmarks bar (persisted).
        bind(im, am, "browser.toggleBookmarksBar", KeyStroke.getKeyStroke("ctrl shift B"),
                () -> {
                    boolean show = !bookmarksBar.isVisible();
                    bookmarksBar.setVisible(show);
                    BrowserSettings settings = settingsRepository.load();
                    settings.setShowBookmarksBar(show);
                    settingsRepository.save(settings);
                });
        bind(im, am, "browser.reload", KeyStroke.getKeyStroke("F5"), () -> registry.reload(activeTabId()));
        // F5 (D3): Ctrl+J toggles the download shelf (Appendix B).
        bind(im, am, "browser.toggleDownloads", KeyStroke.getKeyStroke("ctrl J"),
                downloadShelf::toggle);
        bind(im, am, "browser.reloadAlt", KeyStroke.getKeyStroke("ctrl R"), () -> registry.reload(activeTabId()));
        bind(im, am, "browser.back", KeyStroke.getKeyStroke("alt LEFT"), () -> registry.back(activeTabId()));
        bind(im, am, "browser.forward", KeyStroke.getKeyStroke("alt RIGHT"), () -> registry.forward(activeTabId()));
        bind(im, am, "browser.stop", KeyStroke.getKeyStroke("ESCAPE"), toolbar::onEscape);
        setFocusable(true);
    }

    private void bind(InputMap im, ActionMap am, String name, KeyStroke stroke, Runnable action) {
        im.put(stroke, name);
        am.put(name, new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                action.run();
            }
        });
    }

    private String activeTabId() {
        return controller.getActiveTab().map(BrowserTab::getId).orElse(null);
    }

    /** The OS default downloads directory (D1 fallback, shown in the settings). */
    private static Path defaultDownloadsDir() {
        return Path.of(System.getProperty("user.home", "."), "Downloads");
    }

    /**
     * D1: the download base folder — the configured directory (C5, F6 UI)
     * when set, otherwise the user's OS Downloads folder. The manager
     * falls back further (temp dir) if the directory cannot be created;
     * an unparseable stored value degrades to the default (D17: a bad
     * setting must never kill a download on the CEF thread).
     */
    private static Path resolveDownloadsDir(BrowserSettings settings) {
        String dir = settings.getDownloadsDir();
        if (dir != null && !dir.isBlank()) {
            try {
                return Path.of(dir.trim());
            } catch (RuntimeException e) {
                logger.warn("Invalid downloadsDir in the settings ({}), using the default", dir);
            }
        }
        return defaultDownloadsDir();
    }

    /**
     * F6 (C5): the folder chooser of the settings page. The request arrives
     * on a CEF thread, so the dialog is pumped to the EDT and the caller
     * blocks until the user decides.
     *
     * @param currentDir the currently configured directory ("" = the default)
     * @return the chosen directory, or null when the user cancels
     */
    private String pickDownloadsDir(String currentDir) {
        String[] chosen = new String[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFileChooser chooser = new JFileChooser(defaultDownloadsDir().toString());
                if (currentDir != null && !currentDir.isBlank()) {
                    try {
                        chooser.setCurrentDirectory(new File(currentDir));
                    } catch (RuntimeException ignored) {
                        // an odd saved path — the default directory stays
                    }
                }
                chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
                if (chooser.showDialog(this, I18n.get("browser.settings.downloadsDir"))
                        == JFileChooser.APPROVE_OPTION) {
                    chosen[0] = chooser.getSelectedFile().getAbsolutePath();
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException ignored) {
            // the chooser could not be shown — treated as a cancel
        }
        return chosen[0];
    }

    // ------------------------------------------------------------------
    // F9: clear data, bookmark files, settings hooks, tab discard
    // ------------------------------------------------------------------

    /**
     * F9 (C6/S6): the clear-browsing-data flow, opened by the settings
     * page. The request arrives on a CEF scheme thread, so the dialog (and
     * the removal itself) are pumped to the EDT while the scheme thread
     * waits (the {@code pickDownloadsDir} pattern).
     */
    private void openClearDataDialog() {
        try {
            SwingUtilities.invokeAndWait(this::clearBrowsingData);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException ignored) {
            // the dialog could not be shown — treated as a cancel
        }
    }

    /**
     * Runs on the EDT: the dialog only asks; this panel orchestrates the
     * removal (history in the module store, cookies via the engine's
     * {@link CefDataCleaner}) and shows the outcome with the real counts.
     */
    private void clearBrowsingData() {
        java.awt.Window owner = SwingUtilities.getWindowAncestor(this);
        if (owner == null) {
            return;
        }
        ClearDataDialog.Selection selection = ClearDataDialog.show(owner);
        if (selection == null || !selection.anything()) {
            return; // canceled or nothing selected
        }
        int historyRemoved = selection.history() ? historyStore.clear() : 0;
        boolean cookiesCleared = false;
        if (selection.cookies()) {
            int cleared = selection.host().isEmpty()
                    ? CefDataCleaner.clearAllCookies()
                    : CefDataCleaner.clearCookiesForHost(selection.host());
            cookiesCleared = cleared > 0;
        }
        String message;
        if (historyRemoved == 0 && !cookiesCleared) {
            message = I18n.get("browser.clearData.nothing");
        } else {
            message = I18n.get("browser.clearData.done", historyRemoved,
                    selection.cookies() && !selection.host().isEmpty()
                            ? I18n.get("browser.clearData.hostSuffix", selection.host())
                            : "");
        }
        ClearDataDialog.showResult(owner, message);
    }

    /**
     * F4 (B6): the bookmark export target chooser. The request arrives on a
     * CEF scheme thread; the dialog is pumped to the EDT and the caller
     * blocks until the user decides.
     *
     * @param defaultName the suggested file name
     * @return the chosen path, or null when the user cancels
     */
    private String pickBookmarkExportFile(String defaultName) {
        String[] chosen = new String[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFileChooser chooser = new JFileChooser(confDir.toString());
                chooser.setSelectedFile(new File(defaultName));
                if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                    chosen[0] = chooser.getSelectedFile().getAbsolutePath();
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException ignored) {
            // the chooser could not be shown — treated as a cancel
        }
        return chosen[0];
    }

    /**
     * F4 (B6): the bookmark import source chooser (the same EDT pump as the
     * export one).
     *
     * @return the chosen path, or null when the user cancels
     */
    private String pickBookmarkImportFile() {
        String[] chosen = new String[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                JFileChooser chooser = new JFileChooser(confDir.toString());
                chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
                if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                    chosen[0] = chooser.getSelectedFile().getAbsolutePath();
                }
            });
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (InvocationTargetException ignored) {
            // the chooser could not be shown — treated as a cancel
        }
        return chosen[0];
    }

    /**
     * F9 (C8): a settings save may change the max-tabs cap — it applies
     * live (the hook fires on the scheme thread; the GUI pumps it).
     */
    private void onSettingsSaved() {
        SwingUtilities.invokeLater(
                () -> controller.setMaxTabs(settingsRepository.load().getMaxTabs()));
    }

    /** F9 (T10/D13): the periodic inactive-tab discard check (EDT). */
    private void startDiscardTimer() {
        Timer timer = new Timer(DISCARD_CHECK_INTERVAL_MS, e -> runDiscardPolicy());
        timer.setRepeats(true);
        timer.start();
        discardTimer = timer;
    }

    /**
     * F9 (T10/D13): one tick of the discard policy (EDT). Every inactive,
     * non-internal tab idle beyond the configured delay loses its engine;
     * the tab itself stays in the strip, a placeholder takes the browser's
     * place until the tab is activated again ({@link #restoreDiscarded}).
     * A delay of {@code 0} (or less) disables the policy.
     */
    private void runDiscardPolicy() {
        int minutes = settingsRepository.load().getDiscardMinutes();
        if (minutes <= 0) {
            return; // disabled
        }
        List<String> discardable = TabDiscardPolicy.discardable(
                controller.getTabs(), activeTabId(), System.currentTimeMillis(), minutes);
        for (String tabId : discardable) {
            if (controller.getTab(tabId).isEmpty()) {
                continue; // closed between the policy check and now
            }
            registry.discard(tabId); // the engine is released
            contentPanel.replaceBrowser(tabId, newDiscardPlaceholder());
            controller.setDiscarded(tabId, true); // UPDATED → the tab strip refreshes
        }
        if (!discardable.isEmpty()) {
            logger.info("Discarded {} inactive tab(s)", discardable.size());
        }
    }

    /** The stand-in shown for a discarded tab's content (T10). */
    private JComponent newDiscardPlaceholder() {
        JLabel label = new JLabel(I18n.get("browser.tab.discarded"), SwingConstants.CENTER);
        label.setBorder(BorderFactory.createEmptyBorder(40, 20, 40, 20));
        JPanel placeholder = new JPanel(new FlowLayout());
        placeholder.setOpaque(false);
        placeholder.add(label);
        return placeholder;
    }

    /**
     * F9 (T10): switching back to a discarded tab recreates its engine
     * transparently — the registry lazily rebuilds the browser from the
     * tab's URL and the content panel swaps the placeholder back.
     */
    private void restoreDiscarded(BrowserTab tab) {
        JComponent component = (JComponent) registry.component(tab.getId());
        contentPanel.replaceBrowser(tab.getId(), component);
        controller.setDiscarded(tab.getId(), false);
    }

    // ------------------------------------------------------------------
    // Engine state (D4 lifecycle → UI)
    // ------------------------------------------------------------------

    private void onEngineState(BrowserEngineState state) {
        switch (state) {
            case READY -> {
                if (!initialTabsRestored) {
                    initialTabsRestored = true;
                    restoreInitialTabs(); // T8
                }
                cards.show(cardHost, CARD_CONTENT);
            }
            case FAILED -> {
                boolean missingJcef = engine.getFailureKind() == BrowserEngine.FailureKind.MISSING_JCEF;
                readyScreen.showError(engine.getFailureReason(), missingJcef
                        ? I18n.get("browser.engine.failed.hint.jcef")
                        : I18n.get("browser.engine.failed.hint"));
                cards.show(cardHost, CARD_ENGINE);
                // F2.1: the one-shot JCEF setup dialog (install + retry) — offered
                // only while this tab is actually visible (see the method).
                if (missingJcef) {
                    offerJcefDialogIfVisible();
                }
            }
            case SHUTTING_DOWN, SHUT_DOWN -> {
                if (discardTimer != null) { // F9 (T10): the discard check stops
                    discardTimer.stop();
                    discardTimer = null;
                }
                sessionStore.save(controller.snapshot()); // persist before teardown
                registry.clear();
                InternalPage.registerRenderer(HistoryPageRenderer.PAGE, null);
                InternalPage.registerRenderer(NewTabPageRenderer.PAGE, null);
                InternalPage.registerRenderer(BookmarkPageRenderer.PAGE, null);
                InternalPage.registerRenderer(SettingsPageRenderer.PAGE, null);
                InternalPage.registerRenderer(DownloadsPageRenderer.PAGE, null);
                InternalPage.registerRenderer(AboutPageRenderer.PAGE, null);
                InternalPage.setThemeStyleProvider(null);
                historyStore.close(); // F3: drain the batch writer, release SQLite
                activeDownloads.clear(); // F5: drop the CEF cancel hooks
                downloadManager.close(); // F5: persist the recent list
                readyScreen.showIdle();
                cards.show(cardHost, CARD_ENGINE);
            }
            default -> {
                readyScreen.showPreparing();
                cards.show(cardHost, CARD_ENGINE);
            }
        }
    }

    /**
     * T8: the first tabs, per the {@code startup} setting:
     * {@code newtab} → a fresh NTP; {@code last-session} → the saved session
     * (or an NTP when it is empty); {@code urls} → the configured list.
     */
    private void restoreInitialTabs() {
        BrowserSettings settings = settingsRepository.load();
        switch (settings.getStartup()) {
            case NEW_TAB -> controller.openNewTab();
            case LAST_SESSION -> sessionStore.load()
                    .filter(snap -> !snap.getTabs().isEmpty())
                    .ifPresentOrElse(
                            snapshot -> controller.restore(snapshot),
                            controller::openNewTab);
            case URLS -> {
                if (settings.getStartupUrls().isEmpty()) {
                    controller.openNewTab();
                } else {
                    for (String url : settings.getStartupUrls()) {
                        controller.openTab(url, TabSource.RESTORE);
                    }
                }
            }
        }
    }

    /**
     * F2.1: offers the one-shot JCEF setup dialog, but only while the browser
     * tab is actually visible and it has not been offered yet (user request:
     * the install popup appears when the user clicks the browser tab, not at
     * boot). Until then the failed engine screen carries the install hint.
     */
    private void offerJcefDialogIfVisible() {
        if (jcefDialogOffered || !isShowing() || !JcefProvisioner.isSupported()) {
            return;
        }
        jcefDialogOffered = true;
        JcefSetupDialog.show(SwingUtilities.getWindowAncestor(this),
                () -> engine.init(confDir));
    }

    // ------------------------------------------------------------------
    // Tab events (controller → tab bar + content)
    // ------------------------------------------------------------------

    private void onTabEvent(TabEvent event) {
        switch (event.getType()) {
            case ADDED -> {
                JComponent component = (JComponent) registry.component(event.getTab().getId());
                contentPanel.addBrowser(event.getTab().getId(), component);
                tabBar.animateBirth(event.getTab().getId());
                tabBar.refresh();
            }
            case REMOVED -> {
                registry.remove(event.getTab().getId());
                contentPanel.removeBrowser(event.getTab().getId());
                tabBar.refresh();
                saveSessionSoon();
            }
            case MOVED -> tabBar.refresh();
            case UPDATED -> tabBar.refresh();
            case ACTIVATED -> {
                BrowserTab activated = event.getTab();
                if (activated.isDiscarded()) {
                    restoreDiscarded(activated); // F9 (T10): the transparent restore
                }
                tabBar.refresh();
                contentPanel.showBrowser(activated.getId());
                saveSessionSoon();
            }
        }
    }

    /**
     * Debounced session persistence: the snapshot is taken now (EDT, cheap),
     * the JSON write happens once the quiet period passes (the store writes
     * atomically; a second save simply supersedes it).
     */
    private void saveSessionSoon() {
        if (sessionSaveTimer != null && sessionSaveTimer.isRunning()) {
            return;
        }
        SessionSnapshot snapshot = controller.snapshot();
        sessionSaveTimer = new Timer(SESSION_SAVE_DEBOUNCE_MS, e -> sessionStore.save(snapshot));
        sessionSaveTimer.setRepeats(false);
        sessionSaveTimer.start();
    }
}