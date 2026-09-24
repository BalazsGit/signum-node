package application.module.browser.config;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * Browser module settings (plan Appendix C, D8: {@code conf/browser/settings.json}).
 * <p>
 * Pure data with safe defaults (D17): every default is the conservative
 * choice (Web3 OFF, {@code file://} blocked, sandboxed engine). Fields not
 * needed by F1 (search engine, downloads, theme, max tabs) are already part
 * of the schema so later phases do not have to migrate the file.
 */
public final class BrowserSettings {

    public static final int VERSION = 1;
    public static final String DEFAULT_HOME_PAGE = "signum://newtab";
    public static final String DEFAULT_SEARCH_ENGINE_NAME = "DuckDuckGo";
    public static final String DEFAULT_SEARCH_ENGINE_TEMPLATE = "https://duckduckgo.com/?q={query}";
    public static final String DEFAULT_THEME = "follow-app";
    public static final int DEFAULT_MAX_TABS = 25;
    public static final int DEFAULT_DISCARD_MINUTES = 15;

    /** T8 startup behavior ({@code "startup"} in settings.json). */
    public enum StartupMode {
        @SerializedName("newtab")
        NEW_TAB,
        @SerializedName("last-session")
        LAST_SESSION,
        @SerializedName("urls")
        URLS
    }

    private int version = VERSION;
    private String homepage = DEFAULT_HOME_PAGE;
    private StartupMode startup = StartupMode.LAST_SESSION;
    private List<String> startupUrls = new ArrayList<>();
    private String searchEngineName = DEFAULT_SEARCH_ENGINE_NAME;
    private String searchEngineTemplate = DEFAULT_SEARCH_ENGINE_TEMPLATE;
    /** Empty = the OS default downloads directory (D1). */
    private String downloadsDir = "";
    /** D15: the global Web3 switch — off by default, opt-in. */
    private boolean web3Enabled = false;
    private String theme = DEFAULT_THEME;
    private int maxTabs = DEFAULT_MAX_TABS;
    /** D13: inactive-tab discard delay (P1 feature; the knob is saved early). */
    private int discardMinutes = DEFAULT_DISCARD_MINUTES;
    /** D17: {@code file://} navigation stays blocked unless the user opts out (N12, F2+). */
    private boolean blockFileUrls = true;
    private boolean showBookmarksBar = true;

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        this.version = version;
    }

    public String getHomepage() {
        return homepage;
    }

    public void setHomepage(String homepage) {
        this.homepage = homepage;
    }

    public StartupMode getStartup() {
        return startup;
    }

    public void setStartup(StartupMode startup) {
        this.startup = startup;
    }

    public List<String> getStartupUrls() {
        return startupUrls;
    }

    public void setStartupUrls(List<String> startupUrls) {
        this.startupUrls = startupUrls;
    }

    public String getSearchEngineName() {
        return searchEngineName;
    }

    public void setSearchEngineName(String searchEngineName) {
        this.searchEngineName = searchEngineName;
    }

    public String getSearchEngineTemplate() {
        return searchEngineTemplate;
    }

    public void setSearchEngineTemplate(String searchEngineTemplate) {
        this.searchEngineTemplate = searchEngineTemplate;
    }

    public String getDownloadsDir() {
        return downloadsDir;
    }

    public void setDownloadsDir(String downloadsDir) {
        this.downloadsDir = downloadsDir;
    }

    public boolean isWeb3Enabled() {
        return web3Enabled;
    }

    public void setWeb3Enabled(boolean web3Enabled) {
        this.web3Enabled = web3Enabled;
    }

    public String getTheme() {
        return theme;
    }

    public void setTheme(String theme) {
        this.theme = theme;
    }

    public int getMaxTabs() {
        return maxTabs;
    }

    public void setMaxTabs(int maxTabs) {
        this.maxTabs = maxTabs;
    }

    public int getDiscardMinutes() {
        return discardMinutes;
    }

    public void setDiscardMinutes(int discardMinutes) {
        this.discardMinutes = discardMinutes;
    }

    public boolean isBlockFileUrls() {
        return blockFileUrls;
    }

    public void setBlockFileUrls(boolean blockFileUrls) {
        this.blockFileUrls = blockFileUrls;
    }

    public boolean isShowBookmarksBar() {
        return showBookmarksBar;
    }

    public void setShowBookmarksBar(boolean showBookmarksBar) {
        this.showBookmarksBar = showBookmarksBar;
    }

    /**
     * Fills any field that gson left {@code null} (missing in the JSON) with
     * its safe default. Called by the repository after deserialization only.
     */
    void sanitize() {
        if (homepage == null || homepage.isBlank()) {
            homepage = DEFAULT_HOME_PAGE;
        }
        if (startup == null) {
            startup = StartupMode.LAST_SESSION;
        }
        if (startupUrls == null) {
            startupUrls = new ArrayList<>();
        }
        if (searchEngineName == null || searchEngineName.isBlank()) {
            searchEngineName = DEFAULT_SEARCH_ENGINE_NAME;
        }
        if (searchEngineTemplate == null || !searchEngineTemplate.contains("{query}")) {
            searchEngineTemplate = DEFAULT_SEARCH_ENGINE_TEMPLATE;
        }
        if (downloadsDir == null) {
            downloadsDir = "";
        }
        if (theme == null || theme.isBlank()) {
            theme = DEFAULT_THEME;
        }
        if (maxTabs <= 0) {
            maxTabs = DEFAULT_MAX_TABS;
        }
        if (discardMinutes <= 0) {
            discardMinutes = DEFAULT_DISCARD_MINUTES;
        }
    }
}
