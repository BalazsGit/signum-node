package application.module.browser.core;

import org.cef.browser.CefBrowser;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.KeyboardFocusManager;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeListener;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import javax.swing.SwingUtilities;

/**
 * Application-wide guard that keeps the windowed JCEF browser from holding the
 * OS-level keyboard focus while the user works in the Swing UI (and vice versa).
 * <p>
 * <h3>Root cause</h3>
 * In windowed rendering mode (D1) the web content lives in a <em>native child
 * window</em> (a heavyweight {@code Canvas} inside the browser's lightweight
 * wrapper panel). That native window grabs OS-level keyboard focus when the
 * user clicks the page, and AWT never sees the click — so the AWT focus owner
 * stays on the last Swing component. JCEF ships a {@code FocusListener} on the
 * wrapper panel that releases the CEF focus ({@code focusLost →
 * setFocus(false)}), but it stays dormant because the wrapper never gains AWT
 * focus. The net effect: after a page click every Swing input field in the
 * <em>entire application</em> (browser omnibox, node config ports, node
 * logging handlers, …) looks focused but silently swallows keystrokes — and
 * after screen lock / sleep / Alt+Tab, Windows restores the OS keyboard focus
 * to the CEF child window (or leaves it orphaned), so the same symptoms
 * appear without any page click at all.
 * <p>
 * <h3>Strategy</h3>
 * The guard enforces one invariant: <b>while the user works in the Swing UI,
 * no CEF browser may hold the keyboard focus</b>. It is installed at three
 * levels:
 * <ul>
 *   <li><b>Top-level window</b> (auto-attached when the first browser
 *       component is shown): a mouse press anywhere in the Swing chrome
 *       releases the CEF focus; on window activation (Alt+Tab back, screen
 *       unlock, sleep resume) the guard releases the CEF focus and
 *       re-asserts the AWT focus owner, which repairs orphaned OS focus.</li>
 *   <li><b>Global</b> (once per JVM): whenever the AWT focus owner becomes a
 *       non-browser component (any module, any tab) the CEF focus is
 *       released; every AWT key event refreshes the "the user is actively
 *       typing in the Swing UI" marker.</li>
 *   <li><b>Per browser</b> (via each {@code CefClient}'s focus handler):
 *       when a browser <em>gains</em> CEF focus, the guard decides whether it
 *       is a legitimate page click or an asynchronous steal — see
 *       {@link #onCefGotFocus}.</li>
 * </ul>
 * All state is EDT-confined (JCEF callbacks are pumped to the EDT by the
 * caller, see {@code BrowserCefHandlers}).
 */
public final class CefFocusGuard {

    /**
     * How long after the user's last keystroke in the Swing UI a CEF focus
     * gain is still considered an asynchronous <em>steal</em> rather than an
     * intentional page click. While the user is actively typing in a Swing
     * field the page must never be allowed to take the keyboard; right after
     * the last keystroke the user is still "in" that field. Beyond the grace
     * period an idle Swing focus owner is treated as stale and a page click
     * is assumed.
     */
    static final long SWING_TYPING_GRACE_MS = 2000;

    /** A registered live browser and its (lightweight) AWT wrapper component. */
    private record Entry(CefBrowser browser, Component ui) {
    }

    /** EDT-confined registry of the live browsers (tab close removes the entry). */
    private static final List<Entry> entries = new ArrayList<>();

    /** The windows that already carry the per-window hooks (dedupe). */
    private static final Set<Window> attachedWindows = new HashSet<>();

    /**
     * Window → its detach listener (weakly referenced so a closed window is
     * garbage-collected even before the removal event is processed).
     */
    private static final Map<Window, WindowAdapter> windowListeners = new WeakHashMap<>();

    /** Timestamp of the last AWT key event (the "actively typing" marker). */
    private static volatile long lastSwingKeyMs = 0L;

    /** Global hooks are installed at most once per JVM (kept for test reset). */
    private static PropertyChangeListener globalFocusListener;
    private static AWTEventListener globalKeyListener;

    private CefFocusGuard() {
        // static guard — never instantiated
    }

    // ------------------------------------------------------------------
    // Registration (called from WebBrowser, EDT)
    // ------------------------------------------------------------------

    /**
     * Registers a live browser so the guard can release its CEF focus and
     * recognise its UI tree.
     *
     * @param browser the JCEF browser
     * @param ui      the component returned by {@code browser.getUIComponent()}
     */
    public static void register(CefBrowser browser, Component ui) {
        entries.add(new Entry(browser, ui));
    }

    /** Unregisters a closed browser (no-ops when already gone). */
    public static void unregister(CefBrowser browser) {
        entries.removeIf(entry -> entry.browser() == browser);
    }

    /**
     * Watches the browser's wrapper component and attaches the guard to the
     * top-level window as soon as the browser becomes visible. Called from
     * the browser's construction (the window does not exist yet at that
     * point — the component may not even be realized).
     *
     * @param ui the browser's AWT wrapper component
     */
    public static void watchComponent(Component ui) {
        ui.addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && ui.isShowing()) {
                Window window = SwingUtilities.getWindowAncestor(ui);
                if (window != null) {
                    attachWindow(window);
                }
            }
        });
    }

    /**
     * Installs the per-window hooks (mouse press, activation) on the given
     * top-level window; idempotent per window. Also installs the global
     * hooks on first use.
     *
     * @param window the top-level window hosting the browser (e.g. the app shell)
     */
    public static void attachWindow(Window window) {
        if (window == null || !attachedWindows.add(window)) {
            return;
        }
        // Any click in the Swing chrome (any module, any tab) means the user
        // is working outside the pages — the native CEF windows never
        // generate AWT mouse events, so a mouse press here can only come
        // from the Swing UI.
        window.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                releaseAll();
            }
        });
        // Window activation: Alt+Tab back, screen unlock, sleep resume.
        // Windows restores the OS keyboard focus to the previously focused
        // child window — often the (now hidden) CEF window or an orphan —
        // while AWT believes a Swing component has focus. Release the CEF
        // focus and re-assert the AWT owner so the two layers re-synchronise.
        WindowAdapter activation = new WindowAdapter() {
            @Override
            public void windowGainedFocus(WindowEvent e) {
                onWindowActivated();
            }
        };
        window.addWindowListener(activation);
        WindowAdapter detach = new WindowAdapter() {
            @Override
            public void windowClosed(WindowEvent e) {
                attachedWindows.remove(window);
                windowListeners.remove(window);
                window.removeWindowListener(activation);
            }
        };
        windowListeners.put(window, detach);
        window.addWindowListener(detach);
        installGlobalHooks();
    }

    /**
     * Global hooks, installed once: (1) the AWT focus owner becoming a
     * non-browser component (any module) releases the CEF focus; (2) every
     * AWT key event refreshes the active-typing marker.
     */
    private static void installGlobalHooks() {
        if (globalFocusListener == null) {
            KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            globalFocusListener = e -> handleFocusOwnerChanged(
                    e.getNewValue() instanceof Component component ? component : null);
            kfm.addPropertyChangeListener("focusedComponent", globalFocusListener);
        }
        if (globalKeyListener == null) {
            globalKeyListener = e -> {
                if (e instanceof java.awt.event.KeyEvent) {
                    lastSwingKeyMs = System.currentTimeMillis();
                }
            };
            try {
                Toolkit.getDefaultToolkit().addAWTEventListener(globalKeyListener,
                        AWTEvent.KEY_EVENT_MASK);
            } catch (SecurityException ignored) {
                // no security manager in practice; the marker simply stays stale
            }
        }
    }

    // ------------------------------------------------------------------
    // The invariant
    // ------------------------------------------------------------------

    /**
     * Releases the CEF-side keyboard focus of every live browser (each call
     * is a cheap no-op when the browser does not have focus). EDT.
     */
    public static void releaseAll() {
        for (Entry entry : List.copyOf(entries)) {
            release(entry.browser());
        }
    }

    /**
     * Releases one browser's CEF focus. Defensive: a browser may be closing
     * on a CEF thread at the same moment.
     *
     * @param browser the browser to defocus (may be {@code null}: no-op)
     */
    public static void release(CefBrowser browser) {
        if (browser == null) {
            return;
        }
        try {
            browser.setFocus(false);
        } catch (RuntimeException ignored) {
            // the browser may have closed on a CEF thread in the meantime
        }
    }

    /** @return {@code true} when the component belongs to a registered browser's UI tree. */
    public static boolean isInsideBrowser(Component component) {
        if (component == null) {
            return false;
        }
        for (Entry entry : entries) {
            Component ui = entry.ui();
            if (component == ui
                    || (ui instanceof Container container && container.isAncestorOf(component))) {
                return true;
            }
        }
        return false;
    }

    /**
     * CEF callback entry point: the given browser just <em>gained</em> the
     * CEF-side (OS-level) keyboard focus. Must be called on the EDT (the
     * client's focus handler pumps it there).
     * <p>
     * Decision:
     * <ul>
     *   <li>AWT focus owner is {@code null} or inside a browser — the user
     *       is in (or left) the pages: the focus gain is legitimate, do
     *       nothing.</li>
     *   <li>AWT focus owner is a Swing component and the user typed there
     *       within the last {@link #SWING_TYPING_GRACE_MS} ms — an
     *       <b>asynchronous steal</b> (page JS, visibility restore, …):
     *       release the browser so typing continues.</li>
     *   <li>AWT focus owner is a Swing component but the user has been idle
     *       — an <b>intentional page click</b>: keep the CEF focus and move
     *       the AWT focus owner onto the browser's wrapper. That keeps the
     *       AWT state in sync with reality and — crucially — activates
     *       JCEF's own dormant {@code focusLost → setFocus(false)} listener:
     *       when the user later clicks back into any Swing component the
     *       wrapper loses AWT focus and JCEF releases the CEF focus itself.</li>
     * </ul>
     */
    public static void onCefGotFocus(CefBrowser browser) {
        handleCefGotFocus(browser,
                KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
    }

    /**
     * Decision logic of {@link #onCefGotFocus}, with the current focus owner
     * supplied explicitly (package-private for unit testing without a live
     * window/focus state).
     *
     * @param browser the browser that just gained the CEF-side focus
     * @param owner   the current AWT focus owner (may be {@code null})
     */
    static void handleCefGotFocus(CefBrowser browser, Component owner) {
        if (owner == null || isInsideBrowser(owner)) {
            return; // the user is in the pages — the focus gain is legitimate
        }
        if (System.currentTimeMillis() - lastSwingKeyMs < SWING_TYPING_GRACE_MS) {
            // The user is actively typing in the Swing UI — this focus gain
            // is a steal; take the keyboard back for the field being edited.
            release(browser);
            return;
        }
        // Idle Swing focus owner: the user just clicked the page. Move the
        // AWT focus onto the browser wrapper (see the class javadoc).
        Entry entry = find(browser);
        if (entry != null) {
            entry.ui().requestFocusInWindow();
        }
    }

    /** Window activation handler (Alt+Tab back, unlock, sleep resume). EDT. */
    private static void onWindowActivated() {
        handleWindowActivated(
                KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
    }

    /**
     * Decision logic of the window-activation hook, with the current focus
     * owner supplied explicitly (package-private for unit testing without a
     * live window/focus state).
     *
     * @param owner the current AWT focus owner (may be {@code null})
     */
    static void handleWindowActivated(Component owner) {
        if (owner == null || !owner.isShowing() || isInsideBrowser(owner)) {
            return; // nothing to repair: no owner, or the user was in the pages
        }
        releaseAll();
        owner.requestFocusInWindow(); // re-assert: repairs orphaned OS-level focus
    }

    /**
     * Decision logic of the global {@code focusedComponent} hook, with the
     * new focus owner supplied explicitly (package-private for unit testing).
     *
     * @param newOwner the new AWT focus owner (may be {@code null})
     */
    static void handleFocusOwnerChanged(Component newOwner) {
        if (newOwner != null && !isInsideBrowser(newOwner)) {
            releaseAll();
        }
    }

    private static Entry find(CefBrowser browser) {
        for (Entry entry : entries) {
            if (entry.browser() == browser) {
                return entry;
            }
        }
        return null;
    }

    /**
     * Test seam: pretends the user just typed a key in the Swing UI (the
     * production marker is refreshed by the global AWT key listener, which
     * sees real toolkit events — synthetic test events do not).
     */
    static void markSwingTypingForTests() {
        lastSwingKeyMs = System.currentTimeMillis();
    }

    /**
     * Test seam: drops all registrations, window attachments and global
     * hooks so each test starts from a clean state.
     */
    static void resetForTests() {
        entries.clear();
        for (Window window : List.copyOf(attachedWindows)) {
            WindowAdapter listener = windowListeners.remove(window);
            if (listener != null) {
                window.removeWindowListener(listener);
            }
        }
        attachedWindows.clear();
        lastSwingKeyMs = 0L;
        if (globalFocusListener != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .removePropertyChangeListener("focusedComponent", globalFocusListener);
            globalFocusListener = null;
        }
        if (globalKeyListener != null) {
            try {
                Toolkit.getDefaultToolkit().removeAWTEventListener(globalKeyListener);
            } catch (RuntimeException ignored) {
                // toolkit may not support removal in a headless test env
            }
            globalKeyListener = null;
        }
    }
}
