package application.module.browser.engine.scheme;

import application.module.browser.config.BrowserSettings;
import application.module.browser.config.BrowserSettingsRepository;
import com.google.gson.Gson;
import application.utils.i18n.I18n;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Body producer of the {@code signum://settings} page (plan F6, C1/C2/C5) —
 * the same dynamic-page mechanism as the history and bookmarks pages: a
 * static template ({@code resources/html/browser/settings.html}) carrying no
 * UI text (D16), with the live {@link BrowserSettings} and the i18n strings
 * injected as a single S9-escaped JSON data island.
 * <p>
 * URL space (actions are plain navigations — no XHR on the custom scheme):
 * <ul>
 *   <li>{@code signum://settings} — the form, pre-filled with the saved
 *       values (runtime state: a save here is live — the homepage and the
 *       download directory apply immediately, the startup behavior from the
 *       next start);</li>
 *   <li>{@code signum://settings/save?homepage=..&startup=..&urls=..&downloadsDir=..}
 *       — validates each value (invalid input keeps the old one, D17-style:
 *       garbage in, nothing lost), persists, re-renders;</li>
 *   <li>{@code signum://settings/pickDownloadsDir} — opens the native folder
 *       chooser (the GUI-supplied callback pumps to the EDT), saves the
 *       choice, re-renders.</li>
 * </ul>
 * C3/C4/C6/C7/C8 are the v1.1 wave: the fields exist in the schema, no UI.
 * No CEF imports — fully unit-testable (the chooser callback is injected).
 */
public final class SettingsPageRenderer implements InternalPage.PageRenderer {

    /** The page name in the {@link InternalPage} registry. */
    public static final String PAGE = "settings";
    /** The page URL (toolbar gear button). */
    public static final String PAGE_URL = BrowserSchemeHandler.SCHEME_PREFIX + PAGE;
    /** Cap of the startup URL list (a sanity bound, not a feature). */
    public static final int MAX_STARTUP_URLS = 20;

    private static final Logger logger = LoggerFactory.getLogger(SettingsPageRenderer.class);

    private static final String TEMPLATE = "/html/browser/settings.html";
    /** Placeholder of the static template, replaced by the escaped JSON island. */
    private static final String DATA_PLACEHOLDER = "__SIGNUM_DATA__";

    private final BrowserSettingsRepository repository;
    /** The OS default downloads directory (shown as the fallback, D1). */
    private final Path defaultDownloadsDir;
    /**
     * The folder chooser (C5 "Browse"): takes the currently configured
     * directory ("" = the default) and returns the chosen one, or null when
     * the user canceled. Implemented in the GUI layer (EDT). May be null —
     * then the pick action just re-renders.
     */
    private final Function<String, String> dirPicker;
    private final Gson gson = new Gson();

    public SettingsPageRenderer(BrowserSettingsRepository repository,
                                Path defaultDownloadsDir,
                                Function<String, String> dirPicker) {
        this.repository = repository;
        this.defaultDownloadsDir = defaultDownloadsDir;
        this.dirPicker = dirPicker;
    }

    @Override
    public byte[] render(String url, Map<String, String> query) {
        switch (pathOf(url)) {
            case "settings":
                return renderPage(repository.load());
            case "settings/save": {
                BrowserSettings settings = repository.load();
                apply(settings, query);
                repository.save(settings);
                return renderPage(repository.load());
            }
            case "settings/pickDownloadsDir": {
                BrowserSettings settings = repository.load();
                String chosen = null;
                if (dirPicker != null) {
                    try {
                        chosen = dirPicker.apply(settings.getDownloadsDir());
                    } catch (RuntimeException e) {
                        logger.warn("The download directory chooser failed: {}", e.toString());
                    }
                }
                if (chosen != null && !chosen.isBlank()) {
                    settings.setDownloadsDir(chosen.trim());
                    repository.save(settings);
                }
                return renderPage(repository.load());
            }
            default:
                return null; // unknown action → the 404 page
        }
    }
    // ------------------------------------------------------------------
    // Validation (pure — unit-tested)
    // ------------------------------------------------------------------

    /**
     * C2: the homepage. Blank means "the default" (the new tab page);
     * otherwise it must be a web URL or an internal page. Invalid input
     * keeps the current value (the user's setting is never destroyed).
     */
    static String validateHomepage(String raw, String current) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return BrowserSettings.DEFAULT_HOME_PAGE;
        }
        if (value.startsWith(BrowserSchemeHandler.SCHEME_PREFIX)) {
            return value;
        }
        if (value.matches("https?://[^\\s]+")) {
            return value;
        }
        return current != null && !current.isBlank()
                ? current
                : BrowserSettings.DEFAULT_HOME_PAGE;
    }

    /** C1: the startup mode token; {@code null} = keep the current one. */
    static BrowserSettings.StartupMode validateStartup(String raw) {
        if (raw == null) {
            return null;
        }
        switch (raw.trim()) {
            case "newtab":
                return BrowserSettings.StartupMode.NEW_TAB;
            case "last-session":
                return BrowserSettings.StartupMode.LAST_SESSION;
            case "urls":
                return BrowserSettings.StartupMode.URLS;
            default:
                return null;
        }
    }

    /**
     * C1: the startup URL list — the textarea arrives with {@code ;} and/or
     * line breaks between the URLs. Only web URLs survive, capped at
     * {@link #MAX_STARTUP_URLS}; an empty result is a valid (empty) list.
     */
    static List<String> parseStartupUrls(String raw) {
        List<String> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        for (String part : raw.split("[;\\n\\r]+")) {
            String value = part.trim();
            if (value.isEmpty() || out.size() >= MAX_STARTUP_URLS) {
                continue;
            }
            if (value.matches("https?://[^\\s]+")) {
                out.add(value);
            }
        }
        return out;
    }

    /**
     * C5: the download directory. Blank = the OS default (D1); otherwise
     * the string is trimmed and its trailing separators are stripped (a
     * Windows drive root like {@code C:\} keeps its separator).
     */
    static String validateDownloadsDir(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return "";
        }
        while (value.length() > 1 && (value.endsWith("\\") || value.endsWith("/"))) {
            value = value.substring(0, value.length() - 1);
        }
        if (value.length() == 2 && value.charAt(1) == ':') {
            value = value + "\\"; // "C:" means the drive root, not the CWD
        }
        return value;
    }

    /** The serialized startup token (the form's radio values). */
    static String startupToken(BrowserSettings.StartupMode mode) {
        switch (mode) {
            case NEW_TAB:
                return "newtab";
            case URLS:
                return "urls";
            default:
                return "last-session";
        }
    }

    /**
     * The effective download directory (C5, D1). A stored value that is not
     * a valid path (e.g. hand-edited garbage) degrades to the default —
     * the settings page must render under any saved state (D17).
     */
    Path effectiveDownloadsDir(BrowserSettings settings) {
        String dir = settings.getDownloadsDir();
        if (dir != null && !dir.isBlank()) {
            try {
                return Path.of(dir.trim());
            } catch (RuntimeException e) {
                // fall through to the default
            }
        }
        return defaultDownloadsDir != null
                ? defaultDownloadsDir
                : Path.of(System.getProperty("user.home", "."), "Downloads");
    }

    // ------------------------------------------------------------------
    // Rendering
    // ------------------------------------------------------------------
    private void apply(BrowserSettings settings, Map<String, String> query) {
        String homepage = query.get("homepage");
        if (homepage != null) {
            settings.setHomepage(validateHomepage(homepage, settings.getHomepage()));
        }
        BrowserSettings.StartupMode startup = validateStartup(query.get("startup"));
        if (startup != null) {
            settings.setStartup(startup);
        }
        String urls = query.get("urls");
        if (urls != null) {
            settings.setStartupUrls(parseStartupUrls(urls));
        }
        String dir = query.get("downloadsDir");
        if (dir != null) {
            settings.setDownloadsDir(validateDownloadsDir(dir));
        }
    }

    private byte[] renderPage(BrowserSettings settings) {
        PageData data = new PageData();
        data.homepage = settings.getHomepage();
        data.homepageDefault = BrowserSettings.DEFAULT_HOME_PAGE;
        data.startup = startupToken(settings.getStartup());
        data.urls = new ArrayList<>(settings.getStartupUrls());
        data.downloadsDir = settings.getDownloadsDir() == null ? "" : settings.getDownloadsDir();
        data.effectiveDownloadsDir = effectiveDownloadsDir(settings).toString();
        data.strings.put("title", I18n.get("browser.settings.title"));
        data.strings.put("startupSection", I18n.get("browser.settings.section.startup"));
        data.strings.put("startupNewtab", I18n.get("browser.settings.startup.newtab"));
        data.strings.put("startupLastSession", I18n.get("browser.settings.startup.last-session"));
        data.strings.put("startupUrls", I18n.get("browser.settings.startup.urls"));
        data.strings.put("startupUrlsHint", I18n.get("browser.settings.startup.urls.hint"));
        data.strings.put("generalSection", I18n.get("browser.settings.section.general"));
        data.strings.put("homepage", I18n.get("browser.settings.homepage"));
        data.strings.put("homepageHint", I18n.get("browser.settings.homepage.hint"));
        data.strings.put("downloadsSection", I18n.get("browser.settings.section.downloads"));
        data.strings.put("downloadsDir", I18n.get("browser.settings.downloadsDir"));
        data.strings.put("downloadsDirHint", I18n.get("browser.settings.downloadsDir.hint"));
        data.strings.put("effectiveDir",
                I18n.get("browser.settings.downloadsDir.effective", data.effectiveDownloadsDir));
        data.strings.put("browse", I18n.get("browser.settings.browse"));
        data.strings.put("save", I18n.get("browser.settings.save"));
        data.strings.put("about", I18n.get("browser.settings.link.about"));

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
        try (InputStream in = SettingsPageRenderer.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                logger.error("Settings page template missing from the classpath: {}", TEMPLATE);
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("Could not read the settings page template {}", TEMPLATE, e);
            return null;
        }
    }

    /** The injected payload (gson-serialized public fields). */
    static final class PageData {
        int v = 1;
        String homepage;
        String homepageDefault;
        String startup;
        List<String> urls = new ArrayList<>();
        String downloadsDir;
        String effectiveDownloadsDir;
        Map<String, String> strings = new LinkedHashMap<>();
    }
}
