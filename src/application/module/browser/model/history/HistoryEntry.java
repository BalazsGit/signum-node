package application.module.browser.model.history;

/**
 * One persisted visit of the browsing history (plan F3, Appendix C).
 * <p>
 * Immutable value object — the fields mirror the {@code history} table of
 * {@code conf/browser/history.db} (schema v1).
 */
public final class HistoryEntry {

    private final long id;
    private final String url;
    private final String title;
    private final long visitTs;
    private final String referrer;
    private final int visits;

    public HistoryEntry(long id, String url, String title, long visitTs, String referrer, int visits) {
        this.id = id;
        this.url = url;
        this.title = title;
        this.visitTs = visitTs;
        this.referrer = referrer;
        this.visits = visits;
    }

    /** @return the row id (history table primary key) */
    public long getId() {
        return id;
    }

    /** @return the visited URL (never null) */
    public String getUrl() {
        return url;
    }

    /** @return the page title at the visit, or {@code null} when unknown */
    public String getTitle() {
        return title;
    }

    /** @return the visit timestamp (epoch millis, never {@code <= 0}) */
    public long getVisitTs() {
        return visitTs;
    }

    /** @return the referring page URL, or {@code null} when the visit had none */
    public String getReferrer() {
        return referrer;
    }

    /** @return the number of coalesced visits on this row (H1, at least 1) */
    public int getVisits() {
        return visits;
    }
}