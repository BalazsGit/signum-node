package application.module.browser.model.download;

/**
 * One download record (plan F5, D1–D3) — a pure data bean shared by the
 * {@link DownloadManager} and the download shelf. Persisted (recent items)
 * into {@code conf/browser/downloads.json}.
 * <p>
 * The file itself is written by Chromium (JCEF downloads are native, the
 * browser module only controls the target path via its own
 * {@code CefDownloadHandler}); this bean tracks the state the CEF
 * {@code onDownloadUpdated} callbacks report.
 */
public final class DownloadItem {

    /** Download lifecycle as exposed by the pinned JCEF (booleans mapped in {@link DownloadManager}). */
    public enum State {
        /** Registered, no bytes yet (CEF queued / not started). */
        PENDING,
        /** Transfer in progress. */
        IN_PROGRESS,
        /** Finished successfully; the file is at {@link #targetPath}. */
        COMPLETE,
        /** Cancelled by the user (D2). */
        CANCELED,
        /** Terminal without a complete or canceled CEF flag (error, page closed). */
        FAILED;

        public boolean isTerminal() {
            return this == COMPLETE || this == CANCELED || this == FAILED;
        }
    }

    private String id;
    /** The CEF download-item id (diagnostics; the manager maps it per tab handler). */
    private int cefId;
    private String url;
    /** Sanitized file name without the collision suffix. */
    private String suggestedName;
    /** The resolved, collision-free absolute target path (D1). */
    private String targetPath;
    private long receivedBytes;
    /** 0 when the server did not announce a size. */
    private long totalBytes;
    private int percentComplete;
    /** Bytes/second at the last update. */
    private long speedBps;
    private State state = State.PENDING;
    private long startedAt;
    /** 0 while not terminal. */
    private long finishedAt;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public int getCefId() {
        return cefId;
    }

    public void setCefId(int cefId) {
        this.cefId = cefId;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getSuggestedName() {
        return suggestedName;
    }

    public void setSuggestedName(String suggestedName) {
        this.suggestedName = suggestedName;
    }

    public String getTargetPath() {
        return targetPath;
    }

    public void setTargetPath(String targetPath) {
        this.targetPath = targetPath;
    }

    public long getReceivedBytes() {
        return receivedBytes;
    }

    public void setReceivedBytes(long receivedBytes) {
        this.receivedBytes = receivedBytes;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public void setTotalBytes(long totalBytes) {
        this.totalBytes = totalBytes;
    }

    public int getPercentComplete() {
        return percentComplete;
    }

    public void setPercentComplete(int percentComplete) {
        this.percentComplete = percentComplete;
    }

    public long getSpeedBps() {
        return speedBps;
    }

    public void setSpeedBps(long speedBps) {
        this.speedBps = speedBps;
    }

    public State getState() {
        return state;
    }

    public void setState(State state) {
        this.state = state == null ? State.PENDING : state;
    }

    public long getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(long startedAt) {
        this.startedAt = startedAt;
    }

    public long getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(long finishedAt) {
        this.finishedAt = finishedAt;
    }
}