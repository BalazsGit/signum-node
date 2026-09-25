package application.module.browser.engine.scheme;

import application.module.browser.config.BrowserSettings;
import application.module.browser.model.history.HistoryStore;
import application.module.browser.model.history.TopSite;
import application.utils.i18n.I18n;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Body producer of the {@code signum://newtab} page (plan F9, H5): the
 * static NTP template ({@code resources/html/browser/newtab.html}) plus the
 * live "Frequently visited" top sites (the {@code HistoryStore} aggregate)
 * and the active search engine — one S9-escaped JSON data island, the same
 * mechanism as the settings and history pages.
 * <p>
 * No CEF imports — fully unit-testable (history + settings are injected).
 */
public final class NewTabPageRenderer implements InternalPage.PageRenderer {

    /** The page name in the {@link InternalPage} registry. */
    public static final String PAGE = "newtab";
    /** The NTP URL (a fresh tab's home, plan D6). */
    public static final String PAGE_URL = BrowserSchemeHandler.SCHEME_PREFIX + PAGE;

    /** How many top-site cards the NTP grid holds. */
    public static final int TOP_SITES = 8;

    private static final Logger logger = LoggerFactory.getLogger(NewTabPageRenderer.class);

    private static final String TEMPLATE = "/html/browser/newtab.html";
    /** Placeholder of the static template, replaced by the escaped JSON island. */
    private static final String DATA_PLACEHOLDER = "__SIGNUM_DATA__";

    private final HistoryStore history;
    private final Supplier<BrowserSettings> settings;
    private final Gson gson = new Gson();

    public NewTabPageRenderer(HistoryStore history, Supplier<BrowserSettings> settings) {
        this.history = history;
        this.settings = settings;
    }

    @Override
    public byte[] render(String url, Map<String, String> query) {
        PageData data = new PageData();
        data.now = System.currentTimeMillis();
        data.searchTemplate = settings.get().getSearchEngineTemplate();
        try {
            for (TopSite site : history.topSites(TOP_SITES)) {
                data.topSites.add(new SiteData(site));
            }
        } catch (RuntimeException e) {
            // D17: the NTP must render even when the history is broken
            logger.warn("Could not load the top sites for the NTP: {}", e.toString());
        }
        Map<String, String> strings = new LinkedHashMap<>();
        strings.put("frequent", I18n.get("browser.newtab.frequent"));
        data.strings = strings;

        String template = readTemplate();
        if (template == null) {
            return null;
        }
        String json = escapeForScript(gson.toJson(data));
        return template.replace(DATA_PLACEHOLDER, json).getBytes(StandardCharsets.UTF_8);
    }

    /** S9: makes the JSON safe for inlining into a {@code <script>} tag. */
    static String escapeForScript(String json) {
        return json.replace("<", "\\u003c");
    }

    private String readTemplate() {
        try (InputStream in = NewTabPageRenderer.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                logger.error("New Tab page template missing from the classpath: {}", TEMPLATE);
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("Could not read the New Tab page template {}", TEMPLATE, e);
            return null;
        }
    }

    /** The injected payload (gson-serialized public fields). */
    static final class PageData {
        int v = 1;
        long now;
        List<SiteData> topSites = new ArrayList<>();
        String searchTemplate;
        Map<String, String> strings = new LinkedHashMap<>();
    }

    static final class SiteData {
        String host;
        String title;
        String url;
        int visits;

        SiteData(TopSite site) {
            this.host = site.getHost();
            this.title = (site.getTitle() == null || site.getTitle().isBlank())
                    ? site.getHost() : site.getTitle();
            this.url = site.getUrl();
            this.visits = site.getTotalVisits();
        }
    }
}