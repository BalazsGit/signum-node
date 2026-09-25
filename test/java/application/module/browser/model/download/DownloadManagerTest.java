package application.module.browser.model.download;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link DownloadManager} (F5, D1–D2: collision suffixes,
 * the state machine, cancellation, persistence and the D17 degradation
 * rules).
 */
class DownloadManagerTest {

    private DownloadManager managerIn(Path dir) {
        return new DownloadManager(dir.resolve("downloads.json"), () -> dir);
    }

    private static DownloadItem complete(DownloadManager manager, DownloadItem item) {
        return manager.update(item.getId(), item.getTotalBytes(), item.getTotalBytes(),
                100, 0, false, true, false);
    }

    // ------------------------------------------------------------------
    // D1: target paths
    // ------------------------------------------------------------------

    @Test
    @DisplayName("D1: a free name lands in the configured directory untouched")
    void registerResolvesFreeName(@TempDir Path dir) throws IOException {
        DownloadManager manager = managerIn(dir);
        DownloadItem item = manager.register("https://x.example/a/report.pdf", "report.pdf", 1);
        assertEquals(dir.resolve("report.pdf").toString(), item.getTargetPath());
        assertEquals(DownloadItem.State.PENDING, item.getState());
        assertEquals("https://x.example/a/report.pdf", item.getUrl());
        assertTrue(item.getStartedAt() > 0);
    }

    @Test
    @DisplayName("D1: a name collision gets the ' (1)' suffix")
    void registerAppliesCollisionSuffix(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("report.pdf"));
        DownloadManager manager = managerIn(dir);
        DownloadItem item = manager.register("https://x.example/report.pdf", "report.pdf", 1);
        assertEquals(dir.resolve("report (1).pdf").toString(), item.getTargetPath());
    }

    @Test
    @DisplayName("D1: the collision chain continues over existing suffixed names")
    void registerContinuesCollisionChain(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("report.pdf"));
        Files.createFile(dir.resolve("report (1).pdf"));
        Files.createFile(dir.resolve("report (2).pdf"));
        DownloadManager manager = managerIn(dir);
        DownloadItem item = manager.register("https://x.example/report.pdf", "report.pdf", 1);
        assertEquals(dir.resolve("report (3).pdf").toString(), item.getTargetPath());
    }

    @Test
    @DisplayName("D1: a collision for an extension-less name appends the suffix to the name")
    void registerCollidesExtensionless(@TempDir Path dir) throws IOException {
        Files.createFile(dir.resolve("archive"));
        DownloadManager manager = managerIn(dir);
        DownloadItem item = manager.register("https://x.example/archive", "archive", 1);
        assertEquals(dir.resolve("archive (1)").toString(), item.getTargetPath());
    }

    @Test
    @DisplayName("D1: the download directory is created when missing")
    void registerCreatesMissingDirectory(@TempDir Path dir) {
        Path sub = dir.resolve("nested").resolve("downloads");
        DownloadManager manager = new DownloadManager(dir.resolve("downloads.json"), () -> sub);
        DownloadItem item = manager.register("https://x.example/a.txt", "a.txt", 1);
        assertTrue(Files.isDirectory(sub));
        assertEquals(sub.resolve("a.txt").toString(), item.getTargetPath());
    }

    @Test
    @DisplayName("D1: suggested names are sanitized (paths, invalid characters, empty)")
    void sanitizeNameStripsPathsAndInvalidCharacters() {
        assertEquals("c_d_e.pdf", DownloadManager.sanitizeName("a/b\\c:d*e.pdf"));
        assertEquals("report.pdf", DownloadManager.sanitizeName("C:\\temp\\report.pdf"));
        assertEquals("download", DownloadManager.sanitizeName(null));
        assertEquals("download", DownloadManager.sanitizeName("   "));
        assertEquals("download", DownloadManager.sanitizeName("???"));
        String longName = "x".repeat(300) + ".pdf";
        assertEquals(180, DownloadManager.sanitizeName(longName).length());
    }

    // ------------------------------------------------------------------
    // D2: state machine
    // ------------------------------------------------------------------

    @Test
    @DisplayName("D2: pending → in-progress → complete, with finishedAt stamped")
    void updateGoesThroughStates(@TempDir Path dir) {
        DownloadManager manager = managerIn(dir);
        DownloadItem item = manager.register("https://x.example/a.bin", "a.bin", 1);
        DownloadItem mid = manager.update(item.getId(), 500, 1000, 50, 250, true, false, false);
        assertEquals(DownloadItem.State.IN_PROGRESS, mid.getState());
        assertEquals(500, mid.getReceivedBytes());
        assertEquals(250, mid.getSpeedBps());
        DownloadItem done = complete(manager, mid);
        assertEquals(DownloadItem.State.COMPLETE, done.getState());
        assertTrue(done.getFinishedAt() > 0);
        assertEquals(100, done.getPercentComplete());
    }

    @Test
    @DisplayName("D2: cancellation (leallás) lands in the canceled state")
    void updateCanceledIsCanceled(@TempDir Path dir) {
        DownloadManager manager = managerIn(dir);
        DownloadItem item = manager.register("https://x.example/a.bin", "a.bin", 1);
        manager.update(item.getId(), 10, 100, 10, 0, true, false, false);
        DownloadItem canceled = manager.update(item.getId(), 10, 100, 10, 0, false, false, true);
        assertEquals(DownloadItem.State.CANCELED, canceled.getState());
        assertTrue(canceled.getFinishedAt() > 0);
    }

    @Test
    @DisplayName("D2: a transfer that terminates without complete/canceled fails")
    void updateAfterInProgressFails(@TempDir Path dir) {
        DownloadManager manager = managerIn(dir);
        DownloadItem item = manager.register("https://x.example/a.bin", "a.bin", 1);
        manager.update(item.getId(), 10, 100, 10, 0, true, false, false);
        DownloadItem failed = manager.update(item.getId(), 10, 100, 10, 0, false, false, false);
        assertEquals(DownloadItem.State.FAILED, failed.getState());
        assertTrue(failed.getFinishedAt() > 0);
    }

    @Test
    @DisplayName("D2: the first update before the transfer starts stays pending")
    void firstUpdateBeforeTransferStaysPending(@TempDir Path dir) {
        DownloadManager manager = managerIn(dir);
        DownloadItem item = manager.register("https://x.example/a.bin", "a.bin", 1);
        DownloadItem pending = manager.update(item.getId(), 0, 0, 0, 0, false, false, false);
        assertEquals(DownloadItem.State.PENDING, pending.getState());
        assertEquals(0, pending.getFinishedAt());
    }

    @Test
    @DisplayName("D2: updates are clamped to 0..100 percent")
    void updateClampsPercent(@TempDir Path dir) {
        DownloadManager manager = managerIn(dir);
        DownloadItem item = manager.register("https://x.example/a.bin", "a.bin", 1);
        assertEquals(100, manager.update(item.getId(), 1, 1, 250, 0, true, false, false)
                .getPercentComplete());
        assertEquals(0, manager.update(item.getId(), 1, 1, -5, 0, true, false, false)
                .getPercentComplete());
    }

    @Test
    @DisplayName("D2: an unknown id is a no-op (null result)")
    void updateUnknownIdReturnsNull(@TempDir Path dir) {
        DownloadManager manager = managerIn(dir);
        assertNull(manager.update("nope", 1, 1, 1, 1, true, false, false));
        assertNull(manager.remove("nope"));
        assertNull(manager.find("nope"));
    }

    // ------------------------------------------------------------------
    // Removal + listeners
    // ------------------------------------------------------------------

    @Test
    @DisplayName("remove drops the item (the file on disk is kept) and persists")
    void removeDropsItemAndPersists(@TempDir Path dir) throws IOException {
        DownloadManager manager = managerIn(dir);
        DownloadItem item = manager.register("https://x.example/a.bin", "a.bin", 1);
        complete(manager, item);
        Files.createFile(dir.resolve("a.bin"));
        assertNotNull(manager.remove(item.getId()));
        assertTrue(manager.items().isEmpty());
        assertTrue(Files.isRegularFile(dir.resolve("a.bin"))); // the file stays
        DownloadManager reloaded = managerIn(dir);
        assertTrue(reloaded.items().isEmpty());
    }

    @Test
    @DisplayName("listeners receive the snapshot on update and remove")
    void listenersAreNotified(@TempDir Path dir) {
        DownloadManager manager = managerIn(dir);
        AtomicReference<List<DownloadItem>> seen = new AtomicReference<>();
        manager.addListener(seen::set);
        DownloadItem item = manager.register("https://x.example/a.bin", "a.bin", 1);
        complete(manager, item);
        assertNotNull(seen.get());
        assertEquals(1, seen.get().size());
        assertEquals(DownloadItem.State.COMPLETE, seen.get().get(0).getState());
        manager.remove(item.getId());
        assertTrue(seen.get().isEmpty());
    }

    // ------------------------------------------------------------------
    // Persistence (downloads.json)
    // ------------------------------------------------------------------

    @Test
    @DisplayName("terminal states are persisted and a new manager round-trips them")
    void persistenceRoundTrips(@TempDir Path dir) {
        DownloadManager manager = managerIn(dir);
        DownloadItem a = manager.register("https://x.example/a.bin", "a.bin", 1);
        complete(manager, a);
        DownloadItem b = manager.register("https://x.example/b.bin", "b.bin", 2);
        manager.update(b.getId(), 1, 2, 50, 0, false, false, true); // canceled
        manager.close();
        assertTrue(Files.isRegularFile(dir.resolve("downloads.json")));

        DownloadManager reloaded = managerIn(dir);
        assertEquals(2, reloaded.items().size());
        assertEquals(DownloadItem.State.COMPLETE, reloaded.find(a.getId()).getState());
        assertEquals(DownloadItem.State.CANCELED, reloaded.find(b.getId()).getState());
        assertEquals(a.getTargetPath(), reloaded.find(a.getId()).getTargetPath());
    }

    @Test
    @DisplayName("the recent list is capped at MAX_RECENT (oldest dropped)")
    void recentListIsCapped(@TempDir Path dir) {
        DownloadManager manager = managerIn(dir);
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < DownloadManager.MAX_RECENT + 10; i++) {
            DownloadItem item = manager.register("https://x.example/" + i + ".bin",
                    "f" + i + ".bin", i);
            complete(manager, item);
            ids.add(item.getId());
        }
        manager.close();
        DownloadManager reloaded = managerIn(dir);
        assertEquals(DownloadManager.MAX_RECENT, reloaded.items().size());
        assertFalse(reloaded.items().stream()
                .anyMatch(i -> i.getId().equals(ids.get(0))));
        assertTrue(reloaded.items().stream()
                .anyMatch(i -> i.getId().equals(ids.get(ids.size() - 1))));
    }

    @Test
    @DisplayName("D17: a corrupt downloads file degrades to the empty list (no throw)")
    void corruptFileDegradesToEmpty(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("downloads.json"), "{ this is not json", StandardCharsets.UTF_8);
        DownloadManager manager = managerIn(dir);
        assertTrue(manager.items().isEmpty());
    }

    @Test
    @DisplayName("D17: unresolvable and duplicate records are dropped on load")
    void loadDropsBrokenRecords(@TempDir Path dir) throws IOException {
        Path y = dir.resolve("x").resolve("y.bin");
        Path z = dir.resolve("x").resolve("z.bin");
        com.google.gson.Gson gson = new com.google.gson.Gson();
        List<Map<String, String>> records = new ArrayList<>();
        records.add(Map.of("id", "a", "targetPath", ""));
        records.add(Map.of("id", "b", "targetPath", y.toString(), "state", "IN_PROGRESS"));
        records.add(Map.of("id", "b", "targetPath", y.toString(), "state", "IN_PROGRESS"));
        records.add(Map.of("id", "c", "targetPath", z.toString(), "state", "IN_PROGRESS"));
        Files.writeString(dir.resolve("downloads.json"),
                gson.toJson(Map.of("version", 1, "items", records)), StandardCharsets.UTF_8);
        DownloadManager manager = managerIn(dir);
        assertEquals(2, manager.items().size()); // a dropped, the duplicate b dropped
        // The process restarted: y.bin exists → complete, z.bin missing → failed
        Files.createDirectories(y.getParent());
        Files.createFile(y);
        DownloadManager reloaded = managerIn(dir);
        assertEquals(DownloadItem.State.COMPLETE, reloaded.find("b").getState());
        assertEquals(DownloadItem.State.FAILED, reloaded.find("c").getState());
    }
}