package application.module.browser.model.history;

/**
 * One "top site" of the New Tab page (plan F9, H5): a site (host) aggregated
 * over its history rows — the most recently visited page of the site and the
 * total visit count. Pure data (no Swing, no CEF).
 */
public final class TopSite {

    private final String host;
    private final String url;
    private final String title;
    private final int totalVisits;
    private final long latestVisitTs;

    public TopSite(String host, String url, String title, int totalVisits, long latestVisitTs) {
        this.host = host;
        this.url = url;
        this.title = title;
        this.totalVisits = totalVisits;
        this.latestVisitTs = latestVisitTs;
    }

    /** @return the site host (lowercase, with a non-default port when present). */
    public String getHost() {
        return host;
    }

    /** @return the most recently visited page of the site. */
    public String getUrl() {
        return url;
    }

    /** @return the title of that page (may be blank). */
    public String getTitle() {
        return title;
    }

    /** @return the summed visit count of the site. */
    public int getTotalVisits() {
        return totalVisits;
    }

    /** @return the latest visit timestamp in epoch millis. */
    public long getLatestVisitTs() {
        return latestVisitTs;
    }
}