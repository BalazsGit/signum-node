package application.module.browser.model.session;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SessionStore} (T8 persistence).
 */
class SessionStoreTest {

    private SessionSnapshot snapshotOf(int activeIndex, String... urls) {
        SessionSnapshot snapshot = new SessionSnapshot();
        snapshot.setActiveIndex(activeIndex);
        for (String url : urls) {
            snapshot.addTab(url, "T:" + url);
        }
        return snapshot;
    }

    @Test
    @DisplayName("a missing session file loads as empty (first start)")
    void missingFileLoadsEmpty(@TempDir Path dir) {
        SessionStore store = new SessionStore(dir.resolve("session.json"));

        assertTrue(store.load().isEmpty());
    }

    @Test
    @DisplayName("save then load round-trips urls, titles and the active index")
    void saveLoadRoundTrip(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("session.json");
        SessionStore store = new SessionStore(file);
        store.save(snapshotOf(1, "https://a.example", "https://b.example"));

        SessionSnapshot loaded = store.load().orElseThrow();

        assertTrue(Files.isRegularFile(file));
        assertEquals(2, loaded.getTabs().size());
        assertEquals("https://b.example", loaded.getTabs().get(1).getUrl());
        assertEquals("T:https://b.example", loaded.getTabs().get(1).getTitle());
        assertEquals(1, loaded.getActiveIndex());
    }

    @Test
    @DisplayName("a corrupt session file loads as empty (never fatal)")
    void corruptFileLoadsEmpty(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("session.json");
        Files.writeString(file, "{ this is not json ]", StandardCharsets.UTF_8);

        assertTrue(new SessionStore(file).load().isEmpty());
    }

    @Test
    @DisplayName("an empty session file (no tabs) loads as empty")
    void emptySessionLoadsEmpty(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("session.json");
        Files.writeString(file, "{\"version\":1,\"activeIndex\":0,\"tabs\":[]}", StandardCharsets.UTF_8);

        assertTrue(new SessionStore(file).load().isEmpty());
    }

    @Test
    @DisplayName("a saved file supersedes the previous one without temp leftovers")
    void saveReplacesAtomically(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("session.json");
        SessionStore store = new SessionStore(file);
        store.save(snapshotOf(0, "https://a.example"));
        store.save(snapshotOf(0, "https://x.example", "https://y.example"));

        SessionSnapshot loaded = store.load().orElseThrow();
        assertEquals(2, loaded.getTabs().size());
        assertEquals("https://x.example", loaded.getTabs().get(0).getUrl());
        assertTrue(Files.list(dir).noneMatch(p -> p.toString().endsWith(".tmp")));
    }

    @Test
    @DisplayName("an out-of-range saved active index is clamped on load")
    void activeIndexIsClamped(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("session.json");
        SessionStore store = new SessionStore(file);
        store.save(snapshotOf(99, "https://a.example"));

        assertEquals(0, store.load().orElseThrow().getActiveIndex());
    }
}