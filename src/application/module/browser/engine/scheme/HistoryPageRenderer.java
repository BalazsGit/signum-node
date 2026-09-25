package application.module.browser.engine.scheme;

import application.module.browser.model.history.HistoryEntry;
import application.module.browser.model.history.HistoryStore;
import com.google.gson.Gson;
import application.utils.i18n.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Body producer of the {@code signum://history} page (plan F3, H2/H3).
 * <p>
 * The static template ({@code resources/html/browser/history.html}) carries
 * no user-visible text (D16): every UI string comes from the i18n bundle and
 * the entries from the live {@link HistoryStore}, both injected as a single
 * JSON data island that the page's script renders. The island is script-safe
 * (S9): every {@code <} in the JSON is escaped to {@code \u003c}, so no page
 * title or URL can break out of the {@code <script>} tag.
 * <p>
 * URL space:
 * <ul>
 *   <li>{@code signum://history[?q=..&range=all|today|7d|30d]} — the listing
 *       (H2: day grouping in the page, search + time filter here);</li>
 *   <li>{@code signum://history/delete?id=..} — deletes one row (H3), then
 *       re-renders the listing (the same filters are kept from the query);</li>
 *   <li>{@code signum://history/clear} — deletes everything after the page's
 *       confirmation dialog (H3), then re-renders the listing.</li>
 * </ul>
 * Actions are plain navigations (the F2 cert-continue pattern) — no XHR on
 * the custom scheme. No CEF imports — fully unit-testable.
 */
public final class HistoryPageRenderer implements InternalPage.PageRenderer {

    /** The page name in the {@link InternalPage} registry. */
    public static final String PAGE = "history";
    /** The page URL (Ctrl+H, H4). */
    public static final String PAGE_URL = BrowserSchemeHandler.SCHEME_PREFIX + PAGE;

    private static final Logger logger = LoggerFactory.getLogger(HistoryPageRenderer.class);

    private static final String TEMPLATE = "/html/browser/history.html";
    /** Placeholder of the static template, replaced by the escaped JSON island. */
    private static final String DATA_PLACEHOLDER = "__SIGNUM_DATA__";
    /** The listing cap (the page groups the result client-side). */
    private static final int PAGE_LIMIT = 2000;
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;

    private final HistoryStore store;
    private final Gson gson = new Gson();

    public HistoryPageRenderer(HistoryStore store) {
        this.store = store;
    }

    @Override
    public byte[] render(String url, Map<String, String> query) {
        String path = pathOf(url);
        switch (path) {
            case "history":
                return renderList(query);
            case "history/delete": {
                // H3: single-entry deletion, requested by the page's delete buttons.
                String idParam = query.get("id");
                if (idParam != null) {
                    try {
                        store.delete(Long.parseLong(idParam));
                    } catch (NumberFormatException ignored) {
                        // malformed id — nothing to delete, the listing just re-renders
                    }
                }
                return renderList(query);
            }
            case "history/clear":
                // H3: the page asks for confirmation before this navigation.
                store.clear();
                return renderList(query);
            default:
                // Unknown action of a dynamic page → the 404 page (plan D6).
                return null;
        }
    }

    // ------------------------------------------------------------------
    // Listing (H2)
    // ------------------------------------------------------------------

    private byte[] renderList(Map<String, String> query) {
        String q = param(query, "q");
        String range = param(query, "range");
        long now = System.currentTimeMillis();
        long fromTs = fromTsOf(range, now);

        List<HistoryEntry> entries = store.search(q, fromTs, Long.MAX_VALUE, PAGE_LIMIT);

        PageData data = new PageData();
        data.now = now;
        data.q = q;
        data.range = range;
        data.total = entries.size();
        for (HistoryEntry entry : entries) {
            data.entries.add(new EntryData(entry));
        }
        data.strings.put("title", I18n.get("browser.history.title"));
        data.strings.put("searchPlaceholder", I18n.get("browser.history.search.placeholder"));
        data.strings.put("rangeAll", I18n.get("browser.history.range.all"));
        data.strings.put("rangeToday", I18n.get("browser.history.range.today"));
        data.strings.put("range7d", I18n.get("browser.history.range.7d"));
        data.strings.put("range30d", I18n.get("browser.history.range.30d"));
        data.strings.put("clear", I18n.get("browser.history.clear"));
        data.strings.put("clearConfirm", I18n.get("browser.history.clear.confirm"));
        data.strings.put("empty", I18n.get("browser.history.empty"));
        data.strings.put("today", I18n.get("browser.history.day.today"));
        data.strings.put("yesterday", I18n.get("browser.history.day.yesterday"));
        data.strings.put("delete", I18n.get("browser.history.delete.tooltip"));
        data.strings.put("visits", I18n.get("browser.history.visits"));

        String template = readTemplate();
        if (template == null) {
            return null;
        }
        String json = escapeForScript(gson.toJson(data));
        return template.replace(DATA_PLACEHOLDER, json).getBytes(StandardCharsets.UTF_8);
    }

    /** H2 time filter: the lower bound of the range (0 = all time). */
    static long fromTsOf(String range, long now) {
        if ("today".equals(range)) {
            return LocalDate.now().atStartOfDay(ZoneId.systemDefault())
                    .toInstant().toEpochMilli();
        }
        if ("7d".equals(range)) {
            return now - 7L * DAY_MS;
        }
        if ("30d".equals(range)) {
            return now - 30L * DAY_MS;
        }
        return 0L; // "all" / unknown value → unbounded
    }

    private static String pathOf(String url) {
        if (url == null) {
            return "";
        }
        String path = url;
        if (path.startsWith(BrowserSchemeHandler.SCHEME_PREFIX)) {
            path = path.substring(BrowserSchemeHandler.SCHEME_PREFIX.length());
        }
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int hash = path.indexOf('#');
        if (hash >= 0) {
            path = path.substring(0, hash);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static String param(Map<String, String> query, String name) {
        String value = query.get(name);
        return value == null ? "" : value.trim();
    }

    /**
     * S9: makes the JSON safe for inlining into a {@code <script>} tag —
     * every opening angle bracket becomes the valid JSON escape {@code \u003c},
     * so neither page titles nor URLs can terminate the tag or start a script.
     */
    static String escapeForScript(String json) {
        return json.replace("<", "\\u003c");
    }

    private String readTemplate() {
        try (InputStream in = HistoryPageRenderer.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                logger.error("History page template missing from the classpath: {}", TEMPLATE);
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("Could not read the history page template {}", TEMPLATE, e);
            return null;
        }
    }

    /** The injected payload (gson-serialized public fields). */
    static final class PageData {
        int v = 1;
        long now;
        String q = "";
        String range = "all";
        int total;
        List<EntryData> entries = new ArrayList<>();
        Map<String, String> strings = new LinkedHashMap<>();
    }

    static final class EntryData {
        long id;
        String url;
        String title;
        long ts;
        int visits;

        EntryData(HistoryEntry entry) {
            this.id = entry.getId();
            this.url = entry.getUrl();
            this.title = entry.getTitle() == null ? "" : entry.getTitle();
            this.ts = entry.getVisitTs();
            this.visits = entry.getVisits();
        }
    }
}