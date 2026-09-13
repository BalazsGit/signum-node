package application.utils.gui;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Shared background executor for GUI "prep" work — disk I/O and other heavy
 * computation that must finish BEFORE an EDT rendering pass (e.g. loading a
 * profile from disk before constructing its panel, or computing cross-profile
 * conflicts before re-rendering the info bar chips).
 * <p>
 * <h3>Why a dedicated executor</h3>
 * Historically these disk reads ran directly on the EDT (tab-switch lazy load,
 * state-change refreshes), which froze the UI on slow disks. The pattern is
 * always: {@code GuiExecutors.prepare().execute(() -> { read disk;
 * SwingUtilities.invokeLater(() -> render(result)); });}
 * </p>
 * <p>
 * <h3>Thread model</h3>
 * Single daemon thread: EDT-prep tasks are short (a few file reads) and the
 * single thread keeps their completion order deterministic (a newer read does
 * not overtake an older one). Daemon so it never keeps the JVM alive.
 * </p>
 *
 * @since 5.0
 */
public final class GuiExecutors {

    private static final ThreadFactory FACTORY = r -> {
        Thread t = new Thread(r, "gui-prep");
        t.setDaemon(true);
        return t;
    };

    private static final ExecutorService PREP = Executors.newSingleThreadExecutor(FACTORY);

    private GuiExecutors() {
    }

    /**
     * @return the shared EDT-prep executor (never null; tasks run on a daemon
     *         background thread, preserving submission order)
     */
    public static ExecutorService prepare() {
        return PREP;
    }
}