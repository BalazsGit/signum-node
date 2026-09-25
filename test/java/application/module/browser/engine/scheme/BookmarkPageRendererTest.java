package application.module.browser.engine.scheme;

import application.module.browser.model.bookmarks.BookmarkStore;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BookmarkPageRenderer} (F4, B2–B5: the URL space of
 * the manager page, the actions, and the S9 script-safety of the island).
 */
class BookmarkPageRendererTest {

    private static final String ISLAND_OPEN =
            "<script id=\"signum-data\" type=\"application/json\">";

    private final Gson gson = new Gson();
    private BookmarkStore store;
    private BookmarkPageRenderer renderer;

    @BeforeEach
    void setUp(@TempDir Path dir) {
        store = new BookmarkStore(dir.resolve("bookmarks.json"));
        renderer = new BookmarkPageRenderer(store);
    }

    /** Parses the injected JSON data island out of a rendered page. */
    private JsonObject island(byte[] page) {
        String html = new String(page, StandardCharsets.UTF_8);
        int start = html.indexOf(ISLAND_OPEN) + ISLAND_OPEN.length();
        int end = html.indexOf("</script>", start);
        assertTrue(end > start, "the rendered page must carry the data island");
        return gson.fromJson(html.substring(start, end), JsonObject.class);
    }

    private JsonArray nodes(JsonObject island) {
        return island.getAsJsonArray("nodes");
    }

    private JsonObject nodeById(JsonObject island, String id) {
        for (int i = 0; i < nodes(island).size(); i++) {
            JsonObject node = nodes(island).get(i).getAsJsonObject();
            if (node.get("id").getAsString().equals(id)) {
                return node;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // Listing (B5)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("the listing injects the flat tree (with parents), the bar and the i18n strings")
    void listingInjectsTreeAndStrings() {
        String f1 = store.newFolder(BookmarkStore.ROOT_ID, "Web3");
        String b1 = store.newBookmark(BookmarkStore.ROOT_ID, "Alpha", "https://alpha.example");
        String b2 = store.newBookmark(f1, "Beta", "https://beta.example");
        store.setInBar(b2, true);

        byte[] page = renderer.render("signum://bookmarks", Map.of());

        assertNotNull(page);
        String html = new String(page, StandardCharsets.UTF_8);
        assertFalse(html.contains("__SIGNUM_DATA__"));
        JsonObject island = island(page);
        assertEquals(3, nodes(island).size());
        assertEquals("bookmark", nodeById(island, b1).get("type").getAsString());
        assertEquals("https://alpha.example", nodeById(island, b1).get("url").getAsString());
        assertEquals(BookmarkStore.ROOT_ID, nodeById(island, b1).get("parent").getAsString());
        assertEquals("folder", nodeById(island, f1).get("type").getAsString());
        assertEquals(f1, nodeById(island, b2).get("parent").getAsString());
        assertEquals(1, island.getAsJsonArray("bar").size());
        assertEquals(b2, island.getAsJsonArray("bar").get(0).getAsString());
        assertTrue(island.getAsJsonObject("strings").entrySet().size() >= 14);
    }

    // ------------------------------------------------------------------
    // Actions (B2/B3)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("bookmarks/add stores a valid bookmark and re-renders the listing")
    void addActionAddsBookmark() {
        JsonObject page = island(renderer.render("signum://bookmarks/add",
                Map.of("name", "Signum Explorer", "url", "https://explorer.signum.network")));

        assertEquals(1, nodes(page).size());
        JsonObject node = nodes(page).get(0).getAsJsonObject();
        assertEquals("Signum Explorer", node.get("name").getAsString());
        assertEquals("https://explorer.signum.network", node.get("url").getAsString());
        assertEquals(1, store.findByUrl("https://explorer.signum.network").size());
    }

    @Test
    @DisplayName("bookmarks/add ignores blank names or urls (and unknown folders)")
    void addActionValidatesInputs() {
        assertEquals(0, nodes(island(renderer.render("signum://bookmarks/add",
                Map.of("name", "  ", "url", "https://a.example")))).size());
        assertEquals(0, nodes(island(renderer.render("signum://bookmarks/add",
                Map.of("name", "A", "url", "   ")))).size());
        assertEquals(0, nodes(island(renderer.render("signum://bookmarks/add",
                Map.of("name", "A", "url", "https://a.example", "folder", "nope")))).size());
        assertEquals(0, store.count());
    }

    @Test
    @DisplayName("bookmarks/folder creates a (nested) folder node")
    void folderActionCreatesNestedFolder() {
        String f1 = store.newFolder(BookmarkStore.ROOT_ID, "Top");

        JsonObject page = island(renderer.render("signum://bookmarks/folder",
                Map.of("name", "Nested", "folder", f1)));

        JsonObject node = nodes(page).get(1).getAsJsonObject();
        assertEquals("folder", node.get("type").getAsString());
        assertEquals("Nested", node.get("name").getAsString());
        assertEquals(f1, node.get("parent").getAsString());
        assertEquals(2, store.count());
    }
    @Test
    @DisplayName("bookmarks/rename and bookmarks/move mutate the tree (invalid moves are no-ops)")
    void renameAndMoveActions() {
        String f1 = store.newFolder(BookmarkStore.ROOT_ID, "F1");
        String b1 = store.newBookmark(BookmarkStore.ROOT_ID, "A", "https://a.example");

        JsonObject page = island(renderer.render("signum://bookmarks/rename",
                Map.of("id", b1, "name", "Renamed")));
        assertEquals("Renamed", nodeById(page, b1).get("name").getAsString());

        // invalid: the node's own (descendant) folder — rejected server-side
        String f2 = store.newFolder(f1, "F2");
        JsonObject noMove = island(renderer.render("signum://bookmarks/move",
                Map.of("id", f1, "target", f2)));
        assertEquals(BookmarkStore.ROOT_ID, nodeById(noMove, f1).get("parent").getAsString());

        JsonObject moved = island(renderer.render("signum://bookmarks/move",
                Map.of("id", b1, "target", f1)));
        assertEquals(f1, nodeById(moved, b1).get("parent").getAsString());
    }

    @Test
    @DisplayName("bookmarks/delete removes the node's whole subtree")
    void deleteActionRemovesSubtree() {
        String f1 = store.newFolder(BookmarkStore.ROOT_ID, "F1");
        store.newBookmark(f1, "Inside", "https://inside.example");
        assertEquals(2, store.count());

        JsonObject page = island(renderer.render("signum://bookmarks/delete",
                Map.of("id", f1)));

        assertEquals(0, nodes(page).size());
        assertEquals(0, store.count());
    }

    @Test
    @DisplayName("bookmarks/bar toggles the bookmarks-bar membership (B4)")
    void barActionToggles() {
        String b1 = store.newBookmark(BookmarkStore.ROOT_ID, "A", "https://a.example");

        JsonObject on = island(renderer.render("signum://bookmarks/bar",
                Map.of("id", b1, "on", "1")));
        assertEquals(1, on.getAsJsonArray("bar").size());
        assertEquals(b1, on.getAsJsonArray("bar").get(0).getAsString());

        JsonObject off = island(renderer.render("signum://bookmarks/bar",
                Map.of("id", b1, "on", "0")));
        assertEquals(0, off.getAsJsonArray("bar").size());
        assertFalse(store.isInBar(b1));
    }

    // ------------------------------------------------------------------
    // S9: script safety of the data island
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a hostile bookmark name cannot break out of the script tag")
    void hostileNameIsEscaped() {
        String id = store.newBookmark(BookmarkStore.ROOT_ID,
                "</script><script>alert(1)</script>", "https://evil.example");
        assertNotNull(id);

        String html = new String(renderer.render("signum://bookmarks", Map.of()),
                StandardCharsets.UTF_8);
        assertFalse(html.contains("</script><script>alert(1)</script>"));
        assertTrue(html.contains("\\u003c/script\\u003e"));
        // the escaped island still parses and the name round-trips
        assertEquals("</script><script>alert(1)</script>",
                nodeById(island(renderer.render("signum://bookmarks", Map.of())), id)
                        .get("name").getAsString());
    }

    @Test
    @DisplayName("an unknown bookmarks sub-path renders nothing (the 404 fallback wins)")
    void unknownActionReturnsNull() {
        assertNull(renderer.render("signum://bookmarks/bogus", Map.of()));
    }
}