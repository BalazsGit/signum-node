package application.module.browser.model.tab;

import application.module.browser.engine.security.SslStatus;

import java.util.UUID;

/**
 * One browser tab — the pure state model (plan §4.4).
 * <p>
 * No Swing and no {@code org.cef} imports by design (plan §5.2): the CEF
 * objects live in the engine layer ({@code WebBrowserRegistry}), and the GUI
 * reads this model through {@link TabEvent}s. All mutations go through
 * {@link TabController} so every change is observable as an event (SSOT).
 */
public final class BrowserTab {

    private final String id;
    private final long createdAt;
    private String url;
    private String title;
    private byte[] favicon;
    private boolean loading;
    private int progress;
    private SslStatus sslStatus;
    private boolean canGoBack;
    private boolean canGoForward;
    private boolean pinned;
    private boolean privateMode;
    private boolean discarded;
    /** S5: an http:// subresource was loaded on this (https) page. */
    private boolean mixedContent;
    private int zoomLevel;
    /** T10/D13: when the tab was last active (discard policy input). */
    private long lastActiveAt;

    BrowserTab(String initialUrl) {
        this.id = UUID.randomUUID().toString();
        this.url = initialUrl;
        this.createdAt = System.currentTimeMillis();
        this.lastActiveAt = this.createdAt;
        this.sslStatus = SslStatus.forUrl(initialUrl);
        this.zoomLevel = 0;
    }

    public String getId() {
        return id;
    }

    public long getCreatedAt() {
        return createdAt;
    }

    public String getUrl() {
        return url;
    }

    public String getTitle() {
        return title;
    }

    /**
     * @return raw favicon bytes (PNG/ICO), or {@code null} when the engine did
     *         not deliver one. Note: the pinned JCEF fork (146.0.10) has no
     *         favicon callback, so this stays {@code null} until a fetch-based
     *         source is added — the tab strip renders a placeholder meanwhile.
     */
    public byte[] getFavicon() {
        return favicon;
    }

    public boolean isLoading() {
        return loading;
    }

    /** @return 0..100 (0/100 from the load events; the fork has no progress callback). */
    public int getProgress() {
        return progress;
    }

    public SslStatus getSslStatus() {
        return sslStatus;
    }

    /** N3: the back button's enabled state (from the CEF navigation stack). */
    public boolean canGoBack() {
        return canGoBack;
    }

    /** N3: the forward button's enabled state (from the CEF navigation stack). */
    public boolean canGoForward() {
        return canGoForward;
    }

    public boolean isPinned() {
        return pinned;
    }

    public boolean isPrivateMode() {
        return privateMode;
    }

    public boolean isDiscarded() {
        return discarded;
    }

    /** S5: the page loaded at least one insecure (http) subresource. */
    public boolean isMixedContent() {
        return mixedContent;
    }

    /** T10/D13: the tab's last activity timestamp (epoch millis). */
    public long getLastActiveAt() {
        return lastActiveAt;
    }

    public int getZoomLevel() {
        return zoomLevel;
    }

    // ------------------------------------------------------------------
    // Mutators — TabController only (it dispatches the TabEvents).
    // ------------------------------------------------------------------

    void setTitle(String title) {
        this.title = title;
    }

    void setUrl(String url) {
        this.url = url;
        this.sslStatus = SslStatus.forUrl(url);
    }

    void setLoading(boolean loading) {
        this.loading = loading;
    }

    void setProgress(int progress) {
        this.progress = Math.max(0, Math.min(100, progress));
    }

    void setFavicon(byte[] favicon) {
        this.favicon = favicon;
    }

    void setSslStatus(SslStatus sslStatus) {
        this.sslStatus = sslStatus;
    }

    void setCanGoBack(boolean canGoBack) {
        this.canGoBack = canGoBack;
    }

    void setCanGoForward(boolean canGoForward) {
        this.canGoForward = canGoForward;
    }

    /** S5: flags the page as mixed-content (idempotent). */
    void markMixedContent() {
        this.mixedContent = true;
    }

    /** S5: a new page committed — the mixed-content state is cleared. */
    void resetMixedContent() {
        this.mixedContent = false;
    }

    /** T10/D13: the discard flag (the engine recreates the browser on demand). */
    void setDiscarded(boolean discarded) {
        this.discarded = discarded;
    }

    /** T10/D13: stamps the tab as active now (activation). */
    void touchActive() {
        this.lastActiveAt = System.currentTimeMillis();
    }

    /** Test hook: backdates the last-activity stamp (same package only). */
    void setLastActiveAtForTest(long ts) {
        this.lastActiveAt = ts;
    }
}
