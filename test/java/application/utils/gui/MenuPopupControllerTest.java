package application.utils.gui;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.JComponent;
import javax.swing.JFrame;
import javax.swing.JLayeredPane;
import javax.swing.JPanel;
import javax.swing.RootPaneContainer;
import javax.swing.SwingUtilities;
import java.awt.BorderLayout;
import java.awt.Dimension;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression tests for the {@link MenuPopupController#hide()} NPE
 * ({@code "Cannot invoke JPanel.setSize(int,int) because wrapper is null"}) that surfaced when a
 * popup was opened/closed rapidly — e.g. the toolbar sync menu around a
 * "Blockchain synchronization paused/resumed" push.
 *
 * <p><b>The bug:</b> the old {@code hide()} started a fresh hide-animation {@code Timer} on every
 * call with no re-entrancy guard and never stopped the show animator. A second {@code hide()} while
 * a hide animation was in flight therefore created a <i>second</i> timer animating the <i>same</i>
 * {@code wrapper}; when the first animation finished and set {@code wrapper = null}, the second
 * timer's next tick called {@code wrapper.setSize(...)} and threw an NPE on the EDT.</p>
 *
 * <p><b>The fix under test:</b> a {@code hiding} re-entrancy latch, stopping the show animator on
 * hide, and a {@code wrapper == null} guard at the top of the hide-animation tick.</p>
 *
 * <p>Headless-safe: the owning frame is packed but never shown; the animation Timers still fire on
 * the EDT.</p>
 */
@DisplayName("MenuPopupController - hide() re-entrancy + wrapper null-safety")
class MenuPopupControllerTest {

    private static JFrame newFrame() {
        JFrame frame = new JFrame("menu-popup-test");
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        JPanel body = new JPanel();
        body.setPreferredSize(new Dimension(320, 140));
        frame.getContentPane().add(body);
        frame.pack(); // headless-safe: no setVisible
        return frame;
    }

    private static JComponent newContent() {
        JComponent content = new JPanel();
        content.setPreferredSize(new Dimension(320, 140));
        return content;
    }

    /** Installs a custom uncaught-exception handler on the EDT that records every thrown error. */
    private static void captureEdtErrors(List<Throwable> sink) {
        try {
            SwingUtilities.invokeAndWait(() ->
                    Thread.currentThread().setUncaughtExceptionHandler((t, ex) -> sink.add(ex)));
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /** Blocks until the controller reaches the closed state or the deadline is hit. */
    private static boolean awaitClosed(MenuPopupController c, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline && c.isOpen()) {
            Thread.sleep(20);
        }
        return !c.isOpen();
    }

    private static Object readField(Object target, String name) throws Exception {
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                return f.get(target);
            } catch (NoSuchFieldException ignored) {
                // keep walking the hierarchy
            }
        }
        throw new NoSuchFieldException(name);
    }

    private static void writeField(Object target, String name, Object value) throws Exception {
        for (Class<?> k = target.getClass(); k != null; k = k.getSuperclass()) {
            try {
                Field f = k.getDeclaredField(name);
                f.setAccessible(true);
                f.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                // keep walking the hierarchy
            }
        }
        throw new NoSuchFieldException(name);
    }

    /** Runs an action on the EDT, blocking until it completes (wraps checked exceptions). */
    private static void onEdt(Runnable action) {
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    /**
     * Seeds the controller into an "open popup" state the way {@link MenuPopupController#show} does,
     * but WITHOUT relying on a display / {@code getLocationOnScreen()} (headless-safe). This is what
     * makes the hide-path tests deterministic: the shared {@code wrapper} + {@code open} flag are set
     * directly, so {@link MenuPopupController#hide()} proceeds and starts its animation timer.
     */
    private static void setUpOpenPopup(MenuPopupController c, JFrame frame, JComponent content) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBounds(0, 0, 320, 120); // non-zero height so the hide animation is real
        wrapper.add(content, BorderLayout.CENTER);
        ((RootPaneContainer) frame).getLayeredPane().add(wrapper, JLayeredPane.POPUP_LAYER);
        try {
            writeField(c, "wrapper", wrapper);
            writeField(c, "open", Boolean.TRUE);
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }

    @Test
    @DisplayName("a rapid hide burst never throws on the EDT and always cleans up")
    void rapidHide_neverThrows_andCleansUp() throws Exception {
        JFrame frame = newFrame();
        MenuPopupController c = new MenuPopupController(frame);
        setUpOpenPopup(c, frame, newContent());
        final List<Throwable> edtErrors = new CopyOnWriteArrayList<>();
        captureEdtErrors(edtErrors);

        onEdt(() -> {
            // The burst that used to NPE: several hides while a hide animation is still in flight
            // (a second hide() used to start a second timer animating the same wrapper).
            c.hide();
            c.hide();
            c.hide();
        });

        assertTrue(awaitClosed(c, 2000), "the popup must reach the closed state after the burst");
        Thread.sleep(150); // let any stray animation tick (and thus any NPE) surface
        frame.dispose();

        assertTrue(edtErrors.isEmpty(),
                "the EDT must not throw while the popup is hidden rapidly, got: " + edtErrors);
        assertFalse((Boolean) readField(c, "hiding"), "the hiding latch must be cleared after the hide");
        assertNull(readField(c, "wrapper"), "the wrapper must be cleaned up after the hide");
    }

    @Test
    @DisplayName("a second hide() while a hide animation is in flight is a guarded no-op")
    void hide_reentrantWhileHiding_isGuarded() throws Exception {
        JFrame frame = newFrame();
        MenuPopupController c = new MenuPopupController(frame);
        setUpOpenPopup(c, frame, newContent());

        final boolean[] hidingDuringAnimation = {false};
        onEdt(() -> {
            c.hide(); // starts the hide animation (async) -> the hiding latch must be set now
            try {
                hidingDuringAnimation[0] = (Boolean) readField(c, "hiding");
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        assertTrue(hidingDuringAnimation[0],
                "the 'hiding' latch must be set while a hide animation is in flight (guards re-entrancy)");
        assertTrue(awaitClosed(c, 2000), "the popup must close cleanly");
        frame.dispose();
    }

    @Test
    @DisplayName("the hide-animation tick is null-safe: a wrapper cleaned up mid-flight must not NPE")
    void hideTimer_fireAfterWrapperNulled_doesNotNPE() throws Exception {
        JFrame frame = newFrame();
        MenuPopupController c = new MenuPopupController(frame);
        setUpOpenPopup(c, frame, newContent());
        final List<Throwable> edtErrors = new CopyOnWriteArrayList<>();
        captureEdtErrors(edtErrors);

        onEdt(() -> {
            c.hide(); // starts the hide animation timer
            try {
                writeField(c, "wrapper", null); // simulate a concurrent cleanup (the old NPE source)
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });

        // Let the still-running timer fire; its null-check must stop it instead of throwing.
        Thread.sleep(400);
        frame.dispose();

        assertTrue(edtErrors.isEmpty(),
                "the wrapper null-check must prevent the NPE, got: " + edtErrors);
    }
}