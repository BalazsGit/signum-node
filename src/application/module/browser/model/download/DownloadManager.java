package application.module.browser.model.download;

import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * The download manager (plan F5, D1–D2): the module's single owner of
 * download state. Chromium writes the files itself (JCEF downloads are
 * native); this manager decides <em>where</em> a file lands
 * (D1: configured base folder, name collision → {@code " (1)"} suffix) and
 * tracks the lifecycle the CEF callbacks report
 * (D2: pending → in-progress → complete / canceled / failed).
 * <p>
 * Recent items are persisted to {@code conf/browser/downloads.json}
 * (capped at {@value #MAX_RECENT}) so the shelf and the future
 * {@code signum://downloads} page (D4, P1) survive a restart.
 * <p>
 * <b>Never fatal (D17):</b> a missing or corrupt file degrades to the empty
 * list, {@link #save()} never throws (atomic temp file + move), and an
 * unwritable download directory falls back down the chain
 * (configured → {@code ~/Downloads} → the system temp dir). Pure Java — no
 * Swing, no CEF — fully unit-testable.
 */
public final class DownloadManager implements AutoCloseable {

    /** The JSON file version (Appendix C: {@code downloads.json}). */
    public static final int VERSION = 1;
    /** How many recent items are persisted (the rest are dropped, oldest first). */
    public static final int MAX_RECENT = 50;

    private static final Logger logger = LoggerFactory.getLogger(DownloadManager.class);
    private static final int MAX_NAME_LENGTH = 180;

    private final Path file;
    /** The live download directory (D1: the C5 setting may change at runtime). */
    private final Supplier<Path> downloadDir;
    private final Gson gson = new Gson();
    private final Object lock = new Object();
    /** Insertion order. Guarded by {@link #lock}. */
    private final List<DownloadItem> items = new ArrayList<>();
    /** Shelf listeners; invoked on whatever thread calls the mutation (the CEF
     *  handlers pump to the EDT, plan §4.2). */
    private final List<Consumer<List<DownloadItem>>> listeners = new CopyOnWriteArrayList<>();
    private long idCounter;

    /**
     * Opens (and when needed creates) the recent-downloads list.
     *
     * @param file        the JSON file, conventionally {@code conf/browser/downloads.json}
     * @param downloadDir the live download directory (D1 base folder, settings)
     */
    public DownloadManager(Path file, Supplier<Path> downloadDir) {
        this.file = file;
        this.downloadDir = downloadDir;
        load();
    }

    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** @return a snapshot of the tracked items, oldest first. */
    public synchronized List<DownloadItem> items() {
        synchronized (lock) {
            return new ArrayList<>(items);
        }
    }

    /** @return the item with the given id, or {@code null}. */
    public synchronized DownloadItem find(String id) {
        synchronized (lock) {
            return findLocked(id);
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    /**
     * Registers a new download and resolves its collision-free target path
     * (D1). The file does not exist yet — Chromium creates it; the base
     * directory is created when needed.
     *
     * @param url           the download URL
     * @param suggestedName the CEF-suggested file name (may be null; sanitized here)
     * @param cefId         the CEF download-item id (diagnostics only)
     * @return the registered (PENDING) item
     */
    public synchronized DownloadItem register(String url, String suggestedName, int cefId) {
        DownloadItem item = new DownloadItem();
        synchronized (lock) {
            Path dir = ensureDirectory();
            String name = sanitizeName(suggestedName);
            item.setId(nextId());
            item.setCefId(cefId);
            item.setUrl(url == null ? "" : url);
            item.setSuggestedName(name);
            item.setTargetPath(resolveCollisionFree(dir, name).toString());
            item.setState(DownloadItem.State.PENDING);
            item.setStartedAt(System.currentTimeMillis());
            items.add(item);
        }
        return item;
    }

    /**
     * Applies one CEF {@code onDownloadUpdated} snapshot (D2 state machine).
     * Terminal states stamp {@code finishedAt} and persist the recent list.
     *
     * @return the updated item, or {@code null} for an unknown id
     */
    public synchronized DownloadItem update(String id, long receivedBytes, long totalBytes,
                                            int percentComplete, long speedBps,
                                            boolean inProgress, boolean complete, boolean canceled) {
        DownloadItem item;
        synchronized (lock) {
            item = findLocked(id);
            if (item == null) {
                return null;
            }
            item.setReceivedBytes(receivedBytes);
            item.setTotalBytes(totalBytes);
            item.setPercentComplete(Math.max(0, Math.min(100, percentComplete)));
            item.setSpeedBps(speedBps);
            DownloadItem.State next;
            if (complete) {
                next = DownloadItem.State.COMPLETE;
            } else if (canceled) {
                next = DownloadItem.State.CANCELED;
            } else if (inProgress) {
                next = DownloadItem.State.IN_PROGRESS;
            } else if (item.getState() == DownloadItem.State.IN_PROGRESS) {
                next = DownloadItem.State.FAILED; // terminated without complete/canceled
            } else {
                next = item.getState(); // still queued (first update arrives before transfer)
            }
            if (next != item.getState()) {
                item.setState(next);
                if (next.isTerminal() && item.getFinishedAt() == 0) {
                    item.setFinishedAt(System.currentTimeMillis());
                    saveLocked();
                }
            }
        }
        publish();
        return item;
    }

    /**
     * Drops the item from the tracked list (the file on disk is kept — the
     * shelf's "remove from list"; the D4 page will offer real deletion).
     *
     * @return the removed item, or {@code null} for an unknown id
     */
    public synchronized DownloadItem remove(String id) {
        DownloadItem removed;
        synchronized (lock) {
            removed = findLocked(id);
            if (removed != null) {
                items.remove(removed);
                saveLocked();
            }
        }
        publish();
        return removed;
    }

    /** Re-reads the recent list from disk (replaces the in-memory state). */
    public synchronized void reload() {
        load();
        publish();
    }

    /**
     * Saves the recent list atomically. Never throws (D17); failures are
     * logged and dropped — the in-memory list is the source of truth.
     */
    public synchronized void save() {
        FileShape shape = new FileShape();
        synchronized (lock) {
            shape.version = VERSION;
            shape.items.addAll(items);
        }
        writeAtomic(shape);
    }

    /** Releases nothing (no threads, no connections) — saves instead, for
     *  interface symmetry with the other stores (try-with-resources sites). */
    @Override
    public synchronized void close() {
        save();
    }

    // ------------------------------------------------------------------
    // Listeners (the shelf)
    // ------------------------------------------------------------------

    /** Adds a snapshot listener (invoked on the caller's thread — the EDT in production). */
    public void addListener(Consumer<List<DownloadItem>> listener) {
        if (listener != null) {
            listeners.add(listener);
        }
    }

    /** Notifies the listeners with the current snapshot (any thread). */
    public synchronized void publish() {
        List<DownloadItem> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(items);
        }
        for (Consumer<List<DownloadItem>> listener : listeners) {
            listener.accept(snapshot);
        }
    }

    // ------------------------------------------------------------------
    // Name / path rules (D1)
    // ------------------------------------------------------------------

    /**
     * Turns a CEF-suggested name into a safe file name: keeps the last path
     * segment, strips characters that are invalid on Windows or POSIX, and
     * falls back to {@code "download"} for empty input.
     */
    static String sanitizeName(String raw) {
        String name = raw == null ? "" : raw.trim();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) {
            name = name.substring(slash + 1).trim();
        }
        name = name.replaceAll("[\\\\/:*?\"<>|\u0000-\u001f]", "_").trim();
        if (name.isEmpty() || name.matches("^_+$")) {
            return "download";
        }
        if (name.length() > MAX_NAME_LENGTH) {
            name = name.substring(0, MAX_NAME_LENGTH).trim();
        }
        return name;
    }

    /**
     * D1: the plain name when it is free, otherwise {@code base (1)ext},
     * {@code base (2)ext}, … (the Chrome convention, the plan's " (1)" suffix).
     */
    static Path resolveCollisionFree(Path dir, String name) {
        Path plain = dir.resolve(name);
        if (!Files.exists(plain)) {
            return plain;
        }
        String base = name;
        String extension = "";
        int dot = name.lastIndexOf('.');
        if (dot > 0 && dot < name.length() - 1) {
            base = name.substring(0, dot);
            extension = name.substring(dot);
        }
        for (int i = 1; ; i++) {
            Path candidate = dir.resolve(base + " (" + i + ")" + extension);
            if (!Files.exists(candidate)) {
                return candidate;
            }
        }
    }

    /**
     * The directory a new download lands in (D1): the configured directory
     * when it can be created, otherwise the user's Downloads folder,
     * otherwise the system temp dir (never fatal, D17).
     */
    private Path ensureDirectory() {
        List<Path> candidates = new ArrayList<>();
        Path configured = downloadDir == null ? null : downloadDir.get();
        if (configured != null) {
            candidates.add(configured);
        }
        candidates.add(Path.of(System.getProperty("user.home", "."), "Downloads"));
        candidates.add(Path.of(System.getProperty("java.io.tmpdir", ".")));
        for (Path candidate : candidates) {
            try {
                Files.createDirectories(candidate);
                return candidate;
            } catch (IOException e) {
                logger.warn("Download directory not usable, trying the next candidate: {}", candidate, e);
            }
        }
        // Unreachable in practice; fall through with the last candidate so the
        // registration still succeeds (the CEF download will then fail cleanly).
        return candidates.get(candidates.size() - 1);
    }

    private DownloadItem findLocked(String id) {
        for (DownloadItem item : items) {
            if (item.getId().equals(id)) {
                return item;
            }
        }
        return null;
    }

    private String nextId() {
        String id;
        do {
            id = "d" + Long.toHexString(System.nanoTime()) + Long.toHexString(idCounter++);
        } while (findLocked(id) != null);
        return id;
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    private static final class FileShape {
        int version = VERSION;
        List<DownloadItem> items = new ArrayList<>();
    }

    /** Builds and writes the shape while still holding {@link #lock}. */
    private void saveLocked() {
        FileShape shape = new FileShape();
        shape.version = VERSION;
        shape.items.addAll(items);
        writeAtomic(shape);
    }

    /** Reads + sanitizes the recent list. Never throws (D17). */
    private void load() {
        synchronized (lock) {
            items.clear();
            idCounter = 0;
            if (file == null || !Files.exists(file)) {
                return;
            }
            FileShape shape;
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                shape = gson.fromJson(reader, FileShape.class);
            } catch (RuntimeException | IOException e) {
                logger.warn("Corrupt downloads file, starting with the empty list: {}", file, e);
                return;
            }
            if (shape == null || shape.items == null) {
                return;
            }
            Set<String> seen = new HashSet<>();
            List<DownloadItem> loaded = new ArrayList<>();
            for (DownloadItem item : shape.items) {
                if (item == null || item.getId() == null || item.getId().isBlank()
                        || item.getTargetPath() == null || item.getTargetPath().isBlank()) {
                    continue; // D17: drop unresolvable records
                }
                if (!seen.add(item.getId())) {
                    continue; // duplicates: keep the first
                }
                if (item.getSuggestedName() == null || item.getSuggestedName().isBlank()) {
                    item.setSuggestedName(sanitizeName(Path.of(item.getTargetPath()).getFileName().toString()));
                }
                if (item.getUrl() == null) {
                    item.setUrl("");
                }
                if (item.getPercentComplete() < 0 || item.getPercentComplete() > 100) {
                    item.setPercentComplete(0);
                }
                // The process restarted: no transfer survived. A file on disk is a
                // (possibly complete) result, its absence a failure.
                if (!item.getState().isTerminal()) {
                    Path target = Path.of(item.getTargetPath());
                    item.setState(Files.exists(target)
                            ? DownloadItem.State.COMPLETE
                            : DownloadItem.State.FAILED);
                    item.setFinishedAt(item.getStartedAt() > 0
                            ? item.getStartedAt()
                            : System.currentTimeMillis());
                }
                loaded.add(item);
            }
            if (loaded.size() > MAX_RECENT) {
                loaded = new ArrayList<>(loaded.subList(loaded.size() - MAX_RECENT, loaded.size()));
            }
            items.addAll(loaded);
        }
    }

    /** The atomic write (session-store pattern): temp file in the same directory + move. */
    private void writeAtomic(FileShape shape) {
        if (file == null) {
            return;
        }
        Path dir = file.toAbsolutePath().getParent();
        Path tmp = null;
        try {
            if (dir != null) {
                Files.createDirectories(dir);
            }
            tmp = Files.createTempFile(dir, "downloads-", ".tmp");
            try (Writer writer = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                gson.toJson(shape, writer);
            }
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            tmp = null;
        } catch (RuntimeException | IOException e) {
            logger.warn("Could not persist the download list: {}", file, e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // best effort
                }
            }
        }
    }
}