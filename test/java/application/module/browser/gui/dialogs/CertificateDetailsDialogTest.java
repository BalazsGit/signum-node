package application.module.browser.gui.dialogs;

import application.module.browser.engine.security.CertificateInspector;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.awt.Window;
import java.lang.reflect.InvocationTargetException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeFalse;

/**
 * Unit tests for the {@link CertificateDetailsDialog} open-once contract
 * (S2): a repeated click on the same lock must bring the already-open
 * dialog to the front instead of opening another one (and re-running the
 * handshake); only after the dialog closes may a new one be opened.
 * <p>
 * The inspector is the production one pointed at a reserved (unresolvable)
 * host: the inspection fails fast, which is fine — the contract under test
 * is the dialog lifecycle, not the result content.
 */
@DisplayName("CertificateDetailsDialog Tests")
class CertificateDetailsDialogTest {

    /** A reserved TLD: resolution fails fast, no real network traffic. */
    private static final String URL = "https://cert-dedupe-test.invalid/";

    @Test
    @DisplayName("a second click on the same lock raises the open dialog instead of opening another")
    void secondShowRaisesInsteadOfOpeningAnother() {
        assumeFalse(GraphicsEnvironment.isHeadless(), "requires a display");
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            CertificateDetailsDialog first = showAndAwait(executor);
            CertificateDetailsDialog second = showAndAwait(executor);
            assertSame(first, second,
                    "the same URL must not open a second dialog while one is open");

            invokeOnEdt(first::dispose);
            awaitClosed(first);
            CertificateDetailsDialog third = showAndAwait(executor);
            assertNotSame(first, third,
                    "after the dialog closed, the next lock click opens a fresh one");
            invokeOnEdt(third::dispose);
            awaitClosed(third);
        } finally {
            executor.shutdownNow();
        }
    }

    /** Shows the dialog (on the EDT, like the toolbar click) and waits until visible. */
    private static CertificateDetailsDialog showAndAwait(ExecutorService executor) {
        invokeOnEdt(() -> CertificateDetailsDialog.show(null, URL,
                new CertificateInspector(), executor));
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline) {
            CertificateDetailsDialog dialog = findOpenDialog();
            if (dialog != null) {
                return dialog;
            }
            sleep(20);
        }
        fail("the certificate dialog did not open; windows="
                + java.util.Arrays.toString(Window.getWindows()));
        return null;
    }

    /**
     * The first <em>showing</em> certificate dialog (disposed dialogs stay
     * in {@link Window#getWindows()} but hidden, so they are skipped).
     */
    private static CertificateDetailsDialog findOpenDialog() {
        for (Window window : Window.getWindows()) {
            if (window instanceof CertificateDetailsDialog dialog && dialog.isShowing()) {
                return dialog;
            }
        }
        return null;
    }

    /** Runs an action on the EDT and rethrows any failure as a test failure. */
    private static void invokeOnEdt(Runnable action) {
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            fail("interrupted while waiting for the EDT");
        } catch (InvocationTargetException e) {
            fail("the EDT action failed: " + e.getCause());
        }
    }

    private static void awaitClosed(CertificateDetailsDialog dialog) {
        long deadline = System.currentTimeMillis() + 3000;
        while (System.currentTimeMillis() < deadline && dialog.isShowing()) {
            sleep(20);
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
