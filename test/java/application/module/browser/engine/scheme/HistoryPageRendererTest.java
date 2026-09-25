package application.module.browser.engine.scheme;

import application.module.browser.model.history.HistoryStore;
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
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HistoryPageRenderer} (F3, H2/H3: URL space, filters,
 * actions and the S9 script-safety of the injected data island).
 */
class HistoryPageRendererTest {

    private static final long T0 = 1_700_000_000_000L;
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final String ISLAND_OPEN =
            "<script id=\"signum-data\" type=\"application/json\">";

    private final Gson gson = new Gson();
    private HistoryStore store;
    private HistoryPageRenderer renderer;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        store = new HistoryStore(dir.resolve("history.db"));
        renderer = new HistoryPageRenderer(store);
    }

    @AfterEach
    void tearDown() {
        store.close();
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

    // ------------------------------------------------------------------
    // Listing (H2)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the listing page injects the store data and the i18n strings")
    void listingInjectsStoreData() {
        store.record("https://alpha.example", "Alpha", T0, null);
        store.record("https://beta.example", "Beta", T0 + 1, null);
        store.flush();

        byte[] page = renderer.render("signum://history", Map.of());

        assertNotNull(page);
        String html = new String(page, StandardCharsets.UTF_8);
        assertFalse(html.contains("__SIGNUM_DATA__"));
        assertEquals(2, island(page).get("total").getAsInt());
        assertEquals(2, entries(island(page)).size());
        assertEquals("https://alpha.example",
                entries(island(page)).get(1).getAsJsonObject().get("url").getAsString());
        assertTrue(island(page).getAsJsonObject("strings").entrySet().size() >= 13);
    }

    @Test
    @DisplayName("search and time-range filters restrict the injected entries")
    void filtersRestrictEntries() {
        long now = System.currentTimeMillis();
        store.record("https://alpha.example", "Alpha", now, null); // today / 7d / 30d
        store.record("https://beta.example", "Beta", now - 10 * DAY_MS, null); // 30d only
        store.flush();

        JsonObject byQuery = island(renderer.render("signum://history",
                Map.of("q", "alpha", "range", "all")));
        assertEquals(1, byQuery.get("total").getAsInt());
        assertEquals("alpha", byQuery.get("q").getAsString());

        JsonObject last7Days = island(renderer.render("signum://history",
                Map.of("range", "7d")));
        assertEquals(1, last7Days.get("total").getAsInt());

        JsonObject last30Days = island(renderer.render("signum://history",
                Map.of("range", "30d")));
        assertEquals(2, last30Days.get("total").getAsInt());

        JsonObject today = island(renderer.render("signum://history",
                Map.of("range", "today")));
        assertEquals(1, today.get("total").getAsInt());
    }

    // ------------------------------------------------------------------
    // Actions (H3)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("history/delete removes the row and re-renders the listing")
    void deleteActionRemovesEntry() {
        store.record("https://alpha.example", "Alpha", T0, null);
        store.record("https://beta.example", "Beta", T0 + 1, null);
        store.flush();

        long idBeta = store.search("beta.example", 0L, Long.MAX_VALUE, 1).get(0).getId();
        JsonObject page = island(renderer.render("signum://history/delete?id=" + idBeta,
                Map.of("id", String.valueOf(idBeta))));

        assertEquals(1, page.get("total").getAsInt());
        assertEquals("https://alpha.example",
                entries(page).get(0).getAsJsonObject().get("url").getAsString());
        assertEquals(1L, store.count());
    }

    @Test
    @DisplayName("history/clear empties the store and the listing shows nothing")
    void clearActionEmptiesStore() {
        store.record("https://alpha.example", "Alpha", T0, null);
        store.flush();

        JsonObject page = island(renderer.render("signum://history/clear", Map.of()));

        assertEquals(0, page.get("total").getAsInt());
        assertTrue(entries(page).isEmpty());
        assertEquals(0L, store.count());
    }

    @Test
    @DisplayName("an unknown history sub-path renders nothing (the 404 fallback wins)")
    void unknownActionReturnsNull() {
        assertNull(renderer.render("signum://history/bogus", Map.of()));
    }

    // ------------------------------------------------------------------
    // S9: script safety of the data island
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a hostile page title cannot break out of the script tag")
    void hostileTitleIsEscaped() {
        store.record("https://evil.example", "</script><script>alert(1)</script>", T0, null);
        store.flush();

        String html = new String(renderer.render("signum://history", Map.of()),
                StandardCharsets.UTF_8);

        assertFalse(html.contains("</script><script>alert(1)</script>"));
        assertTrue(html.contains("\\u003c/script\\u003e"));
        // the escaped island must still parse as JSON (and the title round-trips)
        JsonObject island = island(renderer.render("signum://history", Map.of()));
        assertEquals("</script><script>alert(1)</script>",
                island.getAsJsonArray("entries").get(0).getAsJsonObject()
                        .get("title").getAsString());
    }

    @Test
    @DisplayName("escapeForScript turns every '<' into the valid JSON escape \\u003c (and only that)")
    void escapeForScript() {
        assertEquals("{\"a\":\"\\u003cb>&\"}", HistoryPageRenderer.escapeForScript("{\"a\":\"<b>&\"}"));
        assertEquals("{\"u\":\"https://a\\u003cb.example/c>d\"}",
                HistoryPageRenderer.escapeForScript("{\"u\":\"https://a<b.example/c>d\"}"));
        assertEquals("{\"t\":\"no brackets\"}", HistoryPageRenderer.escapeForScript("{\"t\":\"no brackets\"}"));
    }

    // ------------------------------------------------------------------
    // Time filter arithmetic (H2)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("fromTsOf maps range names to lower bounds (unknown = all time)")
    void fromTsOfMapping() {
        long now = System.currentTimeMillis();
        assertEquals(0L, HistoryPageRenderer.fromTsOf("all", now));
        assertEquals(0L, HistoryPageRenderer.fromTsOf("", now));
        assertEquals(0L, HistoryPageRenderer.fromTsOf("bogus", now));
        assertTrue(Math.abs(HistoryPageRenderer.fromTsOf("7d", now) - (now - 7 * DAY_MS)) < 1000L);
        assertTrue(Math.abs(HistoryPageRenderer.fromTsOf("30d", now) - (now - 30 * DAY_MS)) < 1000L);
        assertEquals(LocalDate.now().atStartOfDay(ZoneId.systemDefault())
                        .toInstant().toEpochMilli(),
                HistoryPageRenderer.fromTsOf("today", now));
    }
}