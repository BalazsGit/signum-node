package application.module.browser.core;

import org.cef.browser.CefBrowser;
import org.cef.handler.CefFocusHandler;

import java.awt.AWTEvent;
import java.awt.Component;
import java.awt.Container;
import java.awt.KeyboardFocusManager;
import java.awt.MouseInfo;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.awt.event.AWTEventListener;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.MouseEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.beans.PropertyChangeListener;
import java.lang.reflect.Method;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.JTextField;

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
 * no CEF browser may hold the keyboard focus</b>. It is installed at four
 * levels:
 * <ul>
 *   <li><b>Focus-request veto</b> (the official
 *       {@code CefFocusHandler.onSetFocus} hook, per client): while the user
 *       is in the Swing UI right now (non-browser AWT focus owner + recent
 *       keystroke or mouse press) the browser's focus <em>request</em> —
 *       navigation, load-end, or JCEF's own {@code setFocus(true)} — is
 *       cancelled before it can grab the OS keyboard focus. This is what
 *       keeps a page from re-grabbing the keyboard right after the user
 *       clicked a Swing field (e.g. the omnibox): see
 *       {@link #shouldVetoCefFocus}.</li>
 *   <li><b>Mouse press</b> (global AWT listener, once per JVM): a mouse
 *       press in the Swing chrome — anywhere outside a browser's UI tree —
 *       releases the CEF focus and reclaims the OS keyboard focus for the
 *       clicked top-level window with a direct Win32 {@code SetFocus} on
 *       the frame's HWND ({@link #reassertViaNativeSetFocus}), pulling it
 *       off any CEF child window. It must be a global listener: a
 *       {@code MouseListener} on the top-level window only sees presses
 *       targeted at the window itself — a click on any lightweight
 *       component (the omnibox, a port field, a button) is dispatched to
 *       that component and never reaches the window.</li>
 *   <li><b>Top-level window</b> (auto-attached when the first browser
 *       component is shown): on window activation (Alt+Tab back, screen
 *       unlock, sleep resume) the same re-sync runs, which repairs
 *       orphaned OS focus.</li>
 *   <li><b>Global</b> (once per JVM): whenever the AWT focus owner becomes a
 *       non-browser component (any module, any tab) the CEF focus is
 *       released; every AWT key event and every mouse press in the Swing
 *       chrome refreshes the "the user is in the Swing UI" marker.</li>
 *   <li><b>Per browser</b> (via each {@code CefClient}'s focus handler):
 *       when a browser <em>gains</em> CEF focus anyway (a direct page click
 *       goes native and bypasses the veto), the guard decides whether it is
 *       a legitimate page click or an asynchronous steal — see
 *       {@link #onCefGotFocus}.</li>
 * </ul>
 * <p>
 * <h3>Upstream status</h3>
 * The same failure mode is documented in the open JCEF issue
 * <a href="https://github.com/chromiumembedded/java-cef/issues/321">#321</a>
 * ("Windows: CEF seemingly won't relinquish focus back to Swing"): the
 * native child window keeps the OS keyboard focus while AWT reports the
 * Swing component as focused. If a fix ever ships in JCEF (for example via
 * the discussed {@code onSetFocus}/{@code uiComponent.requestFocus()}
 * fallback), re-evaluate this guard against it — the veto, the OS focus
 * re-sync, and the steal detection may then be partially redundant. Do
 * <em>not</em> port the issue's native parent-HWND {@code SetFocus} hunk:
 * it was removed upstream (commit 51ccf5b) because it crashed
 * multi-browser setups.
 * <p>
 * Note: the officially "central" cure for windowed-JCEF focus problems is
 * offscreen rendering (OSR) — the maintainer's standing advice for Swing
 * hosts. The app runs windowed by design (plan D1), so this guard applies
 * the full official windowed-mode protocol instead: the focus-request veto,
 * the AWT-side focus re-assertion, and the steal detection.
 * All state is EDT-confined (JCEF callbacks are pumped to the EDT by the
 * caller, see {@code BrowserCefHandlers}).
 */
public final class CefFocusGuard {

    /**
     * How long after the user's last Swing input (a keystroke in any Swing
     * field, or a mouse press in the Swing chrome) a CEF focus gain or
     * focus request is still considered the user being <em>in the Swing
     * UI</em> rather than an intentional page interaction. While the user is
     * actively working in the Swing UI the page must never be allowed to
     * take the keyboard; right after the last input the user is still "in"
     * that field. Beyond this period an idle Swing focus owner is treated as
     * stale and a page click is assumed.
     */
    static final long SWING_TYPING_GRACE_MS = 2000;

    /**
     * Delay before the deferred native {@code SetFocus} re-issue (see
     * {@link #scheduleDeferredReassert}). Long enough to run after the CEF child
     * window's next event-loop pass — which reverts a synchronous
     * {@code SetFocus} and is the "first click does not work, the second does"
     * symptom — short enough to feel instant.
     */
    static final long REASSERT_RETRY_DELAY_MS = 60;

    /** A registered live browser and its (lightweight) AWT wrapper component. */
    private record Entry(CefBrowser browser, Component ui) {
    }

    /**
     * Diagnostic trace, on only with {@code -Dsignum.focus.debug=true} (focus
     * problems are black boxes otherwise; the prints go to stderr and never
     * affect behaviour).
     */
    private static final boolean DEBUG = Boolean.getBoolean("signum.focus.debug");

    private static void trace(String message) {
        if (DEBUG) {
            System.err.println("[focus-guard] " + message);
        }
    }

    /** One-line component identity for the diagnostic trace. */
    private static String describe(Component component) {
        if (component == null) {
            return "null";
        }
        String text = component instanceof JTextField textComponent
                ? " text=\"" + textComponent.getText() + "\""
                : "";
        return component.getClass().getSimpleName() + text;
    }

    /**
     * Registry of the live browsers (tab close removes the entry). Copy-on-
     * write: the focus-veto decision ({@link #shouldVetoCefFocus}) reads it
     * on CEF's UI thread, while registration runs on the EDT.
     */
    private static final List<Entry> entries = new CopyOnWriteArrayList<>();

    /**
     * Listeners notified (always on the EDT) whenever a browser requests or
     * gains the CEF-side keyboard focus — a page click, a navigation, a load
     * end. The omnibox uses this to dismiss its suggestion popup when the user
     * moves to the page: the native CEF windows generate no AWT events, so the
     * omnibox field's {@code focusLost} never fires for a page click.
     */
    private static final List<Consumer<CefBrowser>> focusGainedListeners =
            new CopyOnWriteArrayList<>();

    /** The windows that already carry the per-window hooks (dedupe). */
    private static final Set<Window> attachedWindows = new HashSet<>();

    /**
     * Window → its detach listener (weakly referenced so a closed window is
     * garbage-collected even before the removal event is processed).
     */
    private static final Map<Window, WindowAdapter> windowListeners = new WeakHashMap<>();

    /** Timestamp of the last AWT key event (the "actively typing" marker). */
    private static volatile long lastSwingKeyMs = 0L;

    /**
     * Timestamp of the last mouse press in the Swing chrome (refreshed by
     * the global AWT mouse listener, EDT). A click in the Swing UI — e.g. on
     * the omnibox — means the user is in the Swing UI just as a keystroke
     * does, even though nothing has been typed yet.
     */
    private static volatile long lastSwingMouseMs = 0L;

    /** Global hooks are installed at most once per JVM (kept for test reset). */
    private static PropertyChangeListener globalFocusListener;
    private static AWTEventListener globalInputListener;

    /**
     * Recursion guard: {@code Window.requestFocus()} (the native SetFocus
     * re-issue used by the re-sync) posts a {@code WINDOW_GAINED_FOCUS}
     * event on the window, which would otherwise re-enter the
     * window-activation handler. EDT.
     */
    private static boolean reassertInProgress;

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
     * Registers a listener notified (on the EDT) whenever a browser requests
     * or gains the CEF keyboard focus (see {@link #focusGainedListeners}).
     */
    public static void addFocusGainedListener(Consumer<CefBrowser> listener) {
        focusGainedListeners.add(listener);
    }

    /** Removes a listener registered by {@link #addFocusGainedListener}. */
    public static void removeFocusGainedListener(Consumer<CefBrowser> listener) {
        focusGainedListeners.remove(listener);
    }

    /**
     * Notifies the {@link #focusGainedListeners}. May be called from CEF's UI
     * thread (the {@code onSetFocus} veto path), so the dispatch is marshalled
     * to the EDT; a no-op when nothing is registered.
     */
    private static void notifyFocusGained(CefBrowser browser) {
        if (focusGainedListeners.isEmpty()) {
            return;
        }
        Runnable notifyAll = () -> {
            for (Consumer<CefBrowser> listener : focusGainedListeners) {
                try {
                    listener.accept(browser);
                } catch (RuntimeException ex) {
                    trace("focusGained listener failed: " + ex);
                }
            }
        };
        if (SwingUtilities.isEventDispatchThread()) {
            notifyAll.run();
        } else {
            SwingUtilities.invokeLater(notifyAll);
        }
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
     * Installs the per-window hooks (activation) on the given top-level
     * window; idempotent per window. Also installs the global hooks on
     * first use. (The Swing-chrome mouse-press hook is global — a
     * {@code MouseListener} on the window would only see presses targeted
     * at the window itself, never a click on a lightweight component.)
     *
     * @param window the top-level window hosting the browser (e.g. the app shell)
     */
    public static void attachWindow(Window window) {
        if (window == null || !attachedWindows.add(window)) {
            return;
        }
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
     * AWT key event and every mouse press in the Swing chrome refreshes
     * the active-input marker (a press additionally re-asserts the OS
     * focus, see {@link #handleSwingMousePress}).
     */
    private static void installGlobalHooks() {
        if (globalFocusListener == null) {
            KeyboardFocusManager kfm = KeyboardFocusManager.getCurrentKeyboardFocusManager();
            globalFocusListener = e -> handleFocusOwnerChanged(
                    e.getNewValue() instanceof Component component ? component : null);
            kfm.addPropertyChangeListener("focusOwner", globalFocusListener);
        }
        if (globalInputListener == null) {
            globalInputListener = e -> {
                if (e instanceof java.awt.event.KeyEvent) {
                    lastSwingKeyMs = System.currentTimeMillis();
                } else if (e instanceof MouseEvent press && press.getID() == MouseEvent.MOUSE_PRESSED) {
                    handleSwingMousePress(press);
                }
            };
            try {
                Toolkit.getDefaultToolkit().addAWTEventListener(globalInputListener,
                        AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK);
            } catch (SecurityException ignored) {
                // no security manager in practice; the markers simply stay stale
            }
        }
    }

    /**
     * The Swing-chrome half of the global mouse-press hook (runs on the
     * EDT as part of AWT event dispatch, before the press is delivered to
     * its target). A press outside every browser's UI tree means the user
     * is working in the Swing UI: refresh the active-input marker, release
     * the CEF focus, and re-issue the native {@code SetFocus} on the
     * clicked window. Presses inside a browser's UI tree are the user
     * working in the pages — the guard must not touch them.
     *
     * @param press the mouse press event (any component, {@code null}-safe)
     */
    private static void handleSwingMousePress(MouseEvent press) {
        Component component = press.getComponent();
        if (component == null || isInsideBrowser(component)) {
            return;
        }
        lastSwingMouseMs = System.currentTimeMillis();
        trace("mouse press in Swing chrome (releaseAll + reassert)");
        releaseAll();
        reassertOsFocusOnSwingClick(SwingUtilities.getWindowAncestor(component));
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
            trace("released CEF focus (setFocus(false))");
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
     *   <li>AWT focus owner is a Swing component and the user has been
     *       inputting there (typing or clicking) within the last
     *       {@link #SWING_TYPING_GRACE_MS} ms — an
     *       <b>asynchronous steal</b> (page JS, visibility restore, …):
     *       release the browser so the Swing field keeps the keyboard.</li>
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
        notifyFocusGained(browser);
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
        handleCefGotFocus(browser, owner, isMouseOverBrowser(browser));
    }

    /**
     * Decision logic of {@link #onCefGotFocus}, with the current focus owner
     * and the mouse-over-page signal supplied explicitly (package-private for
     * unit testing without a live window/focus/mouse state).
     * <p>
     * A direct page click goes native and bypasses the veto (it generates no
     * AWT event and no {@code onSetFocus} — see the class javadoc), so this
     * hook is the only place a genuine "focus the page" request is visible.
     * The pointer being over this browser's window is what separates it from
     * an asynchronous steal (page JS, load-end, visibility restore), where the
     * pointer is wherever the user left it in the Swing chrome.
     *
     * @param browser        the browser that just gained the CEF-side focus
     * @param owner          the current AWT focus owner (may be {@code null})
     * @param mouseOverBrowser {@code true} when the pointer is over this
     *                        browser's window (the user just clicked the page)
     */
    static void handleCefGotFocus(CefBrowser browser, Component owner, boolean mouseOverBrowser) {
        if (owner == null || isInsideBrowser(owner)) {
            trace("gotFocus: owner=" + describe(owner) + " -> legitimate (pages)");
            return; // the user is in the pages — the focus gain is legitimate
        }
        if (mouseOverBrowser) {
            // The pointer is over this page: the user just clicked it. Hand the
            // keyboard to the page — move the AWT focus owner onto the wrapper
            // so the Swing caret stops (Chrome-like: clicking out of the
            // omnibox releases it; the page keeps the OS keyboard focus).
            trace("gotFocus: owner=" + describe(owner) + " mouseOverPage=true -> page click, sync AWT owner");
            syncAwTOwnerToBrowser(browser);
            return;
        }
        if (recentSwingInput()) {
            // The user is in the Swing UI right now — typing in a field or
            // having just clicked the Swing chrome (e.g. the omnibox) — and the
            // pointer is NOT over the page, so this focus gain is a steal;
            // take the keyboard back.
            trace("gotFocus: owner=" + describe(owner) + " recentInput=true -> STEAL, release");
            release(browser);
            return;
        }
        trace("gotFocus: owner=" + describe(owner) + " recentInput=false -> page click, sync AWT owner");
        // Idle Swing focus owner: the user just clicked the page. Move the
        // AWT focus onto the browser wrapper (see the class javadoc).
        syncAwTOwnerToBrowser(browser);
    }

    /** Moves the AWT focus owner onto the browser's wrapper so the Swing caret stops. */
    private static void syncAwTOwnerToBrowser(CefBrowser browser) {
        Entry entry = find(browser);
        if (entry != null) {
            entry.ui().requestFocusInWindow();
        }
    }

    /**
     * @return {@code true} when the mouse pointer is inside this browser's
     *         window — the only AWT-independent signal that separates a
     *         genuine page click from an asynchronous focus steal (a native
     *         CEF click generates no AWT mouse event; see the class javadoc).
     */
    private static boolean isMouseOverBrowser(CefBrowser browser) {
        Entry entry = find(browser);
        if (entry == null || !entry.ui().isShowing()) {
            return false;
        }
        try {
            Point mouse = MouseInfo.getPointerInfo().getLocation();
            return new Rectangle(toScreenLocation(entry.ui()), entry.ui().getSize()).contains(mouse);
        } catch (RuntimeException | Error e) {
            return false;
        }
    }

    /**
     * Screen-space top-left of a component (manual parent walk —
     * {@code getBoundsOnScreen} is removed in JDK 25; follows the existing
     * codebase pattern).
     */
    private static Point toScreenLocation(Component c) {
        int x = 0;
        int y = 0;
        for (Component current = c; current != null; current = current.getParent()) {
            x += current.getX();
            y += current.getY();
        }
        return new Point(x, y);
    }

    /** Window activation handler (Alt+Tab back, unlock, sleep resume). EDT. */
    private static void onWindowActivated() {
        handleWindowActivated(
                KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
    }

    /**
     * CEF focus-request veto — the official {@code CefFocusHandler.onSetFocus}
     * hook. CEF calls this (via the client's focus handler, synchronously on
     * CEF's UI thread) when a browser is about to take the OS keyboard focus
     * it just requested: a navigation (link click, omnibox Enter), a load
     * finishing, or JCEF's own {@code setFocus(true)} bookkeeping.
     * <p>
     * The request is vetoed only while the user is in the Swing UI <em>right
     * now</em>: the AWT focus owner is a non-browser component and there was
     * recent Swing input (keystroke or mouse press within
     * {@link #SWING_TYPING_GRACE_MS}). A stale Swing owner must never be
     * vetoed — after a real page click the owner is exactly such a stale
     * component (the native CEF windows generate no AWT events), and vetoing
     * there would make the page unfocusable.
     *
     * @param source the CEF focus source (navigation or system); accepted for
     *               the CEF contract — the decision is source-independent
     * @return {@code true} to cancel the browser's focus request
     */
    public static boolean shouldVetoCefFocus(CefFocusHandler.FocusSource source) {
        return handleCefSetFocus(
                KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
    }

    /**
     * Production entry point for the {@code onSetFocus} veto — the debug repro
     * calls {@link #shouldVetoCefFocus} directly. Besides deciding the veto it
     * fires {@link #notifyFocusGained}: a page click is the most common reason
     * CEF asks for focus, and the omnibox uses the signal to dismiss its
     * suggestion popup even when the request is vetoed (so the popup never
     * lingers after a page click). The veto itself still returns synchronously.
     */
    public static boolean onCefSetFocus(CefBrowser browser,
                                        CefFocusHandler.FocusSource source) {
        boolean veto = handleCefSetFocus(
                KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner());
        notifyFocusGained(browser);
        if (veto) {
            reassertSwingFocusOnVeto();
        }
        return veto;
    }

    /**
     * Runs on CEF's UI thread (the veto must return to CEF synchronously, so the
     * re-assert is pumped to the EDT). A vetoed CEF focus request does not move
     * the OS keyboard focus — the CEF child window still grabs it — so without
     * this the Swing caret shows while every keystroke lands in the page. Re-issue
     * the native {@code SetFocus} on the current Swing focus owner so the veto is
     * effective at the OS level: the omnibox keeps working right after a page
     * click, with no extra click needed.
     */
    private static void reassertSwingFocusOnVeto() {
        SwingUtilities.invokeLater(() -> {
            Component owner = KeyboardFocusManager
                    .getCurrentKeyboardFocusManager().getFocusOwner();
            if (owner != null && owner.isShowing() && !isInsideBrowser(owner)) {
                Window window = SwingUtilities.getWindowAncestor(owner);
                if (window != null) {
                    reassertOsFocusOnSwingClick(window);
                }
            }
        });
    }

    /**
     * Decision logic of {@link #shouldVetoCefFocus}, with the current focus
     * owner supplied explicitly (package-private for unit testing).
     *
     * @param owner the current AWT focus owner (may be {@code null})
     */
    static boolean handleCefSetFocus(Component owner) {
        if (owner == null || isInsideBrowser(owner)) {
            trace("setFocus request: owner=" + describe(owner) + " -> allow");
            return false; // the user is in (or left) the pages
        }
        boolean veto = recentSwingInput();
        trace("setFocus request: owner=" + describe(owner) + " recentInput=" + veto
                + " -> " + (veto ? "VETO" : "allow"));
        return veto;
    }

    /**
     * OS focus re-sync: when the user clicks the Swing UI, pull the OS
     * keyboard focus off any CEF child window and back onto the top-level
     * frame — the only reliable way is a direct Win32 {@code SetFocus}
     * (see {@link #reassertViaNativeSetFocus}): the empirical repro
     * (debug/FocusRepro, v1–v8, 2026-09) proved that
     * {@code CefBrowser.setFocus(false)} does not move the OS keyboard
     * focus (the page kept receiving every keystroke) and that no AWT
     * call ({@code requestFocus()}, {@code requestFocusInWindow()})
     * re-issues the native {@code SetFocus} while AWT believes the frame
     * already holds the OS focus. The native path degrades gracefully:
     * without JNA/Win32 it falls back to the legacy
     * {@code Window.requestFocus()}. The re-issue must be on the
     * <em>clicked</em> window: the OS focus of one top-level window
     * cannot be re-asserted from a click in another. No gate on visible
     * browsers: the CEF native child window persists — and keeps holding
     * the OS keyboard focus — even while its wrapper tab is hidden. EDT.
     *
     * @param clicked the top-level window the click landed in (may be
     *                {@code null}: falls back to the first attached window)
     */
    private static void reassertOsFocusOnSwingClick(Window clicked) {
        Window window = clicked != null ? clicked : null;
        if (window == null) {
            for (Window w : attachedWindows) {
                if (w.isShowing()) {
                    window = w;
                    break;
                }
            }
        }
        if (window == null || !window.isShowing()) {
            return;
        }
        reassertOsFocusOnce(window);
        scheduleDeferredReassert(window);
    }

    /**
     * Issues the native {@code SetFocus} re-sync once. Split out from
     * {@link #reassertOsFocusOnSwingClick} so the immediate call and the
     * {@link #scheduleDeferredReassert deferred re-issue} share it without
     * re-scheduling (no loop). EDT.
     */
    private static void reassertOsFocusOnce(Window window) {
        if (reassertInProgress || window == null || !window.isShowing()) {
            return; // our own re-issue posted the window event — no loop
        }
        reassertInProgress = true;
        try {
            if (!reassertViaNativeSetFocus(window)) {
                trace("reassert: native SetFocus unavailable — legacy AWT requestFocus() fallback");
                window.requestFocus();
            }
        } finally {
            reassertInProgress = false;
        }
    }

    /**
     * Re-issues the native {@code SetFocus} once, shortly after an immediate
     * re-assert. The CEF child window re-asserts its OS keyboard focus on the
     * next event-loop pass, so a single synchronous {@code SetFocus} can be
     * reverted before the user's next keystroke — the symptom "the first click
     * on the omnibox does not work, the second does" (an Alt+Tab round trip
     * works because deactivation drops the CEF child's focus first). A short
     * single-shot re-issue lets the frame have the last word. It only fires
     * while the AWT focus owner is still a Swing component, so it never steals
     * the keyboard back from a page the user intentionally focused. EDT.
     */
    private static void scheduleDeferredReassert(Window window) {
        Timer timer = new Timer((int) REASSERT_RETRY_DELAY_MS,
                e -> {
                    if (!attachedWindows.contains(window)) {
                        return; // guard reset (tab closed / test teardown)
                    }
                    Component owner = KeyboardFocusManager
                            .getCurrentKeyboardFocusManager().getFocusOwner();
                    if (owner != null && owner.isShowing() && !isInsideBrowser(owner)) {
                        trace("deferred re-assert on " + window.getClass().getSimpleName());
                        reassertOsFocusOnce(window);
                    }
                });
        timer.setRepeats(false);
        timer.start();
    }

    // ------------------------------------------------------------------
    // Win32 SetFocus (the actual OS focus pull-off; see the method docs)
    // ------------------------------------------------------------------

    /**
     * JNA bindings for the handful of user32 calls the re-sync needs. JNA
     * is declared in build.gradle (the jar was already on the runtime
     * classpath via {@code signumj}, runtimeOnly — the pin 5.7.0 adds no
     * new jar to the fat jar, it only makes it visible at compile time).
     * The interface is loaded lazily on first use;
     * on a non-Windows platform (or a broken JNA install) the load throws
     * and the caller falls back to the legacy AWT path.
     */
    private interface User32 extends com.sun.jna.Library {
        // note: SetFocus/GetForegroundWindow return an HWND, not a BOOL —
        // mapped as long (a boolean mapping would truncate it, see below)
        long SetFocus(long hWnd);

        long GetForegroundWindow();

        long GetFocus();

        boolean AttachThreadInput(int idAttach, int idAttachTo, boolean fAttach);

        int GetWindowThreadProcessId(long hWnd, int[] lpdwProcessId);
    }

    /** Minimal kernel32 binding: the calling thread's ID for the input attach. */
    private interface Kernel32 extends com.sun.jna.Library {
        int GetCurrentThreadId();
    }

    private static final String WIN32_USER32 = "user32.dll";

    private static final String WIN32_KERNEL32 = "kernel32.dll";

    /**
     * Reclaims the OS keyboard focus for the given top-level window by
     * calling Win32 {@code SetFocus} on the frame's HWND directly.
     * <p>
     * Why this and not AWT: the empirical repro (debug/FocusRepro, v1–v8,
     * 2026-09) proved that while a CEF child window holds the OS keyboard
     * focus, a lightweight Swing component (the omnibox) never gets a
     * native {@code SetFocus} issued — AWT believes the top-level frame
     * already owns the OS focus, so {@code Window.requestFocus()} and
     * {@code requestFocusInWindow()} are both silent no-ops for the OS
     * layer, and every keystroke keeps landing in the web page. Calling
     * {@code SetFocus(frameHwnd)} from the EDT (which owns the foreground
     * window in the affected scenario) moves the OS focus in one call;
     * the CEF child window then receives a focus-out and stops eating
     * keys.
     * <p>
     * Two pitfalls the repro established and this code works around:
     * <ul>
     *   <li>On JDK 25 {@code Component.getPeer()} is removed, so the HWND
     *       cannot be reflected off the peer — the fallback is
     *       {@code GetForegroundWindow()} (the frame is the foreground
     *       window in exactly the scenario this method repairs).</li>
     *   <li>{@code SetFocus} returns an <em>HWND</em>, not a BOOL: a JNA
     *       {@code boolean} mapping reads only the low 32 bits of RAX and
     *       reports a false negative whenever the low word is zero, so
     *       this code does not interpret the return value.</li>
     * </ul>
     * Escalation: if the direct call fails to resolve a window, the
     * proven fallback is to attach to the foreground window's thread
     * ({@code AttachThreadInput}) and issue {@code SetFocus} with the
     * attached input queues — the repro's C2 step, which returned the
     * CEF child window as the previous focused window. EDT.
     *
     * @param window the top-level window to reclaim the OS focus for
     * @return {@code true} when the Win32 call was made, {@code false}
     *         when JNA/Win32 is unavailable and the caller should use the
     *         legacy AWT fallback
     */
    private static boolean reassertViaNativeSetFocus(Window window) {
        try {
            User32 user32 = com.sun.jna.Native.load(WIN32_USER32, User32.class);
            long target = frameHwnd(window);
            if (target == 0L) {
                // reflection off the peer unavailable (e.g. JDK 25) — the
                // frame is the foreground window in the scenario being repaired
                target = user32.GetForegroundWindow();
            }
            if (target != 0L) {
                long prev = user32.SetFocus(target);
                long now = user32.GetFocus();
                trace("reassert: native SetFocus on " + window.getClass().getSimpleName()
                        + " target=0x" + Long.toHexString(target)
                        + " prev=0x" + Long.toHexString(prev)
                        + " now=0x" + Long.toHexString(now)
                        + (now == target ? " (landed)" : " (MISSED -> attach)"));
                if (now == target) {
                    return true; // the OS keyboard focus is on the frame now
                }
                // a CEF child window on its own input queue kept the focus:
                // fall through to the AttachThreadInput escalation below
            }
        } catch (Throwable t) {
            // non-Windows platform or JNA load failure
            trace("reassert: Win32 direct path unavailable: " + t);
        }
        return attachAndSetFocus();
    }

    /**
     * The repro-proven fallback (repro C2): attach the current (AWT)
     * thread to the input queue of the thread that owns the foreground
     * window, then issue {@code SetFocus} for that window. Windows only
     * lets a thread set the keyboard focus into another thread's windows
     * while their input queues are attached; the C2 step returned the
     * CEF child window as the previous focused window, i.e. the OS
     * focus moved off the page. The attachment is always released, even
     * on failure. EDT.
     *
     * @return {@code true} when {@code SetFocus} was issued, {@code
     *         false} otherwise (caller uses the legacy AWT fallback)
     */
    private static boolean attachAndSetFocus() {
        try {
            User32 user32 = com.sun.jna.Native.load(WIN32_USER32, User32.class);
            Kernel32 kernel32 = com.sun.jna.Native.load(WIN32_KERNEL32, Kernel32.class);
            long fg = user32.GetForegroundWindow();
            if (fg == 0L) {
                return false;
            }
            int[] pid = new int[1];
            int fgThread = user32.GetWindowThreadProcessId(fg, pid);
            int currentThread = kernel32.GetCurrentThreadId();
            if (fgThread == 0 || fgThread == currentThread) {
                // same thread owns the foreground: the direct call is allowed
                long prev = user32.SetFocus(fg);
                trace("reassert: SetFocus on foreground (same thread) prev=0x"
                        + Long.toHexString(prev));
                return true;
            }
            boolean attached = user32.AttachThreadInput(currentThread, fgThread, true);
            try {
                long prev = user32.SetFocus(fg);
                trace("reassert: SetFocus on foreground (attached to " + fgThread + ") prev=0x"
                        + Long.toHexString(prev));
                return true;
            } finally {
                if (attached) {
                    user32.AttachThreadInput(currentThread, fgThread, false);
                }
            }
        } catch (Throwable t) {
            trace("reassert: attach+SetFocus unavailable: " + t);
            return false;
        }
    }

    /**
     * Resolves the native HWND of a showing top-level window by walking
     * the AWT peer chain ({@code getPeer()} → {@code getHWnd()}), the
     * same mechanism JCEF's own window handling uses. On JDK 25+
     * {@code Component.getPeer()} is removed and this returns
     * {@code 0} — the caller then falls back to {@code GetForegroundWindow()}.
     *
     * @param window the top-level window (may be {@code null})
     * @return the window's HWND, or {@code 0} when it cannot be resolved
     */
    private static long frameHwnd(Window window) {
        if (window == null) {
            return 0L;
        }
        try {
            // protected API — reflection (removed in JDK 25, hence the
            // GetForegroundWindow() fallback in the caller)
            Method getPeer = java.awt.Component.class.getDeclaredMethod("getPeer");
            getPeer.setAccessible(true);
            Object p = getPeer.invoke(window);
            for (int i = 0; p != null && i < 5; i++) {
                Method getHwnd = p.getClass().getMethod("getHWnd");
                getHwnd.setAccessible(true);
                long hwnd = ((Number) getHwnd.invoke(p)).longValue();
                if (hwnd != 0L) {
                    return hwnd;
                }
                p = parentPeer(p); // the top-level peer may not expose its own HWND
            }
        } catch (Throwable ignored) {
            // getPeer() removed (JDK 25) or the peer chain changed — caller falls back
        }
        return 0L;
    }

    /** Best-effort parent-peer walk ({@code WContainerPeer#getParentPeer}); never throws. */
    private static Object parentPeer(Object peer) {
        try {
            Method m = peer.getClass().getMethod("getParentPeer");
            m.setAccessible(true);
            return m.invoke(peer);
        } catch (Throwable t) {
            return null;
        }
    }

    /**
     * @return {@code true} while the user's last Swing input (key or mouse)
     *         is inside the grace period
     */
    private static boolean recentSwingInput() {
        long now = System.currentTimeMillis();
        return now - lastSwingKeyMs < SWING_TYPING_GRACE_MS
                || now - lastSwingMouseMs < SWING_TYPING_GRACE_MS;
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
            trace("window activated: owner=" + describe(owner) + " -> nothing to repair");
            return; // nothing to repair: no owner, or the user was in the pages
        }
        trace("window activated: owner=" + describe(owner) + " -> releaseAll + reassert");
        releaseAll();
        Window window = SwingUtilities.getWindowAncestor(owner);
        if (window != null) {
            reassertOsFocusOnSwingClick(window); // native SetFocus: pulls OS focus off the CEF child
        }
    }

    /**
     * Decision logic of the global focus-owner hook, with the
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
     * Test seam: pretends the user just clicked the Swing UI (the production
     * marker is refreshed by the per-window mouse listener).
     */
    static void markSwingMouseForTests() {
        lastSwingMouseMs = System.currentTimeMillis();
    }

    /**
     * Test seam: drops all registrations, window attachments and global
     * hooks so each test starts from a clean state.
     */
    static void resetForTests() {
        entries.clear();
        focusGainedListeners.clear();
        for (Window window : List.copyOf(attachedWindows)) {
            WindowAdapter listener = windowListeners.remove(window);
            if (listener != null) {
                window.removeWindowListener(listener);
            }
        }
        attachedWindows.clear();
        lastSwingKeyMs = 0L;
        lastSwingMouseMs = 0L;
        if (globalFocusListener != null) {
            KeyboardFocusManager.getCurrentKeyboardFocusManager()
                    .removePropertyChangeListener("focusOwner", globalFocusListener);
            globalFocusListener = null;
        }
        if (globalInputListener != null) {
            try {
                Toolkit.getDefaultToolkit().removeAWTEventListener(globalInputListener);
            } catch (RuntimeException ignored) {
                // toolkit may not support removal in a headless test env
            }
            globalInputListener = null;
        }
    }
}
