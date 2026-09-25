package application.module.browser.engine.scheme;

import application.module.browser.model.bookmarks.Bookmark;
import application.module.browser.model.bookmarks.BookmarkStore;
import application.utils.i18n.I18n;
import com.google.gson.Gson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Body producer of the {@code signum://bookmarks} manager page (plan F4, B5).
 * <p>
 * Same architecture as the history page: the static template carries no
 * user-visible text (D16) — every UI string (i18n) and the whole tree
 * (flat nodes with their parent) arrive in one JSON data island, injected
 * script-safe (S9, {@code <} → {@code \u003c}). The page renders the tree
 * client-side, filters it by the search query and performs every mutation
 * as a plain navigation (the F2/F3 pattern — no XHR on the custom scheme):
 * <ul>
 *   <li>{@code signum://bookmarks[?q=..]} — the manager listing (B5: tree +
 *       search; the HTML5 drag-and-drop target is any folder row);</li>
 *   <li>{@code bookmarks/add?name=..&url=..&folder=..} — add a bookmark;</li>
 *   <li>{@code bookmarks/folder?name=..&folder=..} — add a folder (B3);</li>
 *   <li>{@code bookmarks/rename?id=..&name=..}, {@code bookmarks/move?id=..&target=..},</li>
 *   <li>{@code bookmarks/delete?id=..} (folder deletion is confirmed in-page),</li>
 *   <li>{@code bookmarks/bar?id=..&on=1|0} — bookmarks-bar membership (B4).</li>
 * </ul>
 * Every action URL re-renders the listing afterwards. No CEF imports —
 * fully unit-testable.
 */
public final class BookmarkPageRenderer implements InternalPage.PageRenderer {

    /** The page name in the {@link InternalPage} registry. */
    public static final String PAGE = "bookmarks";
    /** The page URL (bookmarks manager, B5). */
    public static final String PAGE_URL = BrowserSchemeHandler.SCHEME_PREFIX + PAGE;

    private static final Logger logger = LoggerFactory.getLogger(BookmarkPageRenderer.class);

    private static final String TEMPLATE = "/html/browser/bookmarks.html";
    /** Placeholder of the static template, replaced by the escaped JSON island. */
    private static final String DATA_PLACEHOLDER = "__SIGNUM_DATA__";

    private final BookmarkStore store;
    private final Gson gson = new Gson();
    /**
     * B6: the export target chooser (GUI, EDT): takes the default file name
     * and returns the chosen path, or null when canceled. May be null — then
     * the export action just re-renders.
     */
    private java.util.function.Function<String, String> exportPicker;
    /**
     * B6: the import source chooser (GUI, EDT): returns the chosen path or
     * null when canceled. May be null.
     */
    private java.util.function.Supplier<String> importPicker;

    public BookmarkPageRenderer(BookmarkStore store) {
        this.store = store;
    }

    /** B6: sets the export file chooser (the GUI implements it). */
    public void setExportPicker(java.util.function.Function<String, String> exportPicker) {
        this.exportPicker = exportPicker;
    }

    /** B6: sets the import file chooser (the GUI implements it). */
    public void setImportPicker(java.util.function.Supplier<String> importPicker) {
        this.importPicker = importPicker;
    }

    @Override
    public byte[] render(String url, Map<String, String> query) {
        switch (pathOf(url)) {
            case "bookmarks":
                return renderList(query);
            case "bookmarks/add": {
                String folder = param(query, "folder");
                String id = store.newBookmark(
                        folder.isEmpty() ? BookmarkStore.ROOT_ID : folder,
                        param(query, "name"), param(query, "url"));
                if (id != null) {
                    store.save();
                }
                return renderList(Map.of());
            }
            case "bookmarks/folder": {
                String folder = param(query, "folder");
                String id = store.newFolder(
                        folder.isEmpty() ? BookmarkStore.ROOT_ID : folder,
                        param(query, "name"));
                if (id != null) {
                    store.save();
                }
                return renderList(Map.of());
            }
            case "bookmarks/rename": {
                String id = param(query, "id");
                if (!id.isEmpty() && store.rename(id, param(query, "name"))) {
                    store.save();
                }
                return renderList(Map.of());
            }
            case "bookmarks/move": {
                String id = param(query, "id");
                String target = param(query, "target");
                if (!id.isEmpty() && !target.isEmpty() && store.move(id, target)) {
                    store.save();
                }
                return renderList(Map.of());
            }
            case "bookmarks/delete": {
                String id = param(query, "id");
                if (!id.isEmpty() && store.delete(id) > 0) {
                    store.save();
                }
                return renderList(Map.of());
            }
            case "bookmarks/bar": {
                String id = param(query, "id");
                if (!id.isEmpty()) {
                    store.setInBar(id, "1".equals(param(query, "on")));
                    store.save();
                }
                return renderList(Map.of());
            }
            case "bookmarks/export": {
                // B6: the GUI picks the target file; the store serializes
                java.util.function.Function<String, String> picker = exportPicker;
                if (picker != null) {
                    String path = null;
                    try {
                        path = picker.apply("signum-bookmarks.json");
                    } catch (RuntimeException e) {
                        logger.warn("The bookmark export chooser failed: {}", e.toString());
                    }
                    if (path != null && !path.isBlank()) {
                        try {
                            java.nio.file.Files.writeString(java.nio.file.Path.of(path.trim()),
                                    store.exportJson(), java.nio.charset.StandardCharsets.UTF_8);
                            logger.info("Bookmarks exported to {}", path.trim());
                        } catch (java.io.IOException e) {
                            logger.error("Could not write the bookmark export to {}", path, e);
                        }
                    }
                }
                return renderList(Map.of());
            }
            case "bookmarks/import": {
                // B6: the GUI picks the source file; the store merges it
                java.util.function.Supplier<String> picker = importPicker;
                if (picker != null) {
                    String path = null;
                    try {
                        path = picker.get();
                    } catch (RuntimeException e) {
                        logger.warn("The bookmark import chooser failed: {}", e.toString());
                    }
                    if (path != null && !path.isBlank()) {
                        try {
                            String json = java.nio.file.Files.readString(
                                    java.nio.file.Path.of(path.trim()),
                                    java.nio.charset.StandardCharsets.UTF_8);
                            int imported = store.importJson(json);
                            if (imported > 0) {
                                store.save();
                                logger.info("Imported {} bookmark nodes from {}", imported, path.trim());
                            }
                        } catch (java.io.IOException e) {
                            logger.error("Could not read the bookmark import from {}", path, e);
                        }
                    }
                }
                return renderList(Map.of());
            }
            default:
                return null; // unknown action — the 404 page wins
        }
    }
    private byte[] renderList(Map<String, String> query) {
        PageData data = new PageData();
        data.q = param(query, "q");
        data.now = System.currentTimeMillis();
        for (Bookmark node : store.search(null)) {
            data.nodes.add(new NodeData(node, store.parentOf(node.getId()).orElse("")));
        }
        for (Bookmark item : store.barItems()) {
            data.bar.add(item.getId());
        }
        Map<String, String> strings = data.strings;
        strings.put("title", I18n.get("browser.bookmarks.title"));
        strings.put("searchPlaceholder", I18n.get("browser.bookmarks.search.placeholder"));
        strings.put("newFolder", I18n.get("browser.bookmarks.newFolder"));
        strings.put("newFolderPrompt", I18n.get("browser.bookmarks.newFolder.prompt"));
        strings.put("addBookmark", I18n.get("browser.bookmarks.add"));
        strings.put("addNamePrompt", I18n.get("browser.bookmarks.add.name.prompt"));
        strings.put("addUrlPrompt", I18n.get("browser.bookmarks.add.url.prompt"));
        strings.put("empty", I18n.get("browser.bookmarks.empty"));
        strings.put("edit", I18n.get("browser.bookmarks.edit"));
        strings.put("delete", I18n.get("browser.bookmarks.delete"));
        strings.put("addToBar", I18n.get("browser.bookmarks.addToBar"));
        strings.put("removeFromBar", I18n.get("browser.bookmarks.removeFromBar"));
        strings.put("confirmDelete", I18n.get("browser.bookmarks.delete.confirm"));
        strings.put("confirmDeleteFolder", I18n.get("browser.bookmarks.deleteFolder.confirm"));
        // B6
        strings.put("exportBookmarks", I18n.get("browser.bookmarks.export"));
        strings.put("importBookmarks", I18n.get("browser.bookmarks.import"));

        String template = readTemplate();
        if (template == null) {
            return null;
        }
        String json = escapeForScript(gson.toJson(data));
        return template.replace(DATA_PLACEHOLDER, json).getBytes(StandardCharsets.UTF_8);
    }

    /**
     * S9: makes the JSON safe for inlining into a {@code <script>} tag —
     * every opening angle bracket becomes the valid JSON escape {@code \u003c}
     * (the same rule as the history page).
     */
    static String escapeForScript(String json) {
        return json.replace("<", "\\u003c");
    }

    static String pathOf(String url) {
        if (url == null) {
            return "";
        }
        String path = url;
        if (path.startsWith(BrowserSchemeHandler.SCHEME_PREFIX)) {
            path = path.substring(BrowserSchemeHandler.SCHEME_PREFIX.length());
        }
        int query = path.indexOf('?');
        if (query >= 0) {
            path = path.substring(0, query);
        }
        int hash = path.indexOf('#');
        if (hash >= 0) {
            path = path.substring(0, hash);
        }
        while (path.startsWith("/")) {
            path = path.substring(1);
        }
        if (path.endsWith("/")) {
            path = path.substring(0, path.length() - 1);
        }
        return path;
    }

    private static String param(Map<String, String> query, String name) {
        String value = query.get(name);
        return value == null ? "" : value.trim();
    }

    private String readTemplate() {
        try (InputStream in = BookmarkPageRenderer.class.getResourceAsStream(TEMPLATE)) {
            if (in == null) {
                logger.error("Bookmarks page template missing from the classpath: {}", TEMPLATE);
                return null;
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("Could not read the bookmarks page template {}", TEMPLATE, e);
        }
        return null;
    }

    /** The injected payload (gson-serialized public fields). */
    static final class PageData {
        int v = 1;
        long now;
        String q = "";
        /** Flat node list in tree order (the root is not included). */
        List<NodeData> nodes = new ArrayList<>();
        /** Bookmarks-bar item ids, in order. */
        List<String> bar = new ArrayList<>();
        Map<String, String> strings = new LinkedHashMap<>();
    }

    static final class NodeData {
        String id;
        String type;
        String name;
        String url;
        /** The containing folder's id (empty for top-level items). */
        String parent;

        NodeData(Bookmark node, String parentId) {
            this.id = node.getId();
            this.type = node.isFolder() ? "folder" : "bookmark";
            this.name = node.getName() == null ? "" : node.getName();
            this.url = node.getUrl() == null ? "" : node.getUrl();
            this.parent = parentId;
        }
    }
}