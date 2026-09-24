package application.module.browser.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BrowserSettingsRepository} (safe defaults per D17).
 */
class BrowserSettingsRepositoryTest {

    @Test
    @DisplayName("a missing file loads as the safe defaults")
    void missingFileLoadsDefaults(@TempDir Path dir) {
        BrowserSettings settings = new BrowserSettingsRepository(dir.resolve("settings.json")).load();

        assertEquals(BrowserSettings.DEFAULT_HOME_PAGE, settings.getHomepage());
        assertEquals(BrowserSettings.StartupMode.LAST_SESSION, settings.getStartup());
        assertTrue(settings.isBlockFileUrls());
        assertFalse(settings.isWeb3Enabled());
    }

    @Test
    @DisplayName("save then load round-trips the settings")
    void saveLoadRoundTrip(@TempDir Path dir) {
        Path file = dir.resolve("settings.json");
        BrowserSettingsRepository repository = new BrowserSettingsRepository(file);
        BrowserSettings settings = new BrowserSettings();
        settings.setStartup(BrowserSettings.StartupMode.NEW_TAB);
        settings.setWeb3Enabled(true);
        settings.setHomepage("https://example.com");
        repository.save(settings);

        BrowserSettings loaded = repository.load();

        assertEquals(BrowserSettings.StartupMode.NEW_TAB, loaded.getStartup());
        assertTrue(loaded.isWeb3Enabled());
        assertEquals("https://example.com", loaded.getHomepage());
    }

    @Test
    @DisplayName("a corrupt file loads as the safe defaults (D17)")
    void corruptFileLoadsDefaults(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "not json at all", StandardCharsets.UTF_8);

        BrowserSettings settings = new BrowserSettingsRepository(file).load();

        assertEquals(BrowserSettings.StartupMode.LAST_SESSION, settings.getStartup());
    }

    @Test
    @DisplayName("a partial file fills the missing fields with defaults")
    void partialFileFillsDefaults(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "{\"version\":1,\"startup\":\"urls\"}", StandardCharsets.UTF_8);

        BrowserSettings settings = new BrowserSettingsRepository(file).load();

        assertEquals(BrowserSettings.StartupMode.URLS, settings.getStartup());
        assertEquals(BrowserSettings.DEFAULT_HOME_PAGE, settings.getHomepage());
        assertEquals(BrowserSettings.DEFAULT_SEARCH_ENGINE_TEMPLATE, settings.getSearchEngineTemplate());
        assertTrue(settings.isBlockFileUrls());
    }

    @Test
    @DisplayName("an invalid search template falls back to the default (D17)")
    void invalidSearchTemplateFallsBack(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "{\"searchEngineTemplate\":\"https://no-placeholder.example\"}", StandardCharsets.UTF_8);

        BrowserSettings settings = new BrowserSettingsRepository(file).load();

        assertEquals(BrowserSettings.DEFAULT_SEARCH_ENGINE_TEMPLATE, settings.getSearchEngineTemplate());
    }
}