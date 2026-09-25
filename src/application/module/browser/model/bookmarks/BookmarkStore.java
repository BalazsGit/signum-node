package application.module.browser.model.bookmarks;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The bookmarks store (plan F4, B1–B5): a JSON tree at
 * {@code conf/browser/bookmarks.json} (Appendix C:
 * {@code {"version":1,"bar":[ids],"nodes":{id:{...}}}}).
 * <p>
 * The in-memory model is a flat id → {@link Bookmark} map; the tree is the
 * folders' ordered {@code children} id lists. A virtual root folder
 * (id {@value #ROOT_ID}) is always present — it holds everything and is the
 * default target of the star button. Mutation rules keep the tree acyclic
 * (a node can never be moved into its own subtree, the root can never be
 * moved, renamed or deleted).
 * <p>
 * <b>Never fatal (D17):</b> a missing or corrupt file degrades to the empty
 * tree (just the root), and {@link #save()} never throws (atomic temp file +
 * move, the session-store pattern). Pure Java — no Swing, no CEF — fully
 * unit-testable.
 */
public final class BookmarkStore implements AutoCloseable {

    /** The JSON file version (Appendix C). */
    public static final int VERSION = 1;
    /** The always-present, undeletable root folder (UI name from i18n). */
    public static final String ROOT_ID = "root";

    private static final Logger logger = LoggerFactory.getLogger(BookmarkStore.class);

    private final Path file;
    private final Gson gson = new Gson();
    private final Object lock = new Object();
    /** id → node, insertion (tree) order. Guarded by {@link #lock}. */
    private final Map<String, Bookmark> nodes = new LinkedHashMap<>();
    /** Ordered bookmarks-bar item ids (no root). Guarded by {@link #lock}. */
    private final List<String> bar = new ArrayList<>();
    private long idCounter;

    /**
     * Opens (and when needed creates) the bookmark tree.
     *
     * @param file the JSON file, conventionally {@code conf/browser/bookmarks.json}
     */
    public BookmarkStore(Path file) {
        this.file = file;
        synchronized (lock) {
            loadLocked();
        }
    }

    // ------------------------------------------------------------------
    // Persistence
    // ------------------------------------------------------------------

    /** Re-reads the file from disk (replaces the in-memory tree). Never throws. */
    public synchronized void reload() {
        loadLocked();
    }

    /** Saves the tree atomically (never throws; failures are logged). */
    public synchronized void save() {
        FileShape shape = new FileShape();
        shape.version = VERSION;
        synchronized (lock) {
            bar.forEach(id -> {
                if (!ROOT_ID.equals(id) && nodes.containsKey(id)) {
                    shape.bar.add(id);
                }
            });
            // the root is persisted too (its children are the top-level items)
            shape.nodes.putAll(nodes);
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            Path temp = Files.createTempFile(parent, ".bookmarks-", ".tmp");
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                gson.toJson(shape, writer);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            logger.error("Could not save the bookmarks to {}", file, e);
        }
    }

    // ------------------------------------------------------------------
    // Export / import (B6)
    // ------------------------------------------------------------------

    /**
     * B6: serializes the whole tree (including the bookmarks bar) to the
     * portable JSON document — the same shape as the on-disk file
     * (Appendix C), so a plain {@code bookmarks.json} is also a valid
     * import source.
     *
     * @return the JSON text (never null)
     */
    public synchronized String exportJson() {
        FileShape shape = new FileShape();
        shape.version = VERSION;
        synchronized (lock) {
            bar.forEach(id -> {
                if (!ROOT_ID.equals(id) && nodes.containsKey(id)) {
                    shape.bar.add(id);
                }
            });
            shape.nodes.putAll(nodes);
        }
        return gson.toJson(shape);
    }

    /**
     * B6: merges a previously exported JSON document into the tree.
     * <p>
     * Every imported node is copied under a <em>fresh id</em> (the import
     * never touches existing nodes; the virtual root is mapped onto this
     * store's root, and its children are appended when not already
     * present). Child references are remapped accordingly; a dangling
     * reference is dropped. Imported bar entries are appended when absent.
     * Never throws (D17): corrupt input simply imports nothing.
     *
     * @param json the exported document (see {@link #exportJson})
     * @return the number of imported nodes (the root is not counted)
     */
    public synchronized int importJson(String json) {
        if (json == null || json.isBlank()) {
            return 0;
        }
        JsonObject raw;
        try {
            raw = gson.fromJson(json, JsonObject.class);
        } catch (RuntimeException e) {
            logger.warn("Bookmark import rejected (unreadable document): {}", e.toString());
            return 0;
        }
        if (raw == null || !raw.has("nodes") || !raw.get("nodes").isJsonObject()) {
            return 0;
        }
        Map<String, Bookmark> imported = new LinkedHashMap<>();
        List<String> importedBar = new ArrayList<>();
        for (Map.Entry<String, com.google.gson.JsonElement> entry
                : raw.getAsJsonObject("nodes").entrySet()) {
            try {
                Bookmark node = gson.fromJson(entry.getValue(), Bookmark.class);
                if (node != null && node.getId() != null && node.getType() != null) {
                    imported.put(node.getId(), node);
                }
            } catch (RuntimeException e) {
                logger.warn("Dropping an unreadable imported bookmark node");
            }
        }
        if (raw.has("bar") && raw.get("bar").isJsonArray()) {
            for (com.google.gson.JsonElement element : raw.getAsJsonArray("bar")) {
                if (element.isJsonPrimitive()) {
                    importedBar.add(element.getAsString());
                }
            }
        }
        if (imported.isEmpty()) {
            return 0;
        }
        synchronized (lock) {
            // 1) remap every imported id (root -> this root, the rest fresh)
            Map<String, String> idMap = new LinkedHashMap<>();
            for (String id : imported.keySet()) {
                idMap.put(id, ROOT_ID.equals(id) ? ROOT_ID : nextId());
            }
            // 2) copy the nodes with the remapped ids and child references
            for (Map.Entry<String, Bookmark> entry : imported.entrySet()) {
                Bookmark source = entry.getValue();
                String newId = idMap.get(entry.getKey());
                List<String> children = new ArrayList<>();
                for (String childId : source.getChildren()) {
                    String mapped = idMap.get(childId);
                    if (mapped != null && !children.contains(mapped)) {
                        children.add(mapped);
                    }
                }
                if (ROOT_ID.equals(newId)) {
                    // merge the imported top level into this store's root
                    Bookmark root = nodes.get(ROOT_ID);
                    if (root != null) {
                        for (String child : children) {
                            if (!root.getChildren().contains(child)) {
                                root.getChildren().add(child);
                            }
                        }
                    }
                    continue; // the root itself is never replaced
                }
                Bookmark copy = new Bookmark(newId, source.getType(), source.getName(),
                        source.getUrl());
                copy.setChildren(children);
                nodes.put(newId, copy);
            }
            // 3) append the imported bar entries (remapped, no duplicates)
            for (String id : importedBar) {
                String mapped = idMap.get(id);
                if (mapped != null && !ROOT_ID.equals(mapped)
                        && nodes.containsKey(mapped) && !bar.contains(mapped)) {
                    bar.add(mapped);
                }
            }
            int importedCount = (int) imported.keySet().stream()
                    .filter(id -> !ROOT_ID.equals(id)).count();
            return importedCount;
        }
    }

    private void loadLocked() {
        nodes.clear();
        bar.clear();
        JsonObject raw = null;
        if (Files.isRegularFile(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                raw = gson.fromJson(reader, JsonObject.class);
            } catch (IOException | RuntimeException e) {
                logger.warn("Could not read the bookmarks file ({}); starting with an empty tree: {}",
                        file, e.toString());
            }
        }
        if (raw != null && raw.has("nodes") && raw.get("nodes").isJsonObject()) {
            for (Map.Entry<String, com.google.gson.JsonElement> entry
                    : raw.getAsJsonObject("nodes").entrySet()) {
                try {
                    Bookmark node = gson.fromJson(entry.getValue(), Bookmark.class);
                    if (node != null && node.getId() != null && node.getType() != null) {
                        nodes.put(node.getId(), node);
                    }
                } catch (RuntimeException e) {
                    logger.warn("Dropping an unreadable bookmark node (id={})", entry.getKey());
                }
            }
            if (raw.has("bar") && raw.get("bar").isJsonArray()) {
                for (com.google.gson.JsonElement element : raw.getAsJsonArray("bar")) {
                    if (element.isJsonPrimitive()) {
                        bar.add(element.getAsString());
                    }
                }
            }
        }
        ensureRoot();
        sanitizeLocked();
    }

    private void ensureRoot() {
        Bookmark root = nodes.get(ROOT_ID);
        if (root == null || root.getType() != Bookmark.Type.FOLDER) {
            Bookmark fresh = new Bookmark(ROOT_ID, Bookmark.Type.FOLDER, "", null);
            nodes.put(ROOT_ID, fresh);
        }
    }

    /**
     * Repairs a loaded tree: drops child references to missing nodes (and
     * duplicates), then prunes nodes unreachable from the root; the bar list
     * keeps only live, non-root ids (first occurrence wins).
     */
    private void sanitizeLocked() {
        // 1) trim children lists to live, unique ids
        for (Bookmark node : nodes.values()) {
            Set<String> seen = new LinkedHashSet<>();
            List<String> kept = new ArrayList<>();
            for (String childId : node.getChildren()) {
                if (childId != null && !childId.equals(node.getId())
                        && nodes.containsKey(childId) && seen.add(childId)) {
                    kept.add(childId);
                }
            }
            node.setChildren(kept);
        }
        // 2) drop everything the root cannot reach (BFS)
        Set<String> reachable = new LinkedHashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(ROOT_ID);
        reachable.add(ROOT_ID);
        while (!queue.isEmpty()) {
            Bookmark node = nodes.get(queue.poll());
            if (node == null || !node.isFolder()) {
                continue;
            }
            for (String childId : node.getChildren()) {
                if (reachable.add(childId)) {
                    queue.add(childId);
                }
            }
        }
        for (String id : new ArrayList<>(nodes.keySet())) {
            if (!reachable.contains(id)) {
                nodes.remove(id);
            }
        }
        // 3) bar: live, non-root, de-duplicated, order kept
        Set<String> liveBar = new LinkedHashSet<>();
        bar.removeIf(id -> ROOT_ID.equals(id) || !nodes.containsKey(id) || !liveBar.add(id));
    }

    /** gson file shape (Appendix C); the root is the node with id {@code "root"}. */
    private static final class FileShape {
        int version = VERSION;
        List<String> bar = new ArrayList<>();
        Map<String, Bookmark> nodes = new LinkedHashMap<>();
    }
    // ------------------------------------------------------------------
    // Queries
    // ------------------------------------------------------------------

    /** @param id any node id (may be null) */
    public synchronized Optional<Bookmark> get(String id) {
        return Optional.ofNullable(id == null ? null : nodes.get(id));
    }

    /**
     * @param folderId a folder id ({@link #ROOT_ID} for the top level)
     * @return the folder's children in order (empty for unknown ids or
     *         when the id is a bookmark)
     */
    public synchronized List<Bookmark> children(String folderId) {
        List<Bookmark> out = new ArrayList<>();
        Bookmark folder = nodes.get(folderId);
        if (folder == null || !folder.isFolder()) {
            return out;
        }
        for (String childId : folder.getChildren()) {
            Bookmark child = nodes.get(childId);
            if (child != null) {
                out.add(child);
            }
        }
        return out;
    }

    /** @return the id of the folder that contains the node (empty for the root/unknown). */
    public synchronized Optional<String> parentOf(String id) {
        if (id == null || ROOT_ID.equals(id) || !nodes.containsKey(id)) {
            return Optional.empty();
        }
        for (Bookmark folder : nodes.values()) {
            if (folder.isFolder() && folder.getChildren().contains(id)) {
                return Optional.of(folder.getId());
            }
        }
        return Optional.empty();
    }

    /** @return the ids of the node's ancestor folders, nearest parent first (root last). */
    public synchronized List<String> ancestorsOf(String id) {
        List<String> out = new ArrayList<>();
        String cursor = parentOf(id).orElse(null);
        while (cursor != null) {
            out.add(cursor);
            cursor = parentOf(cursor).orElse(null);
        }
        return out;
    }

    /**
     * Case-insensitive substring search over names and URLs, in tree order
     * (children keep their folder order). The result is the <em>whole</em>
     * tree (without the root) when the query is blank.
     */
    public synchronized List<Bookmark> search(String query) {
        List<Bookmark> out = new ArrayList<>();
        String needle = query == null ? "" : query.trim().toLowerCase();
        walk(ROOT_ID, needle, out);
        return out;
    }

    private void walk(String folderId, String needle, List<Bookmark> out) {
        Bookmark folder = nodes.get(folderId);
        if (folder == null || !folder.isFolder()) {
            return;
        }
        for (String childId : folder.getChildren()) {
            Bookmark child = nodes.get(childId);
            if (child == null) {
                continue;
            }
            if (needle.isEmpty()
                    || (child.getName() != null && child.getName().toLowerCase().contains(needle))
                    || (child.getUrl() != null && child.getUrl().toLowerCase().contains(needle))) {
                out.add(child);
            }
            if (child.isFolder()) {
                walk(childId, needle, out);
            }
        }
    }

    /** @param url an exact URL (case-sensitive, as stored) */
    public synchronized List<Bookmark> findByUrl(String url) {
        List<Bookmark> out = new ArrayList<>();
        if (url == null) {
            return out;
        }
        for (Bookmark node : nodes.values()) {
            if (node.isBookmark() && url.equals(node.getUrl())) {
                out.add(node);
            }
        }
        return out;
    }

    /** All folders in tree order, the root first (the dialog's folder picker). */
    public synchronized List<Bookmark> allFolders() {
        List<Bookmark> out = new ArrayList<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(ROOT_ID);
        while (!queue.isEmpty()) {
            String folderId = queue.poll();
            Bookmark folder = nodes.get(folderId);
            if (folder == null || !folder.isFolder()) {
                continue;
            }
            out.add(folder);
            for (String childId : new ArrayList<>(folder.getChildren())) {
                Bookmark child = nodes.get(childId);
                if (child != null && child.isFolder()) {
                    queue.add(childId);
                }
            }
        }
        return out;
    }

    /** The ordered bookmarks-bar items (live nodes only). */
    public synchronized List<Bookmark> barItems() {
        List<Bookmark> out = new ArrayList<>();
        for (String id : bar) {
            Bookmark node = nodes.get(id);
            if (node != null) {
                out.add(node);
            }
        }
        return out;
    }

    public synchronized boolean isInBar(String id) {
        return id != null && bar.contains(id);
    }

    /** @return the number of user nodes (the root is not counted). */
    public synchronized int count() {
        return nodes.size() - 1;
    }
    // ------------------------------------------------------------------
    // Mutations (B2/B3)
    // ------------------------------------------------------------------

    /**
     * Adds a bookmark to the given folder.
     *
     * @return the new node id, or {@code null} when the folder is unknown,
     *         the name is blank or the URL is blank
     */
    public synchronized String newBookmark(String folderId, String name, String url) {
        String trimmedName = name == null ? "" : name.trim();
        String trimmedUrl = url == null ? "" : url.trim();
        if (trimmedName.isEmpty() || trimmedUrl.isEmpty()) {
            return null;
        }
        Bookmark parent = nodes.get(folderId);
        if (parent == null || !parent.isFolder()) {
            return null;
        }
        String id = nextId();
        nodes.put(id, new Bookmark(id, Bookmark.Type.BOOKMARK, trimmedName, trimmedUrl));
        parent.getChildren().add(id);
        return id;
    }

    /**
     * Adds a folder inside the given folder (nesting allowed, B3).
     *
     * @return the new node id, or {@code null} when the parent is unknown or
     *         the name is blank
     */
    public synchronized String newFolder(String folderId, String name) {
        String trimmedName = name == null ? "" : name.trim();
        if (trimmedName.isEmpty()) {
            return null;
        }
        Bookmark parent = nodes.get(folderId);
        if (parent == null || !parent.isFolder()) {
            return null;
        }
        String id = nextId();
        nodes.put(id, new Bookmark(id, Bookmark.Type.FOLDER, trimmedName, null));
        parent.getChildren().add(id);
        return id;
    }

    /**
     * @return {@code false} for unknown ids and blank names (the root is
     *         rename-proof as well — its display name is owned by the UI)
     */
    public synchronized boolean rename(String id, String name) {
        String trimmed = name == null ? "" : name.trim();
        if (trimmed.isEmpty() || ROOT_ID.equals(id)) {
            return false;
        }
        Bookmark node = nodes.get(id);
        if (node == null) {
            return false;
        }
        node.setName(trimmed);
        return true;
    }

    /**
     * Moves the node into the target folder (appended at the end).
     *
     * @return {@code false} when the node or the target is unknown, the
     *         target is not a folder, the move is a no-op, or the target
     *         lies inside the node's own subtree (cycle guard)
     */
    public synchronized boolean move(String id, String targetFolderId) {
        if (id == null || targetFolderId == null || id.equals(targetFolderId)
                || ROOT_ID.equals(id)) {
            return false;
        }
        Bookmark node = nodes.get(id);
        Bookmark target = nodes.get(targetFolderId);
        if (node == null || target == null || !target.isFolder()) {
            return false;
        }
        if (ancestorsOf(targetFolderId).contains(id)) {
            return false; // the target is inside the node's own subtree — cycle
        }
        parentOf(id).ifPresent(current -> nodes.get(current).getChildren().remove(id));
        target.getChildren().add(id);
        return true;
    }

    /**
     * Deletes the node and, when it is a folder, its whole subtree; also
     * removes the node from the bookmarks bar.
     *
     * @return the number of deleted nodes (0 for the root or an unknown id)
     */
    public synchronized int delete(String id) {
        if (id == null || ROOT_ID.equals(id) || !nodes.containsKey(id)) {
            return 0;
        }
        Set<String> doomed = new LinkedHashSet<>();
        collectSubtree(id, doomed);
        parentOf(id).ifPresent(parentId -> nodes.get(parentId).getChildren().remove(id));
        bar.removeAll(doomed);
        int removed = 0;
        for (String doomedId : doomed) {
            if (nodes.remove(doomedId) != null) {
                removed++;
            }
        }
        return removed;
    }

    private void collectSubtree(String id, Set<String> out) {
        out.add(id);
        Bookmark node = nodes.get(id);
        if (node == null || !node.isFolder()) {
            return;
        }
        for (String childId : new ArrayList<>(node.getChildren())) {
            collectSubtree(childId, out);
        }
    }

    /** Adds/removes the node from the bookmarks bar (idempotent, B4). */
    public synchronized void setInBar(String id, boolean inBar) {
        if (id == null || ROOT_ID.equals(id) || !nodes.containsKey(id)) {
            return;
        }
        if (inBar) {
            if (!bar.contains(id)) {
                bar.add(id);
            }
        } else {
            bar.remove(id);
        }
    }

    private String nextId() {
        String id;
        do {
            id = "b" + Long.toHexString(System.nanoTime()) + Long.toHexString(idCounter++);
        } while (nodes.containsKey(id));
        return id;
    }

    /**
     * The store holds no OS resources (no connection, no thread) — close is
     * a no-op that exists for interface symmetry with the other stores
     * (try-with-resources at the call sites).
     */
    @Override
    public void close() {
        // nothing to release
    }
}