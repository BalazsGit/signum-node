package application.module.browser.engine.scheme;

import application.module.browser.model.download.DownloadItem;
import application.module.browser.model.download.DownloadManager;
import com.google.gson.Gson;
import application.utils.i18n.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Body producer of the {@code signum://downloads} page (plan F6, D4 —
 * completed here with the settings/internal-page framework): the recent
 * downloads from the live {@link DownloadManager}, with single-entry
 * deletion and a (confirmed) bulk clear.
 * <p>
 * The same dynamic-page mechanism as the other pages: a static template
 * ({@code resources/html/browser/downloads.html}) with no UI text (D16),
 * the items + i18n strings in one S9-escaped JSON data island. Actions are
 * plain navigations (no XHR on the custom scheme):
 * <ul>
 *   <li>{@code signum://downloads} — the list (newest first, the manager
 *       keeps the recent list, capped at {@link DownloadManager#MAX_RECENT});</li>
 *   <li>{@code signum://downloads/remove?id=..} — deletes one entry from the
 *       list (the file stays on disk — same semantics as the shelf's ×);</li>
 *   <li>{@code signum://downloads/clear} — after the page's confirmation,
 *       removes every entry (files untouched).</li>
 * </ul>
 * The live transfer state (progress, cancel) stays on the shelf (D3); this
 * page is the durable recent list. No CEF imports — fully unit-testable.
 */
public final class DownloadsPageRenderer implements InternalPage.PageRenderer {

    /** The page name in the {@link InternalPage} registry. */
    public static final String PAGE = "downloads";
    /** The page URL (the shelf's header opens it). */
    public static final String PAGE_URL = BrowserSchemeHandler.SCHEME_PREFIX + PAGE;

    private static final Logger logger = LoggerFactory.getLogger(DownloadsPageRenderer.class);

    private static final String TEMPLATE = "/html/browser/downloads.html";
    /** Placeholder of the static template, replaced by the escaped JSON island. */
    private static final String DATA_PLACEHOLDER = "__SIGNUM_DATA__";

    private final DownloadManager manager;
    private final Gson gson = new Gson();

    public DownloadsPageRenderer(DownloadManager manager) {
        this.manager = manager;
    }

    @Override
    public byte[] render(String url, Map<String, String> query) {
        switch (pathOf(url)) {
            case "downloads":
                return renderList();
            case "downloads/remove": {
                // D4: single-entry deletion (the file is not touched).
                String id = query.get("id");
                if (id != null && !id.isBlank()) {
                    manager.remove(id.trim());
                }
                return renderList();
            }
            case "downloads/clear": {
                // D4: bulk clear, after the page's confirmation dialog.
                // Copy first: remove() mutates the live list.
                for (DownloadItem item : new ArrayList<>(manager.items())) {
                    manager.remove(item.getId());
                }
                return renderList();
            }
            default:
                return null; // unknown action → the 404 page
        }
    }
    private byte[] renderList() {
        PageData data = new PageData();
        data.now = System.currentTimeMillis();
        // The manager keeps oldest-first; the page lists newest-first.
        List<DownloadItem> items = manager.items();
        for (int i = items.size() - 1; i >= 0; i--) {
            data.entries.add(new EntryData(items.get(i)));
        }
        data.strings.put("title", I18n.get("browser.downloads.page.title"));
        data.strings.put("empty", I18n.get("browser.downloads.page.empty"));
        data.strings.put("clear", I18n.get("browser.downloads.page.clear"));
        data.strings.put("clearConfirm", I18n.get("browser.downloads.page.clear.confirm"));
        data.strings.put("remove", I18n.get("browser.downloads.remove"));
        data.strings.put("statePending", I18n.get("browser.downloads.state.pending"));
        data.strings.put("stateDone", I18n.get("browser.downloads.state.done"));
        data.strings.put("stateCanceled", I18n.get("browser.downloads.state.canceled"));
        data.strings.put("stateFailed", I18n.get("browser.downloads.state.failed"));
        data.strings.put("percent", I18n.get("browser.downloads.page.percent"));
        data.strings.put("size", I18n.get("browser.downloads.page.size"));
        data.strings.put("sizeUnknown", I18n.get("browser.downloads.page.size.unknown"));

        String template = readTemplate();
        if (template == null) {
            return null;
        }
        String json = escapeForScript(gson.toJson(data));
        return template.replace(DATA_PLACEHOLDER, json).getBytes(StandardCharsets.UTF_8);
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

    /**
     * S9: makes the JSON safe for inlining into a {@code <script>} tag
     * (every opening angle bracket becomes the {@code \u003c} JSON escape).
     */
    static String escapeForScript(String json) {
        return json.replace("<", "\\u003c");
    }

    private String readTemplate() {
        try (InputStream in = DownloadsPageRenderer.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                logger.error("Downloads page template missing from the classpath: {}", TEMPLATE);
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("Could not read the downloads page template {}", TEMPLATE, e);
            return null;
        }
    }

    /** The injected payload (gson-serialized public fields). */
    static final class PageData {
        int v = 1;
        long now;
        List<EntryData> entries = new ArrayList<>();
        Map<String, String> strings = new LinkedHashMap<>();
    }

    /** One row; the state is serialized as its name (the page's switch). */
    static final class EntryData {
        String id;
        String name;
        String path;
        String state;
        long received;
        long total;
        int percent;
        long startedAt;
        long finishedAt;

        EntryData(DownloadItem item) {
            this.id = item.getId();
            this.name = item.getSuggestedName();
            this.path = item.getTargetPath();
            this.state = item.getState() == null
                    ? DownloadItem.State.PENDING.name()
                    : item.getState().name();
            this.received = Math.max(0, item.getReceivedBytes());
            this.total = item.getTotalBytes();
            this.percent = Math.max(0, Math.min(100, item.getPercentComplete()));
            this.startedAt = item.getStartedAt();
            this.finishedAt = item.getFinishedAt();
        }
    }
}
