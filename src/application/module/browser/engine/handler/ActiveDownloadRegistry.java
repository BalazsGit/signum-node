package application.module.browser.engine.handler;

import org.cef.callback.CefDownloadItemCallback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The cancel hooks of the active downloads (plan F5, D2): our download id →
 * the CEF {@link CefDownloadItemCallback} the tab handler last reported for
 * it. The download shelf is global while a download belongs to the tab that
 * started it, so the hooks are shared module-wide.
 * <p>
 * CEF's callback methods post the cancel to the CEF UI thread natively, so
 * invoking {@link #cancel} from the EDT is safe. Engine layer only — the
 * model never sees CEF objects (D7).
 */
public final class ActiveDownloadRegistry {

    private static final Logger logger = LoggerFactory.getLogger(ActiveDownloadRegistry.class);

    private final Map<String, CefDownloadItemCallback> callbacks = new ConcurrentHashMap<>();

    /** Stores/replaces the cancel hook for the download (CEF callback thread). */
    public void register(String downloadId, CefDownloadItemCallback callback) {
        if (downloadId != null && callback != null) {
            callbacks.put(downloadId, callback);
        }
    }

    /** Drops the hook (terminal downloads have nothing left to cancel). */
    public void unregister(String downloadId) {
        if (downloadId != null) {
            callbacks.remove(downloadId);
        }
    }

    /**
     * Cancels the download with the given id (D2).
     *
     * @return {@code true} when an active hook was found and invoked
     */
    public boolean cancel(String downloadId) {
        CefDownloadItemCallback callback = downloadId == null ? null : callbacks.get(downloadId);
        if (callback == null) {
            return false;
        }
        try {
            callback.cancel();
            return true;
        } catch (RuntimeException e) {
            logger.warn("Could not cancel download {} through CEF", downloadId, e);
            return false;
        }
    }

    /** Drops all hooks (engine shutdown). */
    public void clear() {
        callbacks.clear();
    }
}