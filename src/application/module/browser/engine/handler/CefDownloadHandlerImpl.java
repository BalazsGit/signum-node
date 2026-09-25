package application.module.browser.engine.handler;

import application.module.browser.model.download.DownloadItem;
import application.module.browser.model.download.DownloadManager;
import org.cef.browser.CefBrowser;
import org.cef.callback.CefBeforeDownloadCallback;
import org.cef.callback.CefDownloadItem;
import org.cef.callback.CefDownloadItemCallback;
import org.cef.handler.CefDownloadHandlerAdapter;

import java.util.HashMap;
import java.util.Map;

/**
 * Download events of one tab (plan F5, D1–D3). Security rule 5: every
 * download passes through this handler — web content can never write to an
 * arbitrary local path, the target is always the module's configured
 * directory (the C5 setting, resolved by the {@link DownloadManager}).
 * <p>
 * <b>Flow (pinned JCEF, CEF 146):</b> {@code onBeforeDownload} registers the
 * item, resolves the collision-free file name (D1) and answers
 * {@code callback.Continue(path, false)} — the download proceeds to exactly
 * that path, no file dialog. Chromium then performs the IO itself and
 * reports progress through {@code onDownloadUpdated}, whose booleans the
 * {@link DownloadManager} maps to its state machine (D2). The update
 * callback doubles as the cancel hook: it is kept in the
 * {@link ActiveDownloadRegistry} while the transfer is alive so the global
 * shelf can cancel any download (the hook posts the cancel to the CEF UI
 * thread natively).
 * <p>
 * <b>Threading:</b> both callbacks arrive on a CEF thread. The item getters
 * are read inline (they must), the {@link DownloadManager} mutation is
 * pumped to the EDT like every other handler in this package (plan §4.2).
 */
final class CefDownloadHandlerImpl extends CefDownloadHandlerAdapter {

    private final DownloadManager downloads;
    private final ActiveDownloadRegistry activeDownloads;
    /** This CEF context's download-item id → our id (ids are per-context). */
    private final Map<Integer, String> cefIds = new HashMap<>();

    CefDownloadHandlerImpl(DownloadManager downloads, ActiveDownloadRegistry activeDownloads) {
        this.downloads = downloads;
        this.activeDownloads = activeDownloads;
    }

    @Override
    public boolean onBeforeDownload(CefBrowser browser, CefDownloadItem item, String suggestedName,
                                    CefBeforeDownloadCallback callback) {
        String url = item.getURL();
        String name = item.getSuggestedFileName() != null ? item.getSuggestedFileName() : suggestedName;
        int cefId = item.getId();
        // The path must be decided before Continue, synchronously (CEF thread).
        DownloadItem registered = downloads.register(url, name, cefId);
        cefIds.put(cefId, registered.getId());
        callback.Continue(registered.getTargetPath(), false); // no dialog (D1)
        BrowserCefHandlers.runInEdt(downloads::publish);
        return true;
    }

    @Override
    public void onDownloadUpdated(CefBrowser browser, CefDownloadItem item, CefDownloadItemCallback callback) {
        String ourId = cefIds.get(item.getId());
        if (ourId == null) {
            return; // a download this handler never registered (defensive)
        }
        activeDownloads.register(ourId, callback); // the live cancel hook (D2)
        long received = item.getReceivedBytes();
        long total = item.getTotalBytes();
        int percent = item.getPercentComplete();
        long speed = item.getCurrentSpeed();
        boolean inProgress = item.isInProgress();
        boolean complete = item.isComplete();
        boolean canceled = item.isCanceled();
        if (complete || canceled) {
            cefIds.remove(item.getId());
            activeDownloads.unregister(ourId);
        }
        BrowserCefHandlers.runInEdt(() ->
                downloads.update(ourId, received, total, percent, speed, inProgress, complete, canceled));
    }
}