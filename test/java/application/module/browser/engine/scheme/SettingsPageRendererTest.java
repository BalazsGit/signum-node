package application.module.browser.engine.scheme;

import application.module.browser.config.BrowserSettings;
import application.module.browser.config.BrowserSettingsRepository;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SettingsPageRenderer} (F6, C1/C2/C5): the URL space,
 * the value validation (garbage in, nothing lost), the save/pick actions and
 * the S9 script-safety of the injected data island.
 */
class SettingsPageRendererTest {

    private static final String ISLAND_OPEN = "<script id=\"data\" type=\"application/json\">";

    private final Gson gson = new Gson();
    private BrowserSettingsRepository repository;
    private SettingsPageRenderer renderer;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        Path file = dir.resolve("settings.json");
        repository = new BrowserSettingsRepository(file);
        renderer = new SettingsPageRenderer(repository,
                Path.of("/tmp/Downloads"), current -> null);
    }

    /** Parses the injected JSON data island out of a rendered page. */
    private JsonObject island(byte[] page) {
        String html = new String(page, StandardCharsets.UTF_8);
        int start = html.indexOf(ISLAND_OPEN) + ISLAND_OPEN.length();
        int end = html.indexOf("</script>", start);
        assertTrue(end > start, "the rendered page must carry the data island");
        return gson.fromJson(html.substring(start, end), JsonObject.class);
    }

    // ------------------------------------------------------------------
    // C2: homepage validation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a blank homepage means the default (the new tab page)")
    void homepageBlankIsDefault() {
        assertEquals(BrowserSettings.DEFAULT_HOME_PAGE,
                SettingsPageRenderer.validateHomepage("", "https://old.example"));
        assertEquals(BrowserSettings.DEFAULT_HOME_PAGE,
                SettingsPageRenderer.validateHomepage(null, "https://old.example"));
    }

    @Test
    @DisplayName("web URLs and internal pages are accepted as-is")
    void homepageValidAccepted() {
        assertEquals("https://example.com",
                SettingsPageRenderer.validateHomepage("https://example.com", null));
        assertEquals("http://example.com/path?q=1",
                SettingsPageRenderer.validateHomepage("http://example.com/path?q=1", null));
        assertEquals("signum://newtab",
                SettingsPageRenderer.validateHomepage("signum://newtab", null));
    }

    @Test
    @DisplayName("an invalid homepage keeps the current value (nothing is destroyed)")
    void homepageInvalidKeepsCurrent() {
        assertEquals("https://old.example",
                SettingsPageRenderer.validateHomepage("ftp://nope.example", "https://old.example"));
        assertEquals("https://old.example",
                SettingsPageRenderer.validateHomepage("javascript:alert(1)", "https://old.example"));
        assertEquals("https://old.example",
                SettingsPageRenderer.validateHomepage("https://a b.example", "https://old.example"));
        assertEquals(BrowserSettings.DEFAULT_HOME_PAGE,
                SettingsPageRenderer.validateHomepage("ftp://nope.example", null));
    }
    // ------------------------------------------------------------------
    // C1: startup validation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the startup tokens map to the enum, unknown keeps the current one")
    void startupTokens() {
        assertEquals(BrowserSettings.StartupMode.NEW_TAB,
                SettingsPageRenderer.validateStartup("newtab"));
        assertEquals(BrowserSettings.StartupMode.LAST_SESSION,
                SettingsPageRenderer.validateStartup("last-session"));
        assertEquals(BrowserSettings.StartupMode.URLS,
                SettingsPageRenderer.validateStartup("urls"));
        assertNull(SettingsPageRenderer.validateStartup("bogus"));
        assertNull(SettingsPageRenderer.validateStartup(null));
    }

    @Test
    @DisplayName("the startup URL list accepts ; and newlines, drops non-web URLs, caps at 20")
    void startupUrlsParsing() {
        assertEquals(List.of("https://a.example", "http://b.example"),
                SettingsPageRenderer.parseStartupUrls("https://a.example;http://b.example\n"));
        assertEquals(List.of("https://a.example"),
                SettingsPageRenderer.parseStartupUrls("https://a.example\nftp://nope\n\nsignum://newtab"));
        assertEquals(List.of(), SettingsPageRenderer.parseStartupUrls(""));
        assertEquals(List.of(), SettingsPageRenderer.parseStartupUrls(null));

        StringBuilder many = new StringBuilder();
        for (int i = 0; i < 25; i++) {
            many.append("https://x").append(i).append(".example;");
        }
        assertEquals(SettingsPageRenderer.MAX_STARTUP_URLS,
                SettingsPageRenderer.parseStartupUrls(many.toString()).size());
    }

    @Test
    @DisplayName("the startup token round-trips through the serialized form")
    void startupTokenRoundTrip() {
        assertEquals("newtab",
                SettingsPageRenderer.startupToken(BrowserSettings.StartupMode.NEW_TAB));
        assertEquals("last-session",
                SettingsPageRenderer.startupToken(BrowserSettings.StartupMode.LAST_SESSION));
        assertEquals("urls",
                SettingsPageRenderer.startupToken(BrowserSettings.StartupMode.URLS));
    }

    // ------------------------------------------------------------------
    // C5: downloads directory validation
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the directory is trimmed, trailing separators are stripped, blank = default")
    void downloadsDirValidation() {
        assertEquals("", SettingsPageRenderer.validateDownloadsDir("  "));
        assertEquals("", SettingsPageRenderer.validateDownloadsDir(null));
        assertEquals("/data/downloads",
                SettingsPageRenderer.validateDownloadsDir("  /data/downloads/  "));
        assertEquals("D:\\Data\\Downloads",
                SettingsPageRenderer.validateDownloadsDir("D:\\Data\\Downloads\\"));
        // a Windows drive root keeps its separator (C: alone would mean the CWD)
        assertEquals("C:\\", SettingsPageRenderer.validateDownloadsDir("C:\\"));
        assertEquals("C:\\", SettingsPageRenderer.validateDownloadsDir("C:"));
    }
    // ------------------------------------------------------------------
    // Rendering + actions
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the page injects the saved values and the effective directory")
    void pageInjectsSavedValues() {
        BrowserSettings settings = new BrowserSettings();
        settings.setHomepage("https://home.example");
        settings.setStartup(BrowserSettings.StartupMode.URLS);
        settings.getStartupUrls().add("https://a.example");
        settings.setDownloadsDir("/data/dl");
        repository.save(settings);

        JsonObject page = island(renderer.render(SettingsPageRenderer.PAGE_URL, Map.of()));

        assertEquals("https://home.example", page.get("homepage").getAsString());
        assertEquals("urls", page.get("startup").getAsString());
        assertEquals("/data/dl", page.get("downloadsDir").getAsString());
        // the effective directory is a Path — platform separators apply
        assertEquals(Path.of("/data/dl").toString(),
                page.get("effectiveDownloadsDir").getAsString());
        assertEquals("https://a.example",
                page.getAsJsonArray("urls").get(0).getAsString());
        assertTrue(page.getAsJsonObject("strings").entrySet().size() >= 16);
        assertFalse(new String(renderer.render(SettingsPageRenderer.PAGE_URL, Map.of()),
                StandardCharsets.UTF_8).contains("__SIGNUM_DATA__"));
    }

    @Test
    @DisplayName("the effective directory falls back to the OS default when unset")
    void effectiveDirFallback() {
        JsonObject page = island(renderer.render(SettingsPageRenderer.PAGE_URL, Map.of()));
        assertEquals(Path.of("/tmp/Downloads").toString(),
                page.get("effectiveDownloadsDir").getAsString());
    }

    private static String saveUrl(String homepage, String startup, String urls, String dir) {
        return "signum://settings/save?homepage=" + enc(homepage)
                + "&startup=" + enc(startup)
                + "&urls=" + enc(urls)
                + "&downloadsDir=" + enc(dir);
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    @Test
    @DisplayName("settings/save persists the valid values (round-trip through the file)")
    void saveActionPersists() {
        String url = saveUrl("https://home.example", "urls",
                "https://a.example;https://b.example", "/data/dl");

        byte[] page = renderer.render(url, InternalPage.query(url));

        assertNotNull(page);
        BrowserSettings saved = repository.load();
        assertEquals("https://home.example", saved.getHomepage());
        assertEquals(BrowserSettings.StartupMode.URLS, saved.getStartup());
        assertEquals(List.of("https://a.example", "https://b.example"), saved.getStartupUrls());
        assertEquals("/data/dl", saved.getDownloadsDir());
    }

    @Test
    @DisplayName("settings/save with an invalid homepage keeps the old value")
    void saveActionInvalidKeepsOld() {
        BrowserSettings settings = new BrowserSettings();
        settings.setHomepage("https://old.example");
        repository.save(settings);

        String url = saveUrl("ftp://nope.example", "newtab", "", "");
        renderer.render(url, InternalPage.query(url));

        BrowserSettings saved = repository.load();
        assertEquals("https://old.example", saved.getHomepage());
        assertEquals(BrowserSettings.StartupMode.NEW_TAB, saved.getStartup());
        assertEquals(List.of(), saved.getStartupUrls());
    }

    @Test
    @DisplayName("pickDownloadsDir saves the chooser's result; a cancel keeps the old value")
    void pickAction() {
        SettingsPageRenderer withPicker = new SettingsPageRenderer(repository,
                Path.of("/tmp/Downloads"), current -> "/data/chosen");
        withPicker.render("signum://settings/pickDownloadsDir", Map.of());
        assertEquals("/data/chosen", repository.load().getDownloadsDir());

        // the default renderer's picker returns null (cancel) — nothing changes
        renderer.render("signum://settings/pickDownloadsDir", Map.of());
        assertEquals("/data/chosen", repository.load().getDownloadsDir());
    }

    @Test
    @DisplayName("an unknown settings sub-path renders nothing (the 404 fallback wins)")
    void unknownActionReturnsNull() {
        assertNull(renderer.render("signum://settings/bogus", Map.of()));
    }

    // ------------------------------------------------------------------
    // S9: script safety of the data island
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a hostile directory value cannot break out of the script tag")
    void hostileValueIsEscaped() {
        BrowserSettings settings = new BrowserSettings();
        settings.setDownloadsDir("</script><script>alert(1)</script>");
        repository.save(settings);

        String html = new String(renderer.render(SettingsPageRenderer.PAGE_URL, Map.of()),
                StandardCharsets.UTF_8);

        assertFalse(html.contains("</script><script>alert(1)</script>"));
        assertTrue(html.contains("\\u003c/script\\u003e"));
        // the escaped island must still parse (and the value round-trips)
        assertEquals("</script><script>alert(1)</script>",
                island(renderer.render(SettingsPageRenderer.PAGE_URL, Map.of()))
                        .get("downloadsDir").getAsString());
    }

    @Test
    @DisplayName("escapeForScript turns every '<' into the valid JSON escape \\u003c (and only that)")
    void escapeForScript() {
        assertEquals("{\"a\":\"\\u003cb>&\"}",
                SettingsPageRenderer.escapeForScript("{\"a\":\"<b>&\"}"));
        assertEquals("{\"u\":\"https://a\\u003cb.example/c>d\"}",
                SettingsPageRenderer.escapeForScript("{\"u\":\"https://a<b.example/c>d\"}"));
    }
}
