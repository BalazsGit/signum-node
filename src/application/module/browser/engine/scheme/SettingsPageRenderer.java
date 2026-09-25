package application.module.browser.engine.scheme;

import application.module.browser.config.BrowserSettings;
import application.module.browser.config.BrowserSettingsRepository;
import application.module.browser.config.SearchEnginePresets;
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
    /** Cap of the max-tabs setting (C8 sanity bound). */
    public static final int MAX_MAX_TABS = 100;
    /** Cap of the discard delay in minutes (C8: one week). */
    public static final int MAX_DISCARD_MINUTES = 7 * 24 * 60;

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
    /** F9 (C6): the clear-browsing-data action (GUI, EDT); may be null. */
    private Runnable clearDataAction;
    /** F9 (C8): fired on the scheme thread after a save (the GUI pumps it). */
    private Runnable savedCallback;

    public SettingsPageRenderer(BrowserSettingsRepository repository,
                                Path defaultDownloadsDir,
                                Function<String, String> dirPicker) {
        this.repository = repository;
        this.defaultDownloadsDir = defaultDownloadsDir;
        this.dirPicker = dirPicker;
    }

    /** F9 (C6): the clear-data dialog opener (the GUI implements it on the EDT). */
    public void setClearDataAction(Runnable clearDataAction) {
        this.clearDataAction = clearDataAction;
    }

    /** F9 (C8): a hook fired after every successful save (max-tabs refresh). */
    public void setOnSaved(Runnable savedCallback) {
        this.savedCallback = savedCallback;
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
                fireSaved();
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
                    fireSaved();
                }
                return renderPage(repository.load());
            }
            case "settings/clearData": {
                // C6: the GUI opens the clear-data dialog (history + cookies)
                Runnable action = clearDataAction;
                if (action != null) {
                    try {
                        action.run();
                    } catch (RuntimeException e) {
                        logger.warn("The clear-data dialog failed: {}", e.toString());
                    }
                }
                return renderPage(repository.load());
            }
            default:
                return null; // unknown action → the 404 page
        }
    }

    private void fireSaved() {
        Runnable callback = savedCallback;
        if (callback != null) {
            try {
                callback.run(); // the GUI implementation must not block
            } catch (RuntimeException e) {
                logger.warn("The settings-saved callback failed: {}", e.toString());
            }
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

    // ------------------------------------------------------------------
    // F9 validation (pure — unit-tested)
    // ------------------------------------------------------------------

    /** The name the settings form shows for a non-preset engine. */
    public static final String ENGINE_CUSTOM = "Custom";

    /**
     * C3/N11: the search engine. A preset name takes its built-in template;
     * anything else is a custom engine and its template must be a web URL
     * containing {@code {query}}. Invalid input keeps both current values.
     *
     * @return the (name, template) pair to store (never null)
     */
    static String[] validateSearchEngine(String rawName, String rawTemplate,
                                         String currentName, String currentTemplate) {
        String name = rawName == null ? "" : rawName.trim();
        String preset = SearchEnginePresets.templateOf(name);
        if (preset != null) {
            return new String[]{name, preset};
        }
        String template = rawTemplate == null ? "" : rawTemplate.trim();
        if (template.matches("https?://[^\\s]*\\{query\\}[^\\s]*")) {
            return new String[]{ENGINE_CUSTOM, template};
        }
        return new String[]{
                currentName != null && !currentName.isBlank() ? currentName : SearchEnginePresets.DUCKDUCKGO,
                currentTemplate != null && currentTemplate.contains("{query}")
                        ? currentTemplate : BrowserSettings.DEFAULT_SEARCH_ENGINE_TEMPLATE};
    }

    /** C4: the theme mode; {@code null} = keep the current one. */
    static String validateTheme(String raw) {
        if (raw != null && ThemePalette.isKnown(raw.trim())) {
            return raw.trim();
        }
        return null;
    }

    /** C8: the max-tabs cap (1..{@value #MAX_MAX_TABS}); {@code null} = keep. */
    static Integer validateMaxTabs(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return (value >= 1 && value <= MAX_MAX_TABS) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** C8: the discard delay in minutes (1..{@value #MAX_DISCARD_MINUTES}); {@code null} = keep. */
    static Integer validateDiscardMinutes(String raw) {
        if (raw == null) {
            return null;
        }
        try {
            int value = Integer.parseInt(raw.trim());
            return (value >= 1 && value <= MAX_DISCARD_MINUTES) ? value : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /** N12: the file:// block switch token; {@code null} = keep the current one. */
    static Boolean parseBlockFileUrls(String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim()) {
            case "true" -> true;
            case "false" -> false;
            default -> null;
        };
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
        // F9 (C3/N11): the search engine (preset or custom template)
        if (query.containsKey("searchEngine") || query.containsKey("searchTemplate")) {
            String[] engine = validateSearchEngine(query.get("searchEngine"),
                    query.get("searchTemplate"),
                    settings.getSearchEngineName(), settings.getSearchEngineTemplate());
            settings.setSearchEngineName(engine[0]);
            settings.setSearchEngineTemplate(engine[1]);
        }
        // F9 (C4): the internal-page theme
        String theme = validateTheme(query.get("theme"));
        if (theme != null) {
            settings.setTheme(theme);
        }
        // F9 (C8/N12): the limits and the file:// block
        Integer maxTabs = validateMaxTabs(query.get("maxTabs"));
        if (maxTabs != null) {
            settings.setMaxTabs(maxTabs);
        }
        Integer discard = validateDiscardMinutes(query.get("discardMinutes"));
        if (discard != null) {
            settings.setDiscardMinutes(discard);
        }
        Boolean blockFile = parseBlockFileUrls(query.get("blockFileUrls"));
        if (blockFile != null) {
            settings.setBlockFileUrls(blockFile);
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
        // F9 fields
        data.searchEngine = settings.getSearchEngineName();
        data.searchTemplate = settings.getSearchEngineTemplate();
        data.enginePresets = new LinkedHashMap<>(SearchEnginePresets.all());
        data.engineCustom = ENGINE_CUSTOM;
        data.theme = settings.getTheme();
        data.maxTabs = settings.getMaxTabs();
        data.discardMinutes = settings.getDiscardMinutes();
        data.blockFileUrls = settings.isBlockFileUrls();
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
        // F9 strings
        data.strings.put("searchSection", I18n.get("browser.settings.section.search"));
        data.strings.put("searchEngine", I18n.get("browser.settings.searchEngine"));
        data.strings.put("searchTemplate", I18n.get("browser.settings.searchEngine.template"));
        data.strings.put("searchTemplateHint",
                I18n.get("browser.settings.searchEngine.template.hint"));
        data.strings.put("searchHint", I18n.get("browser.settings.searchEngine.hint"));
        data.strings.put("appearanceSection", I18n.get("browser.settings.section.appearance"));
        data.strings.put("theme", I18n.get("browser.settings.theme"));
        data.strings.put("themeFollowApp", I18n.get("browser.settings.theme.follow-app"));
        data.strings.put("themeLight", I18n.get("browser.settings.theme.light"));
        data.strings.put("themeDark", I18n.get("browser.settings.theme.dark"));
        data.strings.put("themeHint", I18n.get("browser.settings.theme.hint"));
        data.strings.put("privacySection", I18n.get("browser.settings.section.privacy"));
        data.strings.put("blockFile", I18n.get("browser.settings.blockFile"));
        data.strings.put("blockFileHint", I18n.get("browser.settings.blockFile.hint"));
        data.strings.put("clearData", I18n.get("browser.settings.clearData"));
        data.strings.put("advancedSection", I18n.get("browser.settings.section.advanced"));
        data.strings.put("maxTabs", I18n.get("browser.settings.maxTabs"));
        data.strings.put("maxTabsHint", I18n.get("browser.settings.maxTabs.hint"));
        data.strings.put("discardMinutes", I18n.get("browser.settings.discardMinutes"));
        data.strings.put("discardHint", I18n.get("browser.settings.discardMinutes.hint"));

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
        // F9 fields
        String searchEngine;
        String searchTemplate;
        Map<String, String> enginePresets = new LinkedHashMap<>();
        String engineCustom;
        String theme;
        int maxTabs;
        int discardMinutes;
        boolean blockFileUrls;
        Map<String, String> strings = new LinkedHashMap<>();
    }
}
