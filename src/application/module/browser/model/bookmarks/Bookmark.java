package application.module.browser.model.bookmarks;

import com.google.gson.annotations.SerializedName;

import java.util.ArrayList;
import java.util.List;

/**
 * One node of the bookmarks tree (plan F4, B3: nested folders).
 * <p>
 * The store persists a flat id → node map (Appendix C); {@code children}
 * holds the ordered child ids of a folder, so the tree is acyclic by
 * construction of the store's mutation rules (a node can never be moved
 * into its own subtree).
 * <p>
 * Plain gson bean (no-arg constructor + getters/setters, the same shape as
 * the session snapshot).
 */
public final class Bookmark {

    public enum Type {
        @SerializedName("folder")
        FOLDER,
        @SerializedName("bookmark")
        BOOKMARK
    }

    private String id;
    private Type type;
    private String name;
    /** The target URL — set on bookmarks only. */
    private String url;
    /** Ordered child ids — set on folders only. */
    private List<String> children = new ArrayList<>();

    public Bookmark() {
        // gson deserialization
    }

    public Bookmark(String id, Type type, String name, String url) {
        this.id = id;
        this.type = type;
        this.name = name;
        this.url = url;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public Type getType() {
        return type;
    }

    public void setType(Type type) {
        this.type = type;
    }

    public boolean isFolder() {
        return type == Type.FOLDER;
    }

    public boolean isBookmark() {
        return type == Type.BOOKMARK;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public List<String> getChildren() {
        return children;
    }

    public void setChildren(List<String> children) {
        this.children = children == null ? new ArrayList<>() : children;
    }
}
