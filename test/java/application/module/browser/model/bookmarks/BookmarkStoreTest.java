package application.module.browser.model.bookmarks;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link BookmarkStore} (F4, B1–B5: the JSON tree, mutations,
 * queries, bar membership and the D17 degradation rules).
 */
class BookmarkStoreTest {

    private BookmarkStore storeIn(Path dir) {
        return new BookmarkStore(dir.resolve("bookmarks.json"));
    }

    // ------------------------------------------------------------------
    // Shape + persistence
    // ------------------------------------------------------------------

    @Test
    @DisplayName("a fresh store holds only the (undeletable) root folder")
    void freshStoreHasOnlyRoot(@TempDir Path dir) {
        BookmarkStore store = storeIn(dir);
        assertTrue(store.get(BookmarkStore.ROOT_ID).isPresent());
        assertTrue(store.get(BookmarkStore.ROOT_ID).get().isFolder());
        assertEquals(0, store.count());
        assertTrue(store.children(BookmarkStore.ROOT_ID).isEmpty());
        assertTrue(store.search(null).isEmpty());
        assertTrue(store.barItems().isEmpty());
    }

    @Test
    @DisplayName("save + a new store on the same file round-trips the tree and the bar")
    void saveAndReloadRoundTrips(@TempDir Path dir) {
        Path file = dir.resolve("bookmarks.json");
        String folder;
        String a;
        String b;
        BookmarkStore store = storeIn(dir);
        folder = store.newFolder(BookmarkStore.ROOT_ID, "Web3");
        a = store.newBookmark(BookmarkStore.ROOT_ID, "Alpha", "https://alpha.example");
        b = store.newBookmark(folder, "Beta", "https://beta.example");
        store.setInBar(b, true);
        store.setInBar(a, true);
        store.save();
        assertTrue(Files.isRegularFile(file));

        try (BookmarkStore reloaded = new BookmarkStore(file)) {
            assertEquals(3, reloaded.count());
            assertEquals("https://alpha.example",
                    reloaded.get(a).orElseThrow().getUrl());
            assertEquals(List.of(folder, a),
                    reloaded.children(BookmarkStore.ROOT_ID).stream().map(Bookmark::getId).toList());
            assertEquals(List.of(b),
                    reloaded.children(folder).stream().map(Bookmark::getId).toList());
            assertEquals(List.of(b, a),
                    reloaded.barItems().stream().map(Bookmark::getId).toList());
            assertEquals(BookmarkStore.ROOT_ID, reloaded.parentOf(a).orElse(""));
            assertEquals(folder, reloaded.parentOf(b).orElse(""));
        }
    }

    @Test
    @DisplayName("a corrupt file degrades to the empty tree and save() writes a valid file again")
    void corruptFileFallsBackToDefaults(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("bookmarks.json");
        Files.writeString(file, "{ this is not json !!!");

        try (BookmarkStore store = new BookmarkStore(file)) {
            assertEquals(0, store.count());
            String id = store.newBookmark(BookmarkStore.ROOT_ID, "A", "https://a.example");
            assertNotNull(id);
            store.save();
        }
        try (BookmarkStore reloaded = new BookmarkStore(file)) {
            assertEquals(1, reloaded.count());
        }
    }

    @Test
    @DisplayName("unreachable nodes, ghost child references and ghost bar ids are dropped on load")
    void sanitizeDropsOrphans(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("bookmarks.json");
        Files.writeString(file, """
                { "version": 1,
                  "bar": ["b1", "b2", "root"],
                  "nodes": {
                    "root": { "id": "root", "type": "folder", "name": "", "children": ["b1", "f1"] },
                    "b1": { "id": "b1", "type": "bookmark", "name": "Live", "url": "https://a.example" },
                    "b2": { "id": "b2", "type": "bookmark", "name": "Orphan", "url": "https://b.example" },
                    "f1": { "id": "f1", "type": "folder", "name": "F", "children": ["ghost", "ghost"] }
                  } }
                """);

        try (BookmarkStore store = new BookmarkStore(file)) {
            assertEquals(2, store.count()); // b2 pruned (unreachable)
            assertEquals(List.of("b1", "f1"),
                    store.children(BookmarkStore.ROOT_ID).stream().map(Bookmark::getId).toList());
            assertTrue(store.children("f1").isEmpty()); // ghost children dropped
            assertEquals(List.of("b1"),
                    store.barItems().stream().map(Bookmark::getId).toList()); // b2 + root gone
        }
    }
    // ------------------------------------------------------------------
    // Mutations (B2/B3)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("newBookmark/newFolder validate the parent, the name and the url")
    void addValidation(@TempDir Path dir) {
        try (BookmarkStore store = storeIn(dir)) {
            assertNull(store.newBookmark(BookmarkStore.ROOT_ID, "  ", "https://a.example"));
            assertNull(store.newBookmark(BookmarkStore.ROOT_ID, "A", "   "));
            assertNull(store.newBookmark("no-such-folder", "A", "https://a.example"));
            assertNull(store.newFolder(BookmarkStore.ROOT_ID, ""));
            assertNull(store.newFolder("no-such-folder", "F"));

            String folder = store.newFolder(BookmarkStore.ROOT_ID, "F1");
            String nested = store.newFolder(folder, "  F2  "); // nesting, B3
            String id = store.newBookmark(nested, "  A ", " https://a.example ");
            assertNotNull(id);
            assertEquals("F2", store.get(nested).orElseThrow().getName()); // trimmed
            assertEquals("https://a.example", store.get(id).orElseThrow().getUrl());
            assertEquals(List.of(id),
                    store.children(nested).stream().map(Bookmark::getId).toList());
            assertEquals(List.of(nested),
                    store.children(folder).stream().map(Bookmark::getId).toList());
            assertEquals(List.of(folder),
                    store.children(BookmarkStore.ROOT_ID).stream().map(Bookmark::getId).toList());
        }
    }

    @Test
    @DisplayName("rename accepts only live, non-root nodes with a non-blank name")
    void renameRules(@TempDir Path dir) {
        try (BookmarkStore store = storeIn(dir)) {
            String id = store.newBookmark(BookmarkStore.ROOT_ID, "A", "https://a.example");
            assertFalse(store.rename("no-such-id", "X"));
            assertFalse(store.rename(id, "  "));
            assertFalse(store.rename(BookmarkStore.ROOT_ID, "X"));
            assertTrue(store.rename(id, "  Renamed  "));
            assertEquals("Renamed", store.get(id).orElseThrow().getName());
        }
    }
    @Test
    @DisplayName("move keeps the tree acyclic and appends to the target's end")
    void moveRules(@TempDir Path dir) {
        try (BookmarkStore store = storeIn(dir)) {
            String f1 = store.newFolder(BookmarkStore.ROOT_ID, "F1");
            String f2 = store.newFolder(f1, "F2"); // F1/F2
            String a = store.newBookmark(BookmarkStore.ROOT_ID, "A", "https://a.example");
            String b = store.newBookmark(f1, "B", "https://b.example");

            assertFalse(store.move("no-such-id", f1));
            assertFalse(store.move(a, "no-such-folder"));
            assertFalse(store.move(a, a));
            assertFalse(store.move(BookmarkStore.ROOT_ID, f1));
            assertFalse(store.move(a, b)); // a bookmark is not a folder
            assertFalse(store.move(f1, f2)); // f2 is inside f1 — cycle

            assertTrue(store.move(a, f2));
            assertEquals(List.of(f2, b),
                    store.children(f1).stream().map(Bookmark::getId).toList());
            assertEquals(List.of(f1),
                    store.children(BookmarkStore.ROOT_ID).stream().map(Bookmark::getId).toList());
            assertEquals(f2, store.parentOf(a).orElse(""));
        }
    }

    @Test
    @DisplayName("delete removes the whole subtree and the bar entries; root is delete-proof")
    void deleteRemovesSubtree(@TempDir Path dir) {
        try (BookmarkStore store = storeIn(dir)) {
            String f1 = store.newFolder(BookmarkStore.ROOT_ID, "F1");
            String f2 = store.newFolder(f1, "F2");
            String a = store.newBookmark(f1, "A", "https://a.example");
            String b = store.newBookmark(f2, "B", "https://b.example");
            store.setInBar(a, true);
            store.setInBar(b, true);
            assertEquals(4, store.count());

            assertEquals(0, store.delete("no-such-id"));
            assertEquals(0, store.delete(BookmarkStore.ROOT_ID));
            assertEquals(4, store.delete(f1)); // F1 + A + F2 + B
            assertEquals(0, store.delete(f1)); // already gone
            assertEquals(0, store.count());
            assertTrue(store.barItems().isEmpty());
        }
    }
    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    @Test
    @DisplayName("search is a case-insensitive substring match on names and urls, in tree order")
    void searchMatchesNamesAndUrls(@TempDir Path dir) {
        try (BookmarkStore store = storeIn(dir)) {
            String f1 = store.newFolder(BookmarkStore.ROOT_ID, "Web3 Tools");
            store.newBookmark(BookmarkStore.ROOT_ID, "Alpha", "https://alpha.example");
            store.newBookmark(f1, "DEX", "https://dune.com");
            store.newBookmark(f1, "Beta", "https://beta.example/wallet");

            assertEquals(4, store.search(null).size()); // whole tree without the root
            assertEquals(List.of("DEX"),
                    store.search("dex").stream().map(Bookmark::getName).toList()); // case-insensitive
            assertEquals(3, store.search("https://").size()); // url match
            assertEquals(List.of("Web3 Tools", "DEX", "Beta", "Alpha"),
                    store.search("e").stream().map(Bookmark::getName).toList()); // name/url match, tree order
            assertTrue(store.search("nothing-matches").isEmpty());
        }
    }

    @Test
    @DisplayName("findByUrl is an exact match (several bookmarks may share a url)")
    void findByUrlExact(@TempDir Path dir) {
        try (BookmarkStore store = storeIn(dir)) {
            String a = store.newBookmark(BookmarkStore.ROOT_ID, "A1", "https://a.example");
            String b = store.newBookmark(BookmarkStore.ROOT_ID, "A2", "https://a.example/");
            assertEquals(List.of(a),
                    store.findByUrl("https://a.example").stream().map(Bookmark::getId).toList());
            assertEquals(List.of(b),
                    store.findByUrl("https://a.example/").stream().map(Bookmark::getId).toList());
            assertTrue(store.findByUrl(null).isEmpty());
        }
    }

    @Test
    @DisplayName("bar membership is idempotent and root-proof; generated ids stay unique")
    void barMembershipAndUniqueIds(@TempDir Path dir) {
        try (BookmarkStore store = storeIn(dir)) {
            Set<String> ids = new HashSet<>();
            for (int i = 0; i < 50; i++) {
                String id = store.newBookmark(BookmarkStore.ROOT_ID, "B" + i,
                        "https://example.com/" + i);
                assertNotNull(id);
                assertTrue(ids.add(id), "duplicate id generated: " + id);
            }
            String first = ids.iterator().next();
            String second = store.newBookmark(BookmarkStore.ROOT_ID, "B50",
                    "https://example.com/50");
            store.setInBar(first, true);
            store.setInBar(first, true); // idempotent
            store.setInBar(second, true);
            store.setInBar(BookmarkStore.ROOT_ID, true); // the root is ignored
            assertEquals(List.of(first, second),
                    store.barItems().stream().map(Bookmark::getId).toList());
            assertTrue(store.isInBar(first));
            store.setInBar(first, false);
            assertEquals(List.of(second),
                    store.barItems().stream().map(Bookmark::getId).toList());
            assertFalse(store.isInBar(first));
        }
    }

    // ------------------------------------------------------------------
    // Export / import (B6)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("export -> import into a fresh store round-trips the tree (B6)")
    void exportImportRoundTrip(@TempDir Path dir) {
        try (BookmarkStore source = new BookmarkStore(dir.resolve("a.json"))) {
            String folder = source.newFolder(BookmarkStore.ROOT_ID, "Work");
            String bm = source.newBookmark(folder, "Example", "https://example.com/");
            source.setInBar(bm, true);

            String json = source.exportJson();

            try (BookmarkStore target = new BookmarkStore(dir.resolve("b.json"))) {
                int imported = target.importJson(json);
                assertEquals(2, imported); // the folder + the bookmark (root not counted)

                List<Bookmark> children = target.children(BookmarkStore.ROOT_ID);
                assertEquals(1, children.size());
                Bookmark importedFolder = children.get(0);
                assertTrue(importedFolder.isFolder());
                assertEquals("Work", importedFolder.getName());
                assertNotEquals(folder, importedFolder.getId()); // remapped id

                List<Bookmark> folderChildren = target.children(importedFolder.getId());
                assertEquals(1, folderChildren.size());
                assertEquals("https://example.com/", folderChildren.get(0).getUrl());

                // the bar entry survived the remap
                assertEquals(1, target.barItems().size());
                assertEquals(folderChildren.get(0).getId(), target.barItems().get(0).getId());
            }
        }
    }

    @Test
    @DisplayName("import never duplicates existing nodes and appends to the bar (B6)")
    void importDoesNotTouchExisting(@TempDir Path dir) {
        try (BookmarkStore store = new BookmarkStore(dir.resolve("bookmarks.json"))) {
            String existing = store.newBookmark(BookmarkStore.ROOT_ID, "Existing",
                    "https://keep.example/");
            store.setInBar(existing, true);
            int before = store.search("").size();

            String foreign = """
                    {"version":1,"bar":["x1"],"nodes":{
                    "root":{"id":"root","type":"folder","name":"","children":["x1"]},
                    "x1":{"id":"x1","type":"bookmark","name":"X","url":"https://x.example/"}}}
                    """;
            assertEquals(1, store.importJson(foreign));

            assertEquals(before + 1, store.search("").size());
            // the original node is untouched
            assertTrue(store.get(existing).isPresent());
            assertEquals("https://keep.example/", store.get(existing).orElseThrow().getUrl());
            // both bar entries are present
            assertEquals(2, store.barItems().size());
            store.save();
            assertTrue(Files.exists(dir.resolve("bookmarks.json")));
        }
    }

    @Test
    @DisplayName("import rejects corrupt or empty input (D17)")
    void importRejectsGarbage(@TempDir Path dir) {
        try (BookmarkStore store = new BookmarkStore(dir.resolve("bookmarks.json"))) {
            assertEquals(0, store.importJson(null));
            assertEquals(0, store.importJson(""));
            assertEquals(0, store.importJson("{not json"));
            assertEquals(0, store.importJson("{\"nodes\":[]}"));
            assertEquals(0, store.importJson("{\"nodes\":{\"a\":{\"type\":\"bookmark\"}}}")); // no id
        }
    }
}