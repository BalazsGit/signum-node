package application.module.browser.model.history;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link HistoryStore} (F3, H1–H3: capture, search, deletion,
 * D17 degradation).
 */
class HistoryStoreTest {

    private static final long T0 = 1_700_000_000_000L;

    private static HistoryStore storeIn(Path dir) {
        return new HistoryStore(dir.resolve("history.db"));
    }

    // ------------------------------------------------------------------
    // Capture (H1)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("recordable urls: web + internal pages, nothing else")
    void isRecordableRules() {
        assertTrue(HistoryStore.isRecordable("https://example.com"));
        assertTrue(HistoryStore.isRecordable("http://example.com:8080/x?q=1"));
        assertTrue(HistoryStore.isRecordable("signum://history"));
        assertFalse(HistoryStore.isRecordable("signum://newtab"));
        assertFalse(HistoryStore.isRecordable("signum://cert-continue/abc"));
        assertFalse(HistoryStore.isRecordable("signum://history/delete?id=1"));
        assertFalse(HistoryStore.isRecordable("signum://history/clear"));
        assertFalse(HistoryStore.isRecordable("file:///C:/x.txt"));
        assertFalse(HistoryStore.isRecordable("about:blank"));
        assertFalse(HistoryStore.isRecordable(""));
        assertFalse(HistoryStore.isRecordable(null));
    }

    @Test
    @DisplayName("record + flush persists url, title, referrer and the visit counter")
    void recordPersists(@TempDir Path dir) {
        try (HistoryStore store = storeIn(dir)) {
            store.record("https://example.com/page", "Example", T0, "https://referrer.example/");
            store.flush();

            List<HistoryEntry> rows = store.search(null, 0L, Long.MAX_VALUE, 0);
            assertEquals(1, rows.size());
            HistoryEntry entry = rows.get(0);
            assertEquals("https://example.com/page", entry.getUrl());
            assertEquals("Example", entry.getTitle());
            assertEquals(T0, entry.getVisitTs());
            assertEquals("https://referrer.example/", entry.getReferrer());
            assertEquals(1, entry.getVisits());
            assertEquals(1L, store.count());
        }
    }

    @Test
    @DisplayName("a same-millisecond reload coalesces into the visits counter (no duplicate row)")
    void sameTimestampCoalesces(@TempDir Path dir) {
        try (HistoryStore store = storeIn(dir)) {
            store.record("https://example.com", "Example", T0, null);
            store.record("https://example.com", "Example again", T0, null);
            store.flush();

            List<HistoryEntry> rows = store.search(null, 0L, Long.MAX_VALUE, 0);
            assertEquals(1, rows.size());
            assertEquals(2, rows.get(0).getVisits());
            assertEquals("Example", rows.get(0).getTitle()); // the first title wins
        }
    }

    @Test
    @DisplayName("non-recordable urls and non-positive timestamps are dropped")
    void nonRecordableIsDropped(@TempDir Path dir) {
        try (HistoryStore store = storeIn(dir)) {
            store.record("signum://newtab", "NTP", T0, null);
            store.record("about:blank", "Blank", T0, null);
            store.record("https://example.com", "Bad ts", 0L, null);
            store.flush();
            assertEquals(0L, store.count());
        }
    }

    @Test
    @DisplayName("64 queued visits flush synchronously (the batch threshold)")
    void thresholdFlushesSynchronously(@TempDir Path dir) {
        try (HistoryStore store = storeIn(dir)) {
            for (int i = 0; i < 64; i++) {
                store.record("https://bulk.example/" + i, "B" + i, T0 + i, null);
            }
            assertEquals(64L, store.count());
        }
    }
    // ------------------------------------------------------------------
    // Search (H2)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("search: substring on url/title, time bounds, limit, newest first")
    void searchFilters(@TempDir Path dir) {
        try (HistoryStore store = storeIn(dir)) {
            store.record("https://alpha.example/one", "Alpha", T0, null);
            store.record("https://beta.example/two", "Notes about beta", T0 + 1000, null);
            store.record("https://gamma.example", "Gamma", T0 + 2000, null);
            store.flush();

            assertEquals(3, store.search(null, 0L, Long.MAX_VALUE, 0).size());
            assertEquals(1, store.search("ALPHA", 0L, Long.MAX_VALUE, 0).size());
            assertEquals(1, store.search("notes about", 0L, Long.MAX_VALUE, 0).size());
            assertEquals(1, store.search(null, T0 + 1500, Long.MAX_VALUE, 0).size());
            assertEquals(2, store.search(null, 0L, T0 + 1500, 0).size());

            List<HistoryEntry> top2 = store.search(null, 0L, Long.MAX_VALUE, 2);
            assertEquals(2, top2.size());
            assertEquals("https://gamma.example", top2.get(0).getUrl());
            assertEquals("https://beta.example/two", top2.get(1).getUrl());
        }
    }

    @Test
    @DisplayName("LIKE wildcards in the query are treated as literals")
    void likeWildcardsAreLiterals(@TempDir Path dir) {
        try (HistoryStore store = storeIn(dir)) {
            // %25 is a literal '%' in the path: "100%" matches it when the
            // pattern's % is escaped, but not the plain sibling
            store.record("https://example.com/100%25-off", "Discount", T0, null);
            store.record("https://example.com/100x25", "Plain", T0 + 1, null);
            store.flush();

            List<HistoryEntry> rows = store.search("100%", 0L, Long.MAX_VALUE, 0);
            assertEquals(1, rows.size());
            assertEquals("https://example.com/100%25-off", rows.get(0).getUrl());
        }
    }

    // ------------------------------------------------------------------
    // Deletion (H3)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("delete removes one row (unknown ids delete nothing); clear removes all")
    void deleteAndClear(@TempDir Path dir) {
        try (HistoryStore store = storeIn(dir)) {
            store.record("https://a.example", "A", T0, null);
            store.record("https://b.example", "B", T0 + 1, null);
            store.flush();

            long idA = store.search("a.example", 0L, Long.MAX_VALUE, 1).get(0).getId();
            assertEquals(1, store.delete(idA));
            assertEquals(0, store.delete(idA)); // already gone
            assertEquals(0, store.delete(999)); // unknown id
            assertEquals(1L, store.count());

            assertEquals(1, store.clear());
            assertEquals(0L, store.count());
        }
    }

    // ------------------------------------------------------------------
    // Degradation + lifecycle (D17)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("an unopenable database degrades to a no-op (never throws)")
    void unopenableDatabaseDegrades(@TempDir Path dir) throws IOException {
        Path blocker = dir.resolve("nested");
        Files.writeString(blocker, "i am a file, not a directory");

        try (HistoryStore store = new HistoryStore(blocker.resolve("history.db"))) {
            store.record("https://example.com", "X", T0, null);
            store.flush();
            assertTrue(store.search(null, 0L, Long.MAX_VALUE, 0).isEmpty());
            assertEquals(0L, store.count());
            assertEquals(0, store.clear());
        }
    }

    @Test
    @DisplayName("close() drains the queue, is idempotent and makes the store inert")
    void closeDrainsAndDisables(@TempDir Path dir) throws Exception {
        Path dbFile = dir.resolve("history.db");
        HistoryStore store = new HistoryStore(dbFile);
        store.record("https://example.com", "X", T0, null);
        store.close();
        store.close();
        store.record("https://late.example", "Late", T0 + 1, null);
        store.flush();
        assertEquals(0, store.search("late", 0L, Long.MAX_VALUE, 0).size());

        // the queued visit was persisted by close()
        try (Connection connection =
                     DriverManager.getConnection("jdbc:sqlite:" + dbFile.toAbsolutePath());
             Statement statement = connection.createStatement();
             ResultSet rs = statement.executeQuery("SELECT COUNT(*) FROM history")) {
            assertTrue(rs.next());
            assertEquals(1, rs.getInt(1));
        }
    }
}