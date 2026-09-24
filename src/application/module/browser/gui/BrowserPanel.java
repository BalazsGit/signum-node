package application.module.browser.gui;

import application.module.browser.config.BrowserSettings;
import application.module.browser.config.BrowserSettingsRepository;
import application.module.browser.core.BrowserEngine;
import application.module.browser.core.BrowserEngineState;
import application.module.browser.engine.WebBrowserRegistry;
import application.module.browser.model.session.SessionSnapshot;
import application.module.browser.model.session.SessionStore;
import application.module.browser.model.tab.BrowserTab;
import application.module.browser.model.tab.TabController;
import application.module.browser.model.tab.TabEvent;
import application.module.browser.model.tab.TabSource;
import application.module.browser.gui.tabstrip.ChromeTabBar;

import java.awt.BorderLayout;
import java.awt.CardLayout;
import java.awt.event.ActionEvent;
import java.nio.file.Path;
import javax.swing.AbstractAction;
import javax.swing.JComponent;
import javax.swing.JPanel;
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

    private final BrowserEngine engine;
    private final Path confDir;
    private final TabController controller = new TabController();
    private final WebBrowserRegistry registry;
    private final SessionStore sessionStore;
    private final ChromeTabBar tabBar;
    private final EngineReadyScreen readyScreen;
    private final ContentPanel contentPanel;
    private final CardLayout cards = new CardLayout();
    private final JPanel cardHost = new JPanel(cards);
    private volatile boolean initialTabsRestored;
    private Timer sessionSaveTimer;

    public BrowserPanel(BrowserEngine engine, Path browserConfDir) {
        super(new BorderLayout());
        this.engine = engine;
        this.confDir = browserConfDir;
        this.registry = new WebBrowserRegistry(engine, controller);
        this.sessionStore = new SessionStore(browserConfDir.resolve("session.json"));
        this.tabBar = new ChromeTabBar(controller);
        this.readyScreen = new EngineReadyScreen();
        this.contentPanel = new ContentPanel();

        cardHost.add(readyScreen, CARD_ENGINE);
        cardHost.add(contentPanel, CARD_CONTENT);
        add(tabBar, BorderLayout.NORTH);
        add(cardHost, BorderLayout.CENTER);

        // Engine and CEF callbacks arrive off the EDT (plan §4.2) -> pump.
        controller.addListener(event -> SwingUtilities.invokeLater(() -> onTabEvent(event)));
        engine.addStateListener(state -> SwingUtilities.invokeLater(() -> onEngineState(state)));

        installKeyBindings();
        onEngineState(engine.getState());
    }

    // ------------------------------------------------------------------
    // Keyboard (T1, T2, T4, T7, N9 — Appendix B)
    // ------------------------------------------------------------------

    private void installKeyBindings() {
        InputMap im = getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
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
        bind(im, am, "browser.reload", KeyStroke.getKeyStroke("F5"), () -> registry.reload(activeTabId()));
        bind(im, am, "browser.reloadAlt", KeyStroke.getKeyStroke("ctrl R"), () -> registry.reload(activeTabId()));
        bind(im, am, "browser.back", KeyStroke.getKeyStroke("alt LEFT"), () -> registry.back(activeTabId()));
        bind(im, am, "browser.forward", KeyStroke.getKeyStroke("alt RIGHT"), () -> registry.forward(activeTabId()));
        bind(im, am, "browser.stop", KeyStroke.getKeyStroke("ESCAPE"), () -> registry.stop(activeTabId()));
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
                readyScreen.showError(engine.getFailureReason());
                cards.show(cardHost, CARD_ENGINE);
            }
            case SHUTTING_DOWN, SHUT_DOWN -> {
                sessionStore.save(controller.snapshot()); // persist before teardown
                registry.clear();
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
        BrowserSettings settings = new BrowserSettingsRepository(confDir.resolve("settings.json")).load();
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
                tabBar.refresh();
                contentPanel.showBrowser(event.getTab().getId());
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