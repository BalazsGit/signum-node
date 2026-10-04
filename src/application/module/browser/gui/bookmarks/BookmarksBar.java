package application.module.browser.gui.bookmarks;

import application.module.browser.model.bookmarks.Bookmark;
import application.module.browser.model.bookmarks.BookmarkStore;
import application.utils.gui.GuiColors;
import application.utils.gui.PlusGlyphIcon;
import application.utils.i18n.I18n;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

import java.awt.FlowLayout;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingUtilities;
import javax.swing.TransferHandler;

/**
 * The bookmarks bar (plan F4, B4): the bar items from the
 * {@link BookmarkStore} as compact buttons — bookmarks open their URL,
 * folders carry a folder icon and a popup menu (children, new folder,
 * manage). Right-click anywhere on a bookmark offers rename / delete
 * (B2/B4) — deleting removes it from the bar and the tree alike.
 * Chrome-style organization: a bar bookmark can
 * be <b>dragged onto a bar folder</b> to move into it (the store's
 * cycle guard rejects a drop into the item's own subtree). Visibility is
 * toggled with Ctrl+Shift+B (the {@code showBookmarksBar} setting
 * persists it, the toggle lives in {@code BrowserPanel}).
 * <p>
 * Pure view: intents go through the shared store (persisted there) and the
 * two navigation consumers (open URL / open an internal page in the tab).
 * Rebuilt wholesale on {@link #refresh()} — the item count is tiny. The
 * owner (one bar per tab view, one shared store) refreshes the other bars
 * through {@link #setOnChanged(Runnable)} — the hook fires after every
 * mutation. Must be constructed on the EDT.
 */
public final class BookmarksBar extends JPanel {

    /** The custom drag flavor: the id of a dragged bookmark (B4). */
    static final DataFlavor BOOKMARK_FLAVOR;

    static {
        BOOKMARK_FLAVOR = new DataFlavor(String.class, "Signum Bookmark Id");
    }

    /**
     * The drop-target highlight of a folder chip (B4 drag hover feedback):
     * without it the drop target is invisible and the drag-and-drop looks
     * dead (the drop itself works, the eye cannot see where it would land).
     */
    static final java.awt.Color DROP_HIGHLIGHT = new java.awt.Color(0x4F, 0x8C, 0xFF, 60);

    /**
     * The bar renders its own FontAwesome folder glyph: the font must be
     * registered with IconFontSwing before the first icon build (in the
     * app some other icon does it first; a standalone bar — unit tests —
     * must not depend on that side effect). The same once-only guard
     * NavigationToolbar uses (double registration corrupts the metrics).
     */
    private static final AtomicBoolean FONT_REGISTERED = new AtomicBoolean(false);
    /** The folder glyph size of the bar buttons (a compact chip, not a toolbar glyph). */
    private static final float FOLDER_ICON_SIZE = 14f;

    private final BookmarkStore store;
    private final Consumer<String> openUrl;
    private final Consumer<String> openPage;
    /** Fired after every mutation (the owner refreshes the other bars + stars). */
    private Runnable changed;

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

    /**
     * Registers the callback fired after any mutation of the shared store
     * through this bar (new folder, rename, delete, bar-membership, a
     * drag-drop move). The owner uses it to refresh the other bars and
     * re-sync the stars. May be null.
     */
    public void setOnChanged(Runnable changed) {
        this.changed = changed;
    }

    private void fireChanged() {
        if (changed != null) {
            changed.run();
        }
    }

    /** Rebuilds the bar from the store (call after any bookmark change). */
    public void refresh() {
        removeAll();
        for (Bookmark item : store.barItems()) {
            add(itemButton(item));
        }
        JButton newFolder = new JButton();
        newFolder.setFocusable(false);
        newFolder.setMargin(new java.awt.Insets(1, 6, 1, 6));
        newFolder.setContentAreaFilled(false);
        newFolder.setBorderPainted(false);
        newFolder.setFocusPainted(false);
        newFolder.setRolloverEnabled(true);
        PlusGlyphIcon.installHover(newFolder);
        newFolder.setToolTipText(I18n.get("browser.bookmarks.bar.newFolder.tooltip"));
        newFolder.addActionListener(e -> createNewFolder());
        add(newFolder);
        revalidate();
        repaint();
    }

    private JButton itemButton(Bookmark item) {
        JButton button = new JButton(item.getName() == null ? "" : item.getName());
        button.setFocusable(false);
        button.setRolloverEnabled(true);
        button.setMargin(new java.awt.Insets(1, 6, 1, 6));
        if (item.isFolder()) {
            // A folder-like glyph (not an emoji) makes the folder's role
            // obvious at a glance; the tooltip explains it in words and
            // hints the drag-and-drop organization.
            button.setIcon(folderIcon());
            button.setIconTextGap(4);
            button.setToolTipText(item.getName() + "\n"
                    + I18n.get("browser.bookmarks.bar.folder.tooltip"));
        } else {
            button.setToolTipText(item.getName());
        }
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

            @Override
            public void mouseDragged(java.awt.event.MouseEvent e) {
                // JDK 25: TransferHandler no longer exposes dragEnter/dragExit
                // (and the DRAG_ENTERED/DRAG_EXITED mouse ids are gone) — a
                // system drag over the chip surfaces as plain drag mouse
                // motion, so the folder is highlighted while it is hovered.
                if (item.isFolder()) {
                    button.setBackground(DROP_HIGHLIGHT);
                }
            }

            @Override
            public void mouseExited(java.awt.event.MouseEvent e) {
                button.setBackground(null);
            }
        });
        // B4: the item participates in the bar's drag-and-drop — a
        // bookmark can be dragged, a folder can be dropped onto.
        button.setTransferHandler(new BookmarkTransferHandler(item, button));
        return button;
    }

    /** The folder glyph of the bar buttons (registered once, palette-colored). */
    private static Icon folderIcon() {
        if (FONT_REGISTERED.compareAndSet(false, true)) {
            IconFontSwing.register(FontAwesome.getIconFont());
        }
        return IconFontSwing.buildIcon(FontAwesome.FOLDER_O, FOLDER_ICON_SIZE, GuiColors.getButtonIcon());
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
                JMenu sub = new JMenu(child.getName());
                sub.setIcon(folderIcon());
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
                            JMenu subSub = new JMenu(grandChild.getName());
                            subSub.setIcon(folderIcon());
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
            // One option, not two: deleting a bar item removes it from the
            // bar AND from the bookmark tree (the user's delete model — a
            // bar-only "remove" left the item reachable nowhere obvious and
            // duplicated the choice).
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
            fireChanged();
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
            fireChanged();
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
            fireChanged();
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
            fireChanged();
        }
    }

    // ------------------------------------------------------------------
    // Drag-and-drop (B4): bookmarks are draggable, folders accept drops
    // ------------------------------------------------------------------

    /**
     * The bar item's transfer handler (JDK 25: the class moved to
     * {@code javax.swing}): a <em>bookmark</em> exports its id as a
     * {@link BookmarkTransferable} (draggable), a <em>folder</em> imports a
     * dragged bookmark id and moves it into itself (Chrome-style
     * organization). The store's {@code move} guards the no-ops (unknown
     * id, not a folder, the item's own subtree — cycles are impossible).
     */
    private final class BookmarkTransferHandler extends TransferHandler {

        private final Bookmark item;
        private final JButton button;

        BookmarkTransferHandler(Bookmark item, JButton button) {
            this.item = item;
            this.button = button;
        }

        /** Only bookmarks export a drag payload (their id); folders don't. */
        @Override
        protected Transferable createTransferable(JComponent c) {
            return item.isBookmark() ? new BookmarkTransferable(item.getId()) : null;
        }

        @Override
        public int getSourceActions(JComponent c) {
            return item.isBookmark() ? MOVE : NONE;
        }

        @Override
        public boolean canImport(TransferHandler.TransferSupport support) {
            return item.isFolder() && support.isDataFlavorSupported(BOOKMARK_FLAVOR);
        }

        @Override
        public boolean importData(TransferHandler.TransferSupport support) {
            if (!support.isDataFlavorSupported(BOOKMARK_FLAVOR) || !item.isFolder()) {
                return false;
            }
            button.setBackground(null);
            try {
                Object data = support.getTransferable().getTransferData(BOOKMARK_FLAVOR);
                if (!(data instanceof String draggedId) || draggedId.equals(item.getId())) {
                    return false;
                }
                if (store.move(draggedId, item.getId())) {
                    store.save();
                    refresh();
                    fireChanged();
                    return true;
                }
            } catch (UnsupportedFlavorException | java.io.IOException e) {
                // the drag payload could not be read — the drop is a no-op
            }
            return false;
        }
    }

    /**
     * The drag payload of a dragged bar bookmark: its id, under the custom
     * {@link #BOOKMARK_FLAVOR} (never stringFlavor — a plain text drop
     * must not move a bookmark).
     */
    private record BookmarkTransferable(String id) implements Transferable {

        @Override
        public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{BOOKMARK_FLAVOR};
        }

        @Override
        public boolean isDataFlavorSupported(DataFlavor flavor) {
            return BOOKMARK_FLAVOR.equals(flavor);
        }

        @Override
        public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException {
            if (!isDataFlavorSupported(flavor)) {
                throw new UnsupportedFlavorException(flavor);
            }
            return id;
        }
    }
}