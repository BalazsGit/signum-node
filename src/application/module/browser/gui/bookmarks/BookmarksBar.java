package application.module.browser.gui.bookmarks;

import application.module.browser.model.bookmarks.Bookmark;
import application.module.browser.model.bookmarks.BookmarkStore;
import application.utils.gui.GuiColors;
import application.utils.gui.PlusGlyphIcon;
import application.utils.i18n.I18n;
import jiconfont.icons.font_awesome.FontAwesome;
import jiconfont.swing.IconFontSwing;

import java.awt.FlowLayout;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Window;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import javax.swing.AbstractAction;
import javax.swing.BorderFactory;
import javax.swing.Icon;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenu;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JWindow;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

/**
 * The bookmarks bar (plan F4, B4): the bar items from the
 * {@link BookmarkStore} as compact buttons — bookmarks open their URL,
 * folders carry a folder icon and a popup menu (children, new folder,
 * manage). Right-click anywhere on a bookmark offers rename / delete
 * (B2/B4) — deleting removes it from the bar and the tree alike.
 * Chrome-style, <em>visually real</em> drag-and-drop organization: a bar
 * item is dragged with the mouse — a translucent ghost of the chip follows
 * the cursor — and dropped either at another position on the bar (reorder,
 * marked by an insertion line) or onto a bar folder (move into it, the
 * folder glows while hovered; the store's cycle guard rejects a drop into
 * the item's own subtree). The manual ghost drag is used instead of the
 * system {@code TransferHandler} drag because on the pinned JDK the system
 * drag neither shows a drag image on the source component nor reliable
 * enter/exit feedback, so the drag looked dead. Visibility is toggled with
 * Ctrl+Shift+B (the {@code showBookmarksBar} setting persists it, the
 * toggle lives in {@code BrowserPanel}).
 * <p>
 * Pure view: intents go through the shared store (persisted there) and the
 * two navigation consumers (open URL / open an internal page in the tab).
 * Rebuilt wholesale on {@link #refresh()} — the item count is tiny. The
 * owner (one bar per tab view, one shared store) refreshes the other bars
 * through {@link #setOnChanged(Runnable)} — the hook fires after every
 * mutation. Must be constructed on the EDT.
 */
public final class BookmarksBar extends JPanel {

    /**
     * The drop-target highlight of a folder chip (drag hover feedback):
     * without it the drop target is invisible and the drag-and-drop looks
     * dead (the drop itself works, the eye cannot see where it would land).
     */
    static final java.awt.Color DROP_HIGHLIGHT = new java.awt.Color(0x4F, 0x8C, 0xFF, 60);

    /** The move threshold in pixels before a press becomes a drag. */
    private static final int DRAG_THRESHOLD = 3;

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

    /** The bar item chips in render order (the "new folder" button excluded). */
    private final List<JButton> chips = new ArrayList<>();
    /** Chip → its bar item (rebuilt on every {@link #refresh()}). */
    private final Map<JButton, Bookmark> chipItems = new IdentityHashMap<>();

    // ------------------------------------------------------------------
    // Drag state (the manual ghost drag — see the class javadoc)
    // ------------------------------------------------------------------
    /** The item currently dragged ({@code null} when no drag is in progress). */
    private Bookmark dragItem;
    /** The chip being dragged (dimmed while the ghost follows the cursor). */
    private JButton dragChip;
    /** True while a drag is in progress (press → threshold → drop/cancel). */
    private boolean dragging;
    /** The cursor's screen position when the drag started. */
    private Point dragCursorAtStart;
    /** The ghost's screen position at the drag start (the chip's location). */
    private Point ghostAnchor;
    /** The floating ghost of the dragged chip (disposed on drag end). */
    private JWindow dragGhost;
    /** The folder chip currently hovered by the drag (highlighted), or null. */
    private JButton dropFolderChip;
    /** The bar-relative x of the insertion line (reorder feedback), or -1. */
    private int dropIndicatorX = -1;

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
        finishDrag(false); // a structural rebuild invalidates an in-flight drag
        removeAll();
        chips.clear();
        chipItems.clear();
        for (Bookmark item : store.barItems()) {
            JButton chip = itemButton(item);
            chips.add(chip);
            chipItems.put(chip, item);
            add(chip);
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
        // right click: the management menu (B2); left press/drag: the
        // manual ghost drag (reorder on the bar, drop onto a bar folder).
        button.addMouseListener(new java.awt.event.MouseAdapter() {
            /** Where the current press started (bar coordinates), or null. */
            private Point pressLocation;

            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                if (SwingUtilities.isRightMouseButton(e)) {
                    itemMenu(item).show(button, e.getX(), e.getY());
                }
            }

            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                if (e.getButton() == MouseEvent.BUTTON1) {
                    pressLocation = e.getPoint();
                }
            }

            @Override
            public void mouseDragged(java.awt.event.MouseEvent e) {
                if (pressLocation == null) {
                    return;
                }
                if (!dragging) {
                    // Only a deliberate move starts the drag — a plain click
                    // (open the URL / show the folder menu) must not spawn
                    // a ghost.
                    int dx = Math.abs(e.getX() - pressLocation.x);
                    int dy = Math.abs(e.getY() - pressLocation.y);
                    if (dx + dy < DRAG_THRESHOLD) {
                        return;
                    }
                    beginDrag(item, button, e.getLocationOnScreen());
                }
                updateDragTarget(e.getLocationOnScreen());
            }

            @Override
            public void mouseReleased(java.awt.event.MouseEvent e) {
                if (dragging) {
                    finishDrag(true); // dropped — commit the move
                }
                pressLocation = null;
            }
        });
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
    // Drag-and-drop (B4): the manual ghost drag — the dragged chip's ghost
    // follows the cursor, an insertion line marks the reorder position, a
    // folder chip glows while hovered (drop into it)
    // ------------------------------------------------------------------

    /**
     * The window-level release watcher: a Swing mouseReleased only fires on
     * the chip while the cursor is inside the window — when the user drops
     * the ghost OUTSIDE the window, the top-level's AWT listener ends the
     * drag as a cancel (no move is committed).
     */
    private final java.awt.event.MouseListener releaseWatcher = new java.awt.event.MouseListener() {
        @Override
        public void mousePressed(java.awt.event.MouseEvent e) {
        }

        @Override
        public void mouseReleased(java.awt.event.MouseEvent e) {
            if (dragging && e.getButton() == MouseEvent.BUTTON1) {
                finishDrag(false);
            }
        }

        @Override
        public void mouseClicked(java.awt.event.MouseEvent e) {
        }

        @Override
        public void mouseEntered(java.awt.event.MouseEvent e) {
        }

        @Override
        public void mouseExited(java.awt.event.MouseEvent e) {
        }
    };

    /**
     * Starts the visual drag: dims the source chip, spawns the floating
     * ghost at the chip's position and begins following the cursor.
     *
     * @param item       the dragged bar item
     * @param chip       the chip the drag started from
     * @param cursorScreen the cursor's current screen position
     */
    private void beginDrag(Bookmark item, JButton chip, Point cursorScreen) {
        dragging = true;
        dragItem = item;
        dragChip = chip;
        dragCursorAtStart = cursorScreen;
        // Dim the source chip (this JDK's JComponent has no setAlpha): a
        // translucent fill reads as "picked up" while the ghost follows.
        chip.setBackground(DROP_HIGHLIGHT);
        buildDragGhost();
        ghostAnchor = chip.getLocationOnScreen();
        positionDragGhost(cursorScreen);
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window != null) {
            window.addMouseListener(releaseWatcher);
        }
    }

    /** The translucent floating copy of the dragged chip (its icon + name). */
    private void buildDragGhost() {
        if (dragGhost != null) {
            dragGhost.dispose();
            dragGhost = null;
        }
        JPanel content = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 1));
        content.setOpaque(true);
        content.setBackground(ghostBackground());
        content.setBorder(BorderFactory.createLineBorder(accentColor()));
        Bookmark item = dragItem;
        JLabel label = new JLabel(item.getName() == null ? "" : item.getName());
        label.setForeground(ghostForeground());
        if (item.isFolder()) {
            label.setIcon(folderIcon());
            label.setIconTextGap(4);
        }
        content.add(label);
        JWindow ghost = new JWindow();
        // This JDK's JWindow has no setOpaque/setResizable — an unpainted
        // borderless JWindow simply shows its background, so blend the
        // window background into the ghost's fill.
        ghost.setBackground(ghostBackground());
        ghost.setAlwaysOnTop(true);
        ghost.setFocusable(false);
        ghost.add(content);
        ghost.pack();
        dragGhost = ghost;
    }

    /** Rigid follow: the ghost moves with the cursor (the chip's grab offset is kept). */
    private void positionDragGhost(Point cursorScreen) {
        if (dragGhost != null && dragCursorAtStart != null && ghostAnchor != null) {
            dragGhost.setLocation(ghostAnchor.x + (cursorScreen.x - dragCursorAtStart.x),
                    ghostAnchor.y + (cursorScreen.y - dragCursorAtStart.y));
        }
    }

    /**
     * Resolves the drop target under the cursor and updates the feedback:
     * a hovered folder chip glows (drop into it), otherwise an insertion
     * line is placed at the reorder position the cursor currently indicates
     * (before / after the chip under it, or at the bar's start / end).
     */
    private void updateDragTarget(Point cursorScreen) {
        if (!dragging) {
            return;
        }
        positionDragGhost(cursorScreen);
        // Screen → bar coordinates (this JDK's SwingUtilities has only the
        // component-relative convertPoint; a null source = screen space).
        Point p = SwingUtilities.convertPoint(null, new Point(cursorScreen), this);
        JButton newFolderTarget = null;
        int newX = -1;
        if (dragItem != null && contains(p)) {
            int n = chips.size();
            int hover = -1;
            for (int i = 0; i < n; i++) {
                if (chips.get(i).getBounds().contains(p)) {
                    hover = i;
                    break;
                }
            }
            if (hover >= 0) {
                JButton c = chips.get(hover);
                Bookmark b = chipItems.get(c);
                if (b != null && !b.getId().equals(dragItem.getId())) {
                    if (b.isFolder()) {
                        // A folder is a valid target only when it is not
                        // inside the dragged item's own subtree (the store
                        // would reject it anyway — don't advertise it).
                        if (!store.ancestorsOf(b.getId()).contains(dragItem.getId())) {
                            newFolderTarget = c;
                        }
                    } else {
                        Rectangle r = c.getBounds();
                        // Left half of the chip = insert before it, right
                        // half = insert after it (this JDK's Rectangle has
                        // no getCenterX).
                        newX = p.x < r.x + r.width / 2 ? r.x - 1 : r.x + r.width;
                    }
                }
            } else {
                // Not over a chip: the first chip to the RIGHT of the
                // cursor is the insertion anchor (before it), or the bar's
                // end when the cursor is past every chip.
                for (int i = 0; i < n; i++) {
                    Rectangle r = chips.get(i).getBounds();
                    if (p.x < r.getMaxX()) {
                        newX = r.x - 1;
                        break;
                    }
                    if (i == n - 1) {
                        newX = (int) r.getMaxX();
                    }
                }
            }
        }
        if (newFolderTarget != dropFolderChip) {
            if (dropFolderChip != null) {
                dropFolderChip.setBackground(null);
            }
            dropFolderChip = newFolderTarget;
            if (dropFolderChip != null) {
                dropFolderChip.setBackground(DROP_HIGHLIGHT);
            }
        }
        if (newX != dropIndicatorX) {
            dropIndicatorX = newX;
            repaint();
        }
    }

    /** The bar's insertion line (the reorder feedback) over the chip row. */
    @Override
    protected void paintComponent(java.awt.Graphics g) {
        super.paintComponent(g);
        if (dragging && dropIndicatorX >= 0) {
            g.setColor(accentColor());
            g.fillRect(dropIndicatorX, 3, 2, Math.max(0, getHeight() - 6));
        }
    }

    /**
     * Ends the drag. When {@code commit} is true and a target is active the
     * move is persisted ({@code move} into the hovered folder,
     * {@code moveBefore} at the insertion position) and every consumer is
     * refreshed; either way the ghost, the feedback and the dimmed chip are
     * restored.
     */
    private void finishDrag(boolean commit) {
        if (!dragging) {
            return; // already finished (the window watcher can fire twice)
        }
        dragging = false;
        Bookmark item = dragItem;
        Bookmark droppedInto = dropFolderChip != null ? chipItems.get(dropFolderChip) : null;
        int indicatorX = dropIndicatorX;
        dragItem = null;
        if (dragChip != null) {
            dragChip.setBackground(null);
            dragChip = null;
        }
        if (dropFolderChip != null) {
            dropFolderChip.setBackground(null);
            dropFolderChip = null;
        }
        dropIndicatorX = -1;
        Window window = SwingUtilities.getWindowAncestor(this);
        if (window != null) {
            window.removeMouseListener(releaseWatcher);
        }
        if (dragGhost != null) {
            dragGhost.dispose();
            dragGhost = null;
        }
        repaint();
        if (!commit || item == null) {
            return;
        }
        boolean moved;
        if (droppedInto != null) {
            moved = store.move(item.getId(), droppedInto.getId());
        } else if (indicatorX >= 0) {
            moved = store.moveBefore(item.getId(), BookmarkStore.ROOT_ID, beforeIdAt(indicatorX));
        } else {
            moved = false; // dropped on empty bar space / outside — cancel
        }
        if (moved) {
            store.save();
            refresh();
            fireChanged();
        }
    }

    /**
     * The reorder anchor for an insertion line: the line at a chip's LEFT
     * edge inserts before that chip, at a chip's RIGHT edge before the NEXT
     * chip (or at the bar's end for the last chip).
     */
    private String beforeIdAt(int x) {
        for (int i = 0; i < chips.size(); i++) {
            JButton c = chips.get(i);
            Rectangle r = c.getBounds();
            if (r.x - 1 == x) {
                return chipItems.get(c).getId();
            }
            if (r.x + r.getWidth() == x) {
                return i + 1 < chips.size() ? chipItems.get(chips.get(i + 1)).getId() : null;
            }
        }
        return null;
    }

    /** The insertion line / drop outline accent (the LAF focus color). */
    private static java.awt.Color accentColor() {
        java.awt.Color c = UIManager.getColor("Component.focusColor");
        return c != null ? c : new java.awt.Color(0x4F, 0x8C, 0xFF);
    }

    /** The ghost window's background (the LAF panel color, slightly translucent). */
    private static java.awt.Color ghostBackground() {
        java.awt.Color c = UIManager.getColor("Panel.background");
        return c != null ? new java.awt.Color(c.getRed(), c.getGreen(), c.getBlue(), 235)
                : new java.awt.Color(30, 33, 40, 235);
    }

    /** The ghost window's text color (the LAF panel foreground). */
    private static java.awt.Color ghostForeground() {
        java.awt.Color c = UIManager.getColor("Panel.foreground");
        return c != null ? c : java.awt.Color.WHITE;
    }
}