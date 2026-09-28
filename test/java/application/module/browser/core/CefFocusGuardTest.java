package application.module.browser.core;

import org.cef.browser.CefBrowser;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JFrame;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.GraphicsEnvironment;
import java.awt.KeyboardFocusManager;
import java.awt.event.MouseEvent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeFalse;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockingDetails;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * Tests for the application-wide {@link CefFocusGuard} — the windowed-JCEF
 * focus fix that keeps the native CEF windows from holding the OS keyboard
 * focus while the user works in the Swing UI (any module), and that
 * re-synchronises the two focus layers on window activation (Alt+Tab back,
 * screen unlock, sleep resume).
 * <p>
 * The {@code CefBrowser} objects are Mockito mocks (the interface is mockable
 * without the CEF runtime); the window/focus mechanics run on real AWT
 * components — the tests that need a window skip in headless environments.
 */
@DisplayName("CefFocusGuard Tests")
class CefFocusGuardTest {

    private static void onEdt(Runnable action) {
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    @BeforeEach
    void cleanState() {
        onEdt(CefFocusGuard::resetForTests);
    }

    // ── window- and focus-based (requires a display) ──────────────────

    /**
     * One shared, always-visible frame for all window-based tests. A fresh
     * frame per test is unreliable: Windows foreground-locking does not
     * guarantee activation of repeatedly created/disposed windows, and
     * {@code requestFocusInWindow} silently fails on inactive windows.
     */
    private static JFrame sharedFrame() {
        JFrame frame = FRAME.get();
        if (frame == null) {
            frame = new JFrame("focus-guard-test-frame");
            frame.setLayout(new BorderLayout());
            frame.setSize(500, 400);
            frame.setVisible(true);
            frame.toFront();
            FRAME.set(frame);
        }
        return frame;
    }

    private static final java.util.concurrent.atomic.AtomicReference<JFrame> FRAME =
            new java.util.concurrent.atomic.AtomicReference<>();

    @AfterEach
    void detachGuardFromSharedFrame() {
        JFrame frame = FRAME.get();
        onEdt(CefFocusGuard::resetForTests);
        if (frame != null) {
            onEdt(() -> {
                frame.getContentPane().removeAll();
                frame.revalidate();
            });
        }
    }

    @org.junit.jupiter.api.AfterAll
    static void disposeSharedFrame() {
        JFrame frame = FRAME.getAndSet(null);
        onEdt(CefFocusGuard::resetForTests);
        if (frame != null) {
            onEdt(frame::dispose);
        }
    }

    /**
     * Waits (on the EDT) until the shared frame is the active window; returns
     * false when the window manager refuses to activate it (e.g. no
     * interactive desktop) — in that case the test must skip, because
     * {@code requestFocusInWindow} cannot work on an inactive window.
     */
    private static boolean awaitActiveWindow() {
        JFrame frame = FRAME.get();
        if (frame == null) {
            return false;
        }
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            if (onEdtBoolean(frame::isActive)) {
                return true;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return onEdtBoolean(frame::isActive);
    }

    private static boolean onEdtBoolean(java.util.function.BooleanSupplier action) {
        final boolean[] result = new boolean[1];
        onEdt(() -> result[0] = action.getAsBoolean());
        return result[0];
    }

    /**
     * Waits (polling the EDT) until the given component is the focus owner.
     * {@code requestFocusInWindow} is asynchronous — the focus grant happens
     * on a later EDT pass, so tests must not assert the owner immediately.
     */
    private static boolean awaitFocusOwner(Component expected) {
        long deadline = System.currentTimeMillis() + 2000;
        while (System.currentTimeMillis() < deadline) {
            if (onEdtBoolean(() -> expected ==
                    KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusOwner())) {
                return true;
            }
            try {
                Thread.sleep(25);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    /** Registers a fresh mock browser in the shared frame and attaches the guard. */
    private static CefBrowser registerInSharedFrame(JPanel wrapper) {
        CefBrowser browser = mock(CefBrowser.class);
        onEdt(() -> {
            JFrame frame = sharedFrame();
            frame.getContentPane().removeAll();
            frame.add(wrapper, BorderLayout.CENTER);
            frame.revalidate();
            CefFocusGuard.register(browser, wrapper);
            CefFocusGuard.attachWindow(frame);
        });
        return browser;
    }

    /** Adds a non-browser Swing component (e.g. another module's input) to the frame. */
    private static void addToFrame(Component component) {
        onEdt(() -> {
            JFrame frame = sharedFrame();
            frame.add(component, BorderLayout.PAGE_START);
            frame.revalidate();
        });
    }

    /** The number of setFocus(false) invocations Mockito recorded on the mock. */
    private static int defocusCalls(CefBrowser browser) {
        int count = 0;
        for (var invocation : mockingDetails(browser).getInvocations()) {
            if (invocation.getMethod().getName().equals("setFocus")
                    && invocation.getArgument(0) instanceof Boolean flag && !flag) {
                count++;
            }
        }
        return count;
    }

    // ── registry / release (headless-safe) ────────────────────────────

    @Test
    @DisplayName("isInsideBrowser recognises the registered wrapper tree")
    void isInsideBrowserRecognisesWrapperTree() {
        onEdt(() -> {
            CefBrowser browser = mock(CefBrowser.class);
            JPanel wrapper = new JPanel();
            JPanel inner = new JPanel();
            wrapper.add(inner);
            JTextField field = new JTextField();
            inner.add(field);
            JPanel outside = new JPanel();

            CefFocusGuard.register(browser, wrapper);
            assertTrue(CefFocusGuard.isInsideBrowser(wrapper), "the wrapper itself counts");
            assertTrue(CefFocusGuard.isInsideBrowser(inner), "a descendant counts");
            assertTrue(CefFocusGuard.isInsideBrowser(field), "the deepest child counts");
            assertFalse(CefFocusGuard.isInsideBrowser(outside), "a foreign component does not");
            assertFalse(CefFocusGuard.isInsideBrowser(null), "null does not");

            CefFocusGuard.unregister(browser);
            assertFalse(CefFocusGuard.isInsideBrowser(wrapper), "unregistered tree is foreign again");
        });
    }

    @Test
    @DisplayName("releaseAll releases every registered browser and survives a closing one")
    void releaseAllReleasesEveryBrowser() {
        onEdt(() -> {
            CefBrowser a = mock(CefBrowser.class);
            CefBrowser b = mock(CefBrowser.class);
            doThrow(new RuntimeException("closed on a CEF thread"))
                    .when(a).setFocus(false);
            CefFocusGuard.register(a, new JPanel());
            CefFocusGuard.register(b, new JPanel());

            CefFocusGuard.releaseAll();

            verify(a).setFocus(false); // threw — but must not break the loop
            verify(b).setFocus(false); // still released
        });
    }

    // ── window- and focus-based (requires a display) ──────────────────

    @Test
    @DisplayName("a mouse press in the Swing chrome releases the CEF focus")
    void mousePressInSwingUIReleasesCefFocus() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CefBrowser browser = registerInSharedFrame(new JPanel());
        // The guard listens on the top-level window: a real click on ANY
        // lightweight Swing component (any module, any tab) is delivered
        // there by AWT — the native CEF windows never generate AWT mouse
        // events. Here the press is delivered to the window itself.
        onEdt(() -> sharedFrame().dispatchEvent(new MouseEvent(sharedFrame(),
                MouseEvent.MOUSE_PRESSED, System.nanoTime(), 0, 10, 10, 1, false,
                MouseEvent.BUTTON1)));
        verify(browser, atLeastOnce()).setFocus(false);
    }

    @Test
    @DisplayName("AWT focus moving to a non-browser component releases the CEF focus")
    void focusMovingToSwingFieldReleasesCefFocus() {
        final CefBrowser[] browserRef = new CefBrowser[1];
        onEdt(() -> {
            CefBrowser browser = mock(CefBrowser.class);
            browserRef[0] = browser;
            JPanel wrapper = new JPanel();
            CefFocusGuard.register(browser, wrapper);
            // the user clicks a field in another module (e.g. the node config port)
            CefFocusGuard.handleFocusOwnerChanged(new JTextField("api port"));
            verify(browser, atLeastOnce()).setFocus(false);
            // …but not when the focus owner is inside a browser's own UI tree
            int before = defocusCalls(browser);
            CefFocusGuard.handleFocusOwnerChanged(wrapper);
            CefFocusGuard.handleFocusOwnerChanged(null);
            assertEquals(before, defocusCalls(browser),
                    "browser-internal (or null) focus must not release");
        });
    }

    @Test
    @DisplayName("a CEF focus gain while the user is typing in the Swing UI is a steal")
    void cefStealWhileTypingIsReleased() {
        final CefBrowser[] browserRef = new CefBrowser[1];
        onEdt(() -> {
            CefBrowser browser = mock(CefBrowser.class);
            browserRef[0] = browser;
            CefFocusGuard.register(browser, new JPanel());
            JTextField field = new JTextField("handlers"); // node logging handlers field
            CefFocusGuard.markSwingTypingForTests(); // the user just typed a key
            CefFocusGuard.handleCefGotFocus(browser, field);
            verify(browser, atLeastOnce()).setFocus(false); // the steal is released
        });
    }

    @Test
    @DisplayName("an idle page click keeps the CEF focus (no release)")
    void idlePageClickKeepsCefFocus() {
        onEdt(() -> {
            CefBrowser browser = mock(CefBrowser.class);
            CefFocusGuard.register(browser, new JPanel());
            JTextField field = new JTextField("stale owner");
            // no typing marker (fresh state per test) → the Swing owner is
            // stale → the focus gain is an intentional page click
            CefFocusGuard.handleCefGotFocus(browser, field);
            verify(browser, never()).setFocus(false);
            // the same holds when the AWT focus owner is inside the browser
            CefFocusGuard.handleCefGotFocus(browser, new JPanel());
            verify(browser, never()).setFocus(false);
        });
    }

    @Test
    @DisplayName("window activation releases the CEF focus for a showing Swing owner")
    void windowActivationReleasesCefForShowingOwner() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        CefBrowser browser = registerInSharedFrame(new JPanel()); // the visible shared frame
        JTextField field = new JTextField("api port");
        addToFrame(field); // now isShowing() — the state after Alt+Tab/unlock
        onEdt(() -> {
            CefFocusGuard.handleWindowActivated(field);
            verify(browser, atLeastOnce()).setFocus(false);
            // a not-showing owner must not trigger a repair
            int before = defocusCalls(browser);
            CefFocusGuard.handleWindowActivated(new JTextField("hidden"));
            assertEquals(before, defocusCalls(browser),
                    "a hidden owner must not trigger a repair");
        });
    }

    @Test
    @DisplayName("integration: an idle page click keeps the CEF focus and syncs AWT focus to the wrapper")
    void intentionalPageClickKeepsCefFocusAndSyncsAwTOwner() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        JPanel wrapper = new JPanel();
        CefBrowser browser = registerInSharedFrame(wrapper);
        assumeTrue(awaitActiveWindow(), "the test window could not be activated");
        JTextField field = new JTextField("stale owner");
        addToFrame(field);
        onEdt(() -> field.requestFocusInWindow());
        assumeTrue(awaitFocusOwner(field), "focus did not move to the field");
        int releasesAfterFocusIn = onEdtInt(() -> defocusCalls(browser));
        // no key events since the focus-in → the idle owner means "page click"
        onEdt(() -> CefFocusGuard.onCefGotFocus(browser));
        assertEquals(releasesAfterFocusIn, onEdtInt(() -> defocusCalls(browser)),
                "an intentional page click must not be released");
        assertTrue(awaitFocusOwner(wrapper),
                "AWT focus must move onto the browser wrapper (syncs the layers and "
                        + "activates JCEF's own focusLost→setFocus(false) path)");
    }

    private static int onEdtInt(java.util.function.IntSupplier action) {
        final int[] result = new int[1];
        onEdt(() -> result[0] = action.getAsInt());
        return result[0];
    }
}
