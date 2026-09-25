package application.module.browser.model.history;

import application.module.browser.util.UrlUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * The browsing history store (plan F3, H1): SQLite database at
 * {@code conf/browser/history.db} (D8), schema v1 (Appendix C).
 * <p>
 * <b>Batch writer:</b> {@link #record} never touches the database directly —
 * visits land in an in-memory queue that a single daemon thread flushes
 * periodically (and immediately once the queue grows past the threshold).
 * {@link #flush()} and {@link #close()} are synchronous; a reload within the
 * same millisecond coalesces into the existing row's {@code visits} counter
 * (H1: no duplicate garbage, the "no identical (url, visit_ts) pair"
 * invariant of plan §8).
 * <p>
 * <b>Never fatal (D17):</b> a missing parent directory is created, an
 * unopenable or corrupt database degrades the store to a no-op (all calls
 * succeed, queries return empty) so browsing is never broken by the history
 * subsystem. Pure Java — no Swing, no CEF — fully unit-testable.
 */
public final class HistoryStore implements AutoCloseable {

    /** The database schema version written into {@code schema_version} (v1). */
    public static final int SCHEMA_VERSION = 1;

    /** Default cap of {@link #search} when the caller asks for none. */
    public static final int DEFAULT_LIMIT = 1000;

    /** Hard cap of {@link #search} (protects the GUI from pathological asks). */
    public static final int MAX_LIMIT = 5000;

    private static final Logger logger = LoggerFactory.getLogger(HistoryStore.class);

    /** Background flush period of the batch writer. */
    static final int FLUSH_INTERVAL_MS = 1000;
    /** Queue size that triggers an immediate synchronous flush. */
    private static final int FLUSH_THRESHOLD = 64;

    /** One queued visit (title trimmed, referrer normalized to null-or-value). */
    private static final class Visit {
        final String url;
        final String title;
        final long visitTs;
        final String referrer;

        Visit(String url, String title, long visitTs, String referrer) {
            this.url = url;
            this.title = title;
            this.visitTs = visitTs;
            this.referrer = referrer;
        }
    }

    private final Path dbFile;
    private final Object dbLock = new Object();
    private final Object queueLock = new Object();
    private Connection connection; // guarded by dbLock
    private final Deque<Visit> pending = new ArrayDeque<>(); // guarded by queueLock
    private final ScheduledExecutorService flusher;
    private volatile boolean degraded; // open failed: every operation is a no-op
    private volatile boolean closed;

    /**
     * Opens (and when needed creates) the history database.
     *
     * @param dbFile the database file, conventionally {@code conf/browser/history.db}
     */
    public HistoryStore(Path dbFile) {
        this.dbFile = dbFile;
        try {
            Path parent = dbFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            synchronized (dbLock) {
                this.connection = DriverManager.getConnection(jdbcUrl());
                createSchema();
            }
        } catch (Exception e) {
            // D17: a broken database must never break browsing.
            logger.error("Could not open the history database at {}; the store degrades to no-op",
                    dbFile, e);
            this.degraded = true;
        }
        this.flusher = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "browser-history-flusher");
            t.setDaemon(true);
            return t;
        });
        if (!degraded) {
            flusher.scheduleWithFixedDelay(this::flushIfPending,
                    FLUSH_INTERVAL_MS, FLUSH_INTERVAL_MS, TimeUnit.MILLISECONDS);
        }
    }

    private String jdbcUrl() {
        return "jdbc:sqlite:" + dbFile.toAbsolutePath();
    }

    private void createSchema() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS schema_version (v INTEGER NOT NULL)");
            statement.executeUpdate(
                    "CREATE TABLE IF NOT EXISTS history ("
                            + "id INTEGER PRIMARY KEY, "
                            + "url TEXT NOT NULL, "
                            + "title TEXT, "
                            + "visit_ts INTEGER NOT NULL, "
                            + "referrer TEXT, "
                            + "visits INTEGER NOT NULL DEFAULT 1)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_history_ts ON history(visit_ts)");
            statement.executeUpdate(
                    "CREATE INDEX IF NOT EXISTS idx_history_url ON history(url)");
        }
        try (PreparedStatement version = connection.prepareStatement(
                "SELECT COUNT(*) FROM schema_version");
             PreparedStatement insert = connection.prepareStatement(
                     "INSERT INTO schema_version (v) VALUES (?)")) {
            try (ResultSet rs = version.executeQuery()) {
                if (!rs.next()) {
                    insert.setInt(1, SCHEMA_VERSION);
                    insert.executeUpdate();
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Capture (H1)
    // ------------------------------------------------------------------

    /**
     * @param url the candidate URL (may be null)
     * @return {@code true} when the visit is recorded in the history:
     *         http/https and {@code signum://} pages, except the New Tab page
     *         (plan F3: NTP excluded) and the internal plumbing URLs
     *         (certificate-continue, history delete/clear actions)
     */
    public static boolean isRecordable(String url) {
        if (url == null) {
            return false;
        }
        String trimmed = url.trim();
        if (trimmed.isEmpty()) {
            return false;
        }
        String scheme = UrlUtils.scheme(trimmed);
        if (!"http".equals(scheme) && !"https".equals(scheme) && !"signum".equals(scheme)) {
            return false;
        }
        if ("signum".equals(scheme)) {
            String path = trimmed.substring("signum://".length()).toLowerCase();
            int cut = path.indexOf('?');
            if (cut >= 0) {
                path = path.substring(0, cut);
            }
            cut = path.indexOf('#');
            if (cut >= 0) {
                path = path.substring(0, cut);
            }
            if ("newtab".equals(path)
                    || path.startsWith("cert-continue")
                    || path.startsWith("history/delete")
                    || path.startsWith("history/clear")) {
                return false;
            }
        }
        return true;
    }

    /**
     * Queues a visit for the batch writer. Never throws (D17); invalid or
     * non-recordable URLs are dropped.
     *
     * @param url      the visited URL (see {@link #isRecordable})
     * @param title    the page title (may be null or blank)
     * @param visitTs  the visit timestamp in epoch millis (must be positive)
     * @param referrer the referring page URL (may be null)
     */
    public void record(String url, String title, long visitTs, String referrer) {
        if (closed || degraded || !isRecordable(url) || visitTs <= 0) {
            return;
        }
        String safeTitle = title == null ? "" : title.trim();
        String safeReferrer = referrer == null || referrer.isBlank() ? null : referrer.trim();
        boolean flushNow = false;
        synchronized (queueLock) {
            pending.addLast(new Visit(url.trim(), safeTitle, visitTs, safeReferrer));
            flushNow = pending.size() >= FLUSH_THRESHOLD;
        }
        if (flushNow) {
            flush();
        }
    }

    // ------------------------------------------------------------------
    // Batch writer
    // ------------------------------------------------------------------

    /** Synchronously writes every pending visit (no-op when nothing is queued). */
    public void flush() {
        if (closed || degraded) {
            return;
        }
        writePending();
    }

    /** Background flusher entry point (daemon thread). */
    private void flushIfPending() {
        synchronized (queueLock) {
            if (pending.isEmpty()) {
                return;
            }
        }
        flush();
    }

    /**
     * Writes the queued visits; unlike {@link #flush} it must work from
     * {@link #close()} (where {@code closed} is already true) so a shutdown
     * never loses the last visits.
     */
    private void writePending() {
        if (degraded) {
            return;
        }
        List<Visit> batch;
        synchronized (queueLock) {
            if (pending.isEmpty()) {
                return;
            }
            batch = new ArrayList<>(pending);
            pending.clear();
        }
        synchronized (dbLock) {
            if (connection == null) {
                return;
            }
            try {
                boolean previousAutoCommit = connection.getAutoCommit();
                try {
                    connection.setAutoCommit(false);
                    for (Visit visit : batch) {
                        upsert(visit);
                    }
                    connection.commit();
                } catch (SQLException e) {
                    connection.rollback();
                    throw e;
                } finally {
                    try {
                        connection.setAutoCommit(previousAutoCommit);
                    } catch (SQLException ignored) {
                        // the connection is about to be closed anyway
                    }
                }
            } catch (SQLException e) {
                logger.error("History flush failed ({} pending visit(s) lost)", batch.size(), e);
            }
        }
    }

    // ------------------------------------------------------------------
    // Queries (H2)
    // ------------------------------------------------------------------

    /**
     * H1 deduplication: a visit on the same url within the same millisecond
     * (a reload) increments the existing row instead of adding a row.
     */
    private void upsert(Visit visit) throws SQLException {
        long existingId = -1;
        String existingTitle = null;
        try (PreparedStatement find = connection.prepareStatement(
                "SELECT id, title FROM history WHERE url = ? AND visit_ts = ? LIMIT 1")) {
            find.setString(1, visit.url);
            find.setLong(2, visit.visitTs);
            try (ResultSet rs = find.executeQuery()) {
                if (rs.next()) {
                    existingId = rs.getLong(1);
                    existingTitle = rs.getString(2);
                }
            }
        }
        if (existingId >= 0) {
            // Backfill the title when the original visit had none.
            boolean backfill = (existingTitle == null || existingTitle.isEmpty())
                    && !visit.title.isEmpty();
            try (PreparedStatement update = connection.prepareStatement(
                    "UPDATE history SET visits = visits + 1,"
                            + " title = CASE WHEN ? THEN ? ELSE title END"
                            + " WHERE id = ?")) {
                update.setBoolean(1, backfill);
                update.setString(2, visit.title);
                update.setLong(3, existingId);
                update.executeUpdate();
            }
        } else {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO history (url, title, visit_ts, referrer, visits)"
                            + " VALUES (?, ?, ?, ?, 1)")) {
                insert.setString(1, visit.url);
                insert.setString(2, visit.title);
                insert.setLong(3, visit.visitTs);
                insert.setString(4, visit.referrer);
                insert.executeUpdate();
            }
        }
    }

    // ------------------------------------------------------------------
    // Queries (H2)
    // ------------------------------------------------------------------

    /**
     * Searches the history.
     *
     * @param query  case-insensitive substring matched against url and title;
     *               LIKE wildcards in the input are treated as literals.
     *               Blank or null matches everything.
     * @param fromTs inclusive lower bound of the visit timestamp (0 = unbounded)
     * @param toTs   inclusive upper bound ({@link Long#MAX_VALUE} = unbounded)
     * @param limit  maximum number of rows ({@code <= 0} = default, capped at
     *               {@link #MAX_LIMIT})
     * @return the matching entries, newest first, never null
     */
    public List<HistoryEntry> search(String query, long fromTs, long toTs, int limit) {
        List<HistoryEntry> result = new ArrayList<>();
        if (closed || degraded) {
            return result;
        }
        String q = query == null ? "" : query.trim();
        int effectiveLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);
        boolean hasQuery = !q.isEmpty();
        boolean hasFrom = fromTs > 0;
        boolean hasTo = toTs != Long.MAX_VALUE;

        StringBuilder sql = new StringBuilder(
                "SELECT id, url, title, visit_ts, referrer, visits FROM history");
        List<Object> params = new ArrayList<>();
        if (hasQuery || hasFrom || hasTo) {
            sql.append(" WHERE ");
            if (hasQuery) {
                String like = "%" + escapeLike(q) + "%";
                sql.append("(url LIKE ? ESCAPE '\\'")
                        .append(" OR COALESCE(title, '') LIKE ? ESCAPE '\\')");
                params.add(like);
                params.add(like);
            }
            if (hasFrom) {
                sql.append(hasQuery ? " AND " : "");
                sql.append("visit_ts >= ?");
                params.add(fromTs);
            }
            if (hasTo) {
                sql.append((hasQuery || hasFrom) ? " AND " : "");
                sql.append("visit_ts <= ?");
                params.add(toTs);
            }
        }
        sql.append(" ORDER BY visit_ts DESC, id DESC LIMIT ?");
        params.add(effectiveLimit);

        synchronized (dbLock) {
            if (connection == null) {
                return result;
            }
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    Object value = params.get(i);
                    if (value instanceof Integer intParam) {
                        statement.setInt(i + 1, intParam);
                    } else {
                        statement.setObject(i + 1, value);
                    }
                }
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        result.add(new HistoryEntry(rs.getLong(1), rs.getString(2),
                                rs.getString(3), rs.getLong(4), rs.getString(5), rs.getInt(6)));
                    }
                }
            } catch (SQLException e) {
                logger.error("History search failed", e);
            }
        }
        return result;
    }

    /** @return the total number of history rows. */
    public long count() {
        if (closed || degraded) {
            return 0L;
        }
        synchronized (dbLock) {
            if (connection == null) {
                return 0L;
            }
            try (Statement statement = connection.createStatement();
                 ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM history")) {
                return rs.next() ? rs.getLong(1) : 0L;
            } catch (SQLException e) {
                logger.error("History count failed", e);
                return 0L;
            }
        }
    }

    /**
     * H6: searches the history with <em>ranking</em> instead of plain
     * recency (plan F9): the score is the visit count weighted by a
     * freshness decay — {@code visits * 10000 / (ageHours + 10)}. A site
     * visited 10 times a day long outlives a site visited once an hour ago;
     * a fresh single visit still outranks a stale heavy one.
     *
     * @param query  case-insensitive substring (same semantics as
     *               {@link #search})
     * @param nowMs  the reference "now" (epoch millis; tests pass a fixed
     *               value)
     * @param limit  maximum number of rows ({@code <= 0} = default, capped at
     *               {@link #MAX_LIMIT})
     * @return the matching entries, best score first, never null
     */
    public List<HistoryEntry> searchRanked(String query, long nowMs, int limit) {
        List<HistoryEntry> result = new ArrayList<>();
        if (closed || degraded || nowMs <= 0) {
            return result;
        }
        String q = query == null ? "" : query.trim();
        int effectiveLimit = limit <= 0 ? DEFAULT_LIMIT : Math.min(limit, MAX_LIMIT);

        StringBuilder sql = new StringBuilder(
                "SELECT id, url, title, visit_ts, referrer, visits FROM history");
        List<Object> params = new ArrayList<>();
        if (!q.isEmpty()) {
            String like = "%" + escapeLike(q) + "%";
            sql.append(" WHERE (url LIKE ? ESCAPE '\\'")
                    .append(" OR COALESCE(title, '') LIKE ? ESCAPE '\\')");
            params.add(like);
            params.add(like);
        }
        // H6: score = visits * 10000 / (ageHours + 10); MAX() guards against
        // clock skew (future timestamps would make the denominator negative).
        sql.append(" ORDER BY visits * 10000.0 / ((MAX(0, ? - visit_ts)) / 3600000.0 + 10.0) DESC,")
                .append(" visit_ts DESC LIMIT ?");
        params.add(nowMs);
        params.add(effectiveLimit);

        synchronized (dbLock) {
            if (connection == null) {
                return result;
            }
            try (PreparedStatement statement = connection.prepareStatement(sql.toString())) {
                for (int i = 0; i < params.size(); i++) {
                    Object value = params.get(i);
                    if (value instanceof Integer intParam) {
                        statement.setInt(i + 1, intParam);
                    } else {
                        statement.setObject(i + 1, value);
                    }
                }
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) {
                        result.add(new HistoryEntry(rs.getLong(1), rs.getString(2),
                                rs.getString(3), rs.getLong(4), rs.getString(5), rs.getInt(6)));
                    }
                }
            } catch (SQLException e) {
                logger.error("Ranked history search failed", e);
            }
        }
        return result;
    }

    /**
     * H5: the most visited sites for the New Tab page: the (scanned) history
     * is aggregated <em>per site</em> (host, see {@link #hostOf}) in Java —
     * no SQL dialect tricks. Only http/https pages count (internal pages are
     * pages of the application itself), and only the most recent page of a
     * site is reported.
     *
     * @param limit maximum number of sites ({@code <= 0} = 8, the NTP grid)
     * @return the top sites, most visits first (ties: most recent first)
     */
    public List<TopSite> topSites(int limit) {
        int effectiveLimit = limit <= 0 ? 8 : Math.min(limit, 24);
        Map<String, TopSiteAgg> byHost = new LinkedHashMap<>();
        for (HistoryEntry entry : search("", 0L, Long.MAX_VALUE, MAX_LIMIT)) {
            String host = hostOf(entry.getUrl());
            if (host.isEmpty()) {
                continue; // internal and other pages are not web sites
            }
            TopSiteAgg agg = byHost.get(host);
            if (agg == null) {
                agg = new TopSiteAgg();
                byHost.put(host, agg);
            }
            agg.totalVisits += Math.max(1, entry.getVisits());
            if (entry.getVisitTs() >= agg.latestTs) {
                // the rows arrive newest first; >= keeps the newest seen
                agg.latestTs = entry.getVisitTs();
                agg.url = entry.getUrl();
                agg.title = entry.getTitle();
            }
        }
        List<TopSite> sites = new ArrayList<>(byHost.size());
        for (Map.Entry<String, TopSiteAgg> e : byHost.entrySet()) {
            TopSiteAgg agg = e.getValue();
            sites.add(new TopSite(e.getKey(), agg.url, agg.title,
                    agg.totalVisits, agg.latestTs));
        }
        sites.sort((a, b) -> {
            int byVisits = Integer.compare(b.getTotalVisits(), a.getTotalVisits());
            return byVisits != 0 ? byVisits
                    : Long.compare(b.getLatestVisitTs(), a.getLatestVisitTs());
        });
        return sites.size() > effectiveLimit
                ? new ArrayList<>(sites.subList(0, effectiveLimit))
                : sites;
    }

    /**
     * The site (host) of a web URL: the authority part after the scheme,
     * lowercased, without the path/query/fragment and without a default port
     * ({@code :80} on http, {@code :443} on https). Pure — unit-testable.
     *
     * @return the host, or the empty string for anything that is not a
     *         plain http/https URL
     */
    public static String hostOf(String url) {
        if (url == null) {
            return "";
        }
        String scheme = UrlUtils.scheme(url);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            return "";
        }
        int schemeEnd = url.indexOf("://");
        if (schemeEnd < 0) {
            return "";
        }
        String rest = url.substring(schemeEnd + 3);
        int cut = rest.length();
        for (char c : new char[]{'/', '?', '#'}) {
            int at = rest.indexOf(c);
            if (at >= 0 && at < cut) {
                cut = at;
            }
        }
        String host = rest.substring(0, cut).toLowerCase();
        int at = host.lastIndexOf('@'); // user:pass@host — keep the host
        if (at >= 0) {
            host = host.substring(at + 1);
        }
        if ("http".equals(scheme) && host.endsWith(":80")) {
            host = host.substring(0, host.length() - 3);
        } else if ("https".equals(scheme) && host.endsWith(":443")) {
            host = host.substring(0, host.length() - 4);
        }
        return host;
    }

    /** The mutable per-host accumulator of {@link #topSites}. */
    private static final class TopSiteAgg {
        int totalVisits;
        long latestTs;
        String url;
        String title;
    }

    // ------------------------------------------------------------------
    // Deletion (H3)
    // ------------------------------------------------------------------

    /**
     * Deletes a single history row.
     *
     * @param id the row id
     * @return the number of deleted rows (0 when the id was unknown)
     */
    public int delete(long id) {
        if (closed || degraded || id <= 0) {
            return 0;
        }
        synchronized (dbLock) {
            if (connection == null) {
                return 0;
            }
            try (PreparedStatement statement = connection.prepareStatement(
                    "DELETE FROM history WHERE id = ?")) {
                statement.setLong(1, id);
                return statement.executeUpdate();
            } catch (SQLException e) {
                logger.error("History delete failed (id={})", id, e);
                return 0;
            }
        }
    }

    /**
     * Deletes the entire history.
     *
     * @return the number of deleted rows
     */
    public int clear() {
        if (closed || degraded) {
            return 0;
        }
        synchronized (dbLock) {
            if (connection == null) {
                return 0;
            }
            try (Statement statement = connection.createStatement()) {
                return statement.executeUpdate("DELETE FROM history");
            } catch (SQLException e) {
                logger.error("History clear failed", e);
                return 0;
            }
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /** Flushes the pending visits and releases the database. Idempotent. */
    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        flusher.shutdownNow();
        writePending(); // drain the queue even though the store is now closed
        synchronized (dbLock) {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    logger.error("Could not close the history database at {}", dbFile, e);
                } finally {
                    connection = null;
                }
            }
        }
    }

    private static String escapeLike(String input) {
        StringBuilder out = new StringBuilder(input.length());
        for (int i = 0; i < input.length(); i++) {
            char c = input.charAt(i);
            if (c == '\\' || c == '%' || c == '_') {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }
}