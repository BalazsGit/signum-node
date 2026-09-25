package application.module.browser.gui.bookmarks;

import application.module.browser.model.bookmarks.Bookmark;
import application.module.browser.model.bookmarks.BookmarkStore;
import application.utils.i18n.I18n;

import java.awt.FlowLayout;
import java.util.List;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.JButton;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;

/**
 * The bookmarks bar (plan F4, B4): the bar items from the
 * {@link BookmarkStore} as compact buttons — bookmarks open their URL,
 * folders carry a popup menu (children, new folder, manage). Right-click
 * anywhere on an item offers rename / delete / bar-membership (B2/B4).
 * Visibility is toggled with Ctrl+Shift+B (the {@code showBookmarksBar}
 * setting persists it, the toggle lives in {@code BrowserPanel}).
 * <p>
 * Pure view: intents go through the shared store (persisted there) and the
 * two navigation consumers (open URL / open an internal page in the active
 * tab). Rebuilt wholesale on {@link #refresh()} — the item count is tiny.
 * Must be constructed on the EDT.
 */
public final class BookmarksBar extends JPanel {

    private static final String FOLDER_GLYPH = "\uD83D\uDCC1"; // 📁

    private final BookmarkStore store;
    private final Consumer<String> openUrl;
    private final Consumer<String> openPage;

    public BookmarksBar(BookmarkStore store,
                        Consumer<String> openUrl,
                        Consumer<String> openPage) {
        super(new FlowLayout(FlowLayout.LEFT, 4, 3));
        this.store = store;
        this.openUrl = openUrl;
        this.openPage = openPage;
        setOpaque(false);
        refresh();
    }

    /** Rebuilds the bar from the store (call after any bookmark change). */
    public void refresh() {
        removeAll();
        for (Bookmark item : store.barItems()) {
            add(itemButton(item));
        }
        JButton newFolder = new JButton("+");
        newFolder.setFocusable(false);
        newFolder.setMargin(new java.awt.Insets(0, 6, 0, 6));
        newFolder.setToolTipText(I18n.get("browser.bookmarks.bar.newFolder"));
        newFolder.addActionListener(e -> createNewFolder());
        add(newFolder);
        revalidate();
        repaint();
    }

    private JButton itemButton(Bookmark item) {
        JButton button = new JButton((item.isFolder() ? FOLDER_GLYPH + " " : "")
                + (item.getName() == null ? "" : item.getName()));
        button.setFocusable(false);
        button.setRolloverEnabled(true);
        button.setMargin(new java.awt.Insets(1, 6, 1, 6));
        button.setToolTipText(item.getName());
        button.addActionListener(e -> {
            if (item.isBookmark() && item.getUrl() != null) {
                openUrl.accept(item.getUrl());
            }
        });
        if (item.isFolder()) {
            // left click: the folder's contents (a popup, like Chrome's folders)
            button.addActionListener(e ->
                    folderMenu(item, false).show(button, 0, button.getHeight()));
        }
        // right click: the management menu (B2)
        button.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    itemMenu(item).show(button, e.getX(), e.getY());
                }
            }
        });
        return button;
    }
    // ------------------------------------------------------------------
    // Menus
    // ------------------------------------------------------------------

    /** The folder's children as a (recursive) menu, optionally with the management items. */
    private JPopupMenu folderMenu(Bookmark folder, boolean withManagement) {
        JPopupMenu menu = new JPopupMenu();
        List<Bookmark> children = store.children(folder.getId());
        for (Bookmark child : children) {
            if (child.isFolder()) {
                JMenu sub = new JMenu(FOLDER_GLYPH + " " + child.getName());
                List<Bookmark> grandChildren = store.children(child.getId());
                if (grandChildren.isEmpty()) {
                    JMenuItem empty = new JMenuItem(I18n.get("browser.bookmarks.menu.empty"));
                    empty.setEnabled(false);
                    sub.add(empty);
                } else {
                    for (Bookmark grandChild : grandChildren) {
                        if (grandChild.isBookmark()) {
                            String url = grandChild.getUrl();
                            sub.add(menuItem(grandChild.getName(),
                                    () -> openUrl.accept(url)));
                        } else {
                            JMenu subSub = new JMenu(FOLDER_GLYPH + " " + grandChild.getName());
                            subSub.add(folderMenu(grandChild, false)); // content only
                            sub.add(subSub);
                        }
                    }
                }
                menu.add(sub);
            } else {
                String url = child.getUrl();
                menu.add(menuItem(child.getName(), () -> openUrl.accept(url)));
            }
        }
        if (children.isEmpty()) {
            JMenuItem empty = new JMenuItem(I18n.get("browser.bookmarks.menu.empty"));
            empty.setEnabled(false);
            menu.add(empty);
        }
        if (withManagement) {
            menu.addSeparator();
            menu.add(menuItem(I18n.get("browser.bookmarks.menu.newFolder"),
                    () -> createNewFolderIn(folder.getId())));
            menu.addSeparator();
            menu.add(menuItem(I18n.get("browser.bookmarks.menu.manage"),
                    () -> openPage.accept("signum://bookmarks")));
        }
        return menu;
    }

    /** The management menu of one bar item (B2). */
    private JPopupMenu itemMenu(Bookmark item) {
        JPopupMenu menu = new JPopupMenu();
        if (item.isBookmark() && item.getUrl() != null) {
            String url = item.getUrl();
            menu.add(menuItem(I18n.get("browser.bookmarks.menu.open"),
                    () -> openUrl.accept(url)));
        }
        if (!item.isFolder()) {
            menu.add(menuItem(I18n.get("browser.bookmarks.menu.rename"),
                    () -> renameItem(item)));
            menu.addSeparator();
            boolean inBar = store.isInBar(item.getId());
            menu.add(menuItem(inBar
                    ? I18n.get("browser.bookmarks.menu.removeFromBar")
                    : I18n.get("browser.bookmarks.menu.addToBar"),
                    () -> {
                        store.setInBar(item.getId(), !inBar);
                        store.save();
                        refresh();
                    }));
            menu.add(menuItem(I18n.get("browser.bookmarks.menu.delete"),
                    () -> deleteItem(item)));
        } else {
            menu.add(folderMenu(item, true));
        }
        return menu;
    }

    private JMenuItem menuItem(String text, Runnable action) {
        return new JMenuItem(new AbstractAction(text) {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                action.run();
            }
        });
    }
    // ------------------------------------------------------------------
    // Mutations (prompt → store → persist → refresh)
    // ------------------------------------------------------------------

    private void createNewFolder() {
        String name = JOptionPane.showInputDialog(this,
                I18n.get("browser.bookmarks.bar.newFolder.prompt"),
                I18n.get("browser.bookmarks.folder.new.name"),
                JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        String id = store.newFolder(BookmarkStore.ROOT_ID, name);
        if (id != null) {
            store.setInBar(id, true); // a bar-created folder belongs on the bar
            store.save();
            refresh();
        }
    }

    private void createNewFolderIn(String parentId) {
        String name = JOptionPane.showInputDialog(this,
                I18n.get("browser.bookmarks.bar.newFolder.prompt"),
                I18n.get("browser.bookmarks.folder.new.name"),
                JOptionPane.PLAIN_MESSAGE);
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        if (store.newFolder(parentId, name) != null) {
            store.save();
            refresh();
        }
    }

    private void renameItem(Bookmark item) {
        String name = JOptionPane.showInputDialog(this,
                I18n.get("browser.bookmarks.rename.prompt"), item.getName());
        if (name == null || name.trim().isEmpty()) {
            return;
        }
        if (store.rename(item.getId(), name)) {
            store.save();
            refresh();
        }
    }

    private void deleteItem(Bookmark item) {
        String message = item.isFolder()
                ? I18n.get("browser.bookmarks.deleteFolder.confirm").replace("{0}", item.getName())
                : I18n.get("browser.bookmarks.delete.confirm").replace("{0}", item.getName());
        int answer = JOptionPane.showConfirmDialog(this, message,
                I18n.get("browser.bookmarks.title"),
                JOptionPane.OK_CANCEL_OPTION, JOptionPane.WARNING_MESSAGE);
        if (answer == JOptionPane.OK_OPTION) {
            store.delete(item.getId());
            store.save();
            refresh();
        }
    }
}