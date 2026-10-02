package application.module.browser.gui;

import application.module.browser.core.BrowserEngine;
import application.module.browser.model.session.SessionStore;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.concurrent.Callable;
import java.util.stream.Stream;
import javax.swing.SwingUtilities;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for the {@link BrowserPanel} lifecycle seams. No CEF is
 * involved: the engine is never initialized, so no tabs are opened and no
 * CEF browsers created — only the Swing shell and the file stores.
 */
@DisplayName("BrowserPanel Tests")
class BrowserPanelTest {

    private Path confDir;
    private BrowserPanel panel;

    @BeforeEach
    void setUp() throws Exception {
        confDir = Files.createTempDirectory("browser-panel-test");
        panel = onEdt(() -> new BrowserPanel(BrowserEngine.getInstance(), confDir));
    }

    @AfterEach
    void tearDown() {
        if (panel != null) {
            runOnEdt(panel::dispose); // idempotent
        }
        // Best-effort: the panel's history store may still hold the SQLite
        // file (in the app the engine-stop path closes it, which never runs
        // here), and Windows keeps open files locked.
        deleteRecursivelyBestEffort(confDir);
    }

    @Test
    @DisplayName("dispose flushes the tab session before exit (restart restore: T8)")
    void dispose_flushesSessionSave() throws Exception {
        Path sessionFile = confDir.resolve("session.json");
        Thread.sleep(50); // inside the 400 ms debounce window — nothing was written yet
        assertFalse(Files.exists(sessionFile),
                "no session save may have landed before the dispose flush");

        panel.dispose();

        assertTrue(Files.isRegularFile(sessionFile),
                "dispose must flush the tab session before the app exits or restarts");
        // This test opens no tabs: a valid (empty) snapshot, which load() maps
        // to Optional.empty (the app would then start with a New Tab).
        assertTrue(new SessionStore(sessionFile).load().isEmpty());
    }

    private static void runOnEdt(Runnable action) {
        try {
            SwingUtilities.invokeAndWait(action);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static <T> T onEdt(Callable<T> action) {
        final Object[] out = new Object[1];
        final Throwable[] err = new Throwable[1];
        try {
            SwingUtilities.invokeAndWait(() -> {
                try {
                    out[0] = action.call();
                } catch (Throwable t) {
                    err[0] = t;
                }
            });
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        if (err[0] != null) {
            throw new RuntimeException(err[0]);
        }
        return (T) out[0];
    }

    private static void deleteRecursivelyBestEffort(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException ignored) {
                    // locked file (open SQLite handle) — leave it in the OS temp dir
                }
            });
        } catch (IOException ignored) {
            // best-effort cleanup
        }
    }
}
