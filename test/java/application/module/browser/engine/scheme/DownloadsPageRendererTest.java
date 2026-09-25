package application.module.browser.engine.scheme;

import application.module.browser.model.download.DownloadManager;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DownloadsPageRenderer} (F6, D4): the recent list
 * (newest first), the remove/clear actions (files untouched) and the S9
 * script-safety of the injected data island.
 */
class DownloadsPageRendererTest {

    private static final String ISLAND_OPEN = "<script id=\"data\" type=\"application/json\">";

    private final Gson gson = new Gson();
    private DownloadManager manager;
    private DownloadsPageRenderer renderer;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        manager = new DownloadManager(dir.resolve("downloads.json"),
                () -> dir);
        renderer = new DownloadsPageRenderer(manager);
    }

    @AfterEach
    void tearDown() {
        manager.close();
    }

    /** Parses the injected JSON data island out of a rendered page. */
    private JsonObject island(byte[] page) {
        String html = new String(page, StandardCharsets.UTF_8);
        int start = html.indexOf(ISLAND_OPEN) + ISLAND_OPEN.length();
        int end = html.indexOf("</script>", start);
        assertTrue(end > start, "the rendered page must carry the data island");
        return gson.fromJson(html.substring(start, end), JsonObject.class);
    }

    private JsonArray entries(JsonObject island) {
        return island.getAsJsonArray("entries");
    }

    @Test
    @DisplayName("an empty manager renders the empty page (the i18n strings are injected)")
    void emptyList() {
        JsonObject page = island(renderer.render(DownloadsPageRenderer.PAGE_URL, Map.of()));
        assertTrue(entries(page).isEmpty());
        assertTrue(page.getAsJsonObject("strings").entrySet().size() >= 11);
        assertFalse(new String(renderer.render(DownloadsPageRenderer.PAGE_URL, Map.of()),
                StandardCharsets.UTF_8).contains("__SIGNUM_DATA__"));
    }

    @Test
    @DisplayName("the list is newest first and carries the item state and sizes")
    void listOrderAndContent() {
        manager.register("https://a.example/old.pdf", "old.pdf", 1);
        manager.register("https://b.example/new.pdf", "new.pdf", 2);

        JsonObject page = island(renderer.render(DownloadsPageRenderer.PAGE_URL, Map.of()));
        JsonArray rows = entries(page);

        assertEquals(2, rows.size());
        // newest (second registered) first
        assertEquals("new.pdf", rows.get(0).getAsJsonObject().get("name").getAsString());
        assertEquals("old.pdf", rows.get(1).getAsJsonObject().get("name").getAsString());
        assertEquals("PENDING", rows.get(0).getAsJsonObject().get("state").getAsString());
        assertEquals(0L, rows.get(0).getAsJsonObject().get("received").getAsLong());
        assertEquals(0L, rows.get(0).getAsJsonObject().get("total").getAsLong());
    }

    @Test
    @DisplayName("downloads/remove deletes the entry from the list and re-renders")
    void removeAction() {
        var old = manager.register("https://a.example/old.pdf", "old.pdf", 1);
        manager.register("https://b.example/new.pdf", "new.pdf", 2);

        JsonObject page = island(renderer.render(
                "signum://downloads/remove?id=" + old.getId(),
                Map.of("id", old.getId())));

        assertEquals(1, entries(page).size());
        assertEquals("new.pdf",
                entries(page).get(0).getAsJsonObject().get("name").getAsString());
        assertEquals(1, manager.items().size());
    }

    @Test
    @DisplayName("downloads/clear empties the whole list")
    void clearAction() {
        manager.register("https://a.example/old.pdf", "old.pdf", 1);
        manager.register("https://b.example/new.pdf", "new.pdf", 2);

        JsonObject page = island(renderer.render("signum://downloads/clear", Map.of()));

        assertTrue(entries(page).isEmpty());
        assertTrue(manager.items().isEmpty());
    }

    @Test
    @DisplayName("an unknown downloads sub-path renders nothing (the 404 fallback wins)")
    void unknownActionReturnsNull() {
        assertNull(renderer.render("signum://downloads/bogus", Map.of()));
    }

    @Test
    @DisplayName("a hostile file name is neutralized by the manager's sanitizer and never reaches the page raw")
    void hostileNameIsNeutralized() {
        manager.register("https://evil.example/x", "</script><script>alert(1)</script>", 1);

        String html = new String(renderer.render(DownloadsPageRenderer.PAGE_URL, Map.of()),
                StandardCharsets.UTF_8);

        assertFalse(html.contains("</script><script>alert(1)</script>"));
        // the island still parses and the sanitized name carries no angle brackets
        String name = entries(island(renderer.render(DownloadsPageRenderer.PAGE_URL, Map.of())))
                .get(0).getAsJsonObject().get("name").getAsString();
        assertFalse(name.contains("<"));
        assertFalse(name.contains(">"));
    }

    @Test
    @DisplayName("escapeForScript turns every '<' into the valid JSON escape \\u003c (and only that)")
    void escapeForScript() {
        assertEquals("{\"a\":\"\\u003cb>&\"}",
                DownloadsPageRenderer.escapeForScript("{\"a\":\"<b>&\"}"));
        assertEquals("{\"t\":\"no brackets\"}", DownloadsPageRenderer.escapeForScript("{\"t\":\"no brackets\"}"));
    }
}
