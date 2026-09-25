package application.module.browser.gui.bookmarks;

import application.module.browser.model.bookmarks.Bookmark;
import application.module.browser.model.bookmarks.BookmarkStore;
import application.utils.i18n.I18n;

import java.awt.BorderLayout;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.awt.Window;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.JButton;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.JTree;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;

/**
 * The Ctrl+D / star bookmark dialog (plan F4, B1): the page's suggested
 * name, the target folder (a folder-only tree, the store's root labelled
 * with the i18n name) and Add/Cancel. When the URL is already bookmarked
 * the dialog switches to edit mode (Edit also moves the bookmark to the
 * chosen folder; Remove deletes it).
 * <p>
 * Static {@link #show} — the same idiom as the certificate dialog. Every
 * accepted change is persisted through the {@link BookmarkStore}.
 */
public final class BookmarkDialog {

    /** What the user did with the dialog (the caller refreshes its views). */
    public enum Result {
        /** Nothing was changed. */
        CANCEL,
        /** A new bookmark was added. */
        ADDED,
        /** The existing bookmark was renamed/moved. */
        EDITED,
        /** The existing bookmark was removed. */
        REMOVED
    }

    private BookmarkDialog() {
        // utility class — never instantiated
    }

    /**
     * Shows the dialog modally.
     *
     * @param owner         the top-level window (may be null)
     * @param store         the shared bookmark store
     * @param url           the page URL
     * @param suggestedName the page title (fallback: the URL)
     * @param existing      the bookmark for this URL (null when unbookmarked)
     * @return what the user did
     */
    public static Result show(Window owner, BookmarkStore store, String url,
                              String suggestedName, Bookmark existing) {
        String name = suggestedName == null || suggestedName.isBlank() ? url : suggestedName;
        String rootLabel = I18n.get("browser.bookmarks.folder.root");

        // folder-only tree; allFolders() is in tree order, so a parent
        // always precedes its children
        DefaultMutableTreeNode root = new DefaultMutableTreeNode(rootLabel);
        root.setUserObject(BookmarkStore.ROOT_ID);
        Map<String, DefaultMutableTreeNode> folderNodes = new LinkedHashMap<>();
        folderNodes.put(BookmarkStore.ROOT_ID, root);
        for (Bookmark folder : store.allFolders()) {
            if (folder.getId().equals(BookmarkStore.ROOT_ID)) {
                continue;
            }
            DefaultMutableTreeNode node = new DefaultMutableTreeNode(folder.getName());
            node.setUserObject(folder.getId());
            String parentId = store.parentOf(folder.getId()).orElse(BookmarkStore.ROOT_ID);
            folderNodes.getOrDefault(parentId, root).add(node);
            folderNodes.put(folder.getId(), node);
        }
        JTree tree = new JTree(new DefaultTreeModel(root));
        tree.expandRow(0);

        // default selection: the existing bookmark's folder, else the root
        String defaultFolderId = existing == null
                ? BookmarkStore.ROOT_ID
                : store.parentOf(existing.getId()).orElse(BookmarkStore.ROOT_ID);
        DefaultMutableTreeNode defaultNode = folderNodes.getOrDefault(defaultFolderId, root);
        tree.setSelectionPath(new TreePath(defaultNode.getPath()));

        JTextField nameField = new JTextField(name);
        nameField.setPreferredSize(new Dimension(320, 30));

        JPanel form = new JPanel(new GridBagLayout());
        form.setOpaque(false);
        form.setBorder(BorderFactory.createEmptyBorder(14, 16, 6, 16));
        GridBagConstraints c = new GridBagConstraints();
        c.insets = new Insets(4, 4, 4, 4);
        c.anchor = GridBagConstraints.WEST;
        c.gridx = 0;
        c.gridy = 0;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(new JLabel(I18n.get("browser.bookmarks.dialog.name")), c);
        c.gridx = 1;
        form.add(nameField, c);
        c.gridx = 0;
        c.gridy = 1;
        c.fill = GridBagConstraints.NONE;
        form.add(new JLabel(I18n.get("browser.bookmarks.dialog.folder")), c);
        c.gridx = 1;
        c.fill = GridBagConstraints.HORIZONTAL;
        form.add(new JScrollPane(tree), c);
        JButton addOrEdit = new JButton(existing == null
                ? I18n.get("browser.bookmarks.dialog.add")
                : I18n.get("browser.bookmarks.dialog.edit"));
        JButton remove = null;
        if (existing != null) {
            remove = new JButton(I18n.get("browser.bookmarks.dialog.remove"));
        }
        JButton cancel = new JButton(I18n.get("browser.bookmarks.dialog.cancel"));
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        buttons.setOpaque(false);
        buttons.setBorder(BorderFactory.createEmptyBorder(6, 16, 14, 16));
        if (remove != null) {
            buttons.add(remove);
        }
        buttons.add(addOrEdit);
        buttons.add(cancel);

        String title = I18n.get("browser.bookmarks.dialog.title")
                .replace("{0}", shortenUrl(url));
        JDialog dialog = new JDialog(owner, title, JDialog.ModalityType.APPLICATION_MODAL);
        dialog.getContentPane().setLayout(new BorderLayout());
        dialog.getContentPane().add(form, BorderLayout.CENTER);
        dialog.getContentPane().add(buttons, BorderLayout.SOUTH);
        dialog.getRootPane().setDefaultButton(addOrEdit);
        dialog.pack();
        dialog.setLocationRelativeTo(owner);

        final Result[] outcome = {Result.CANCEL};
        addOrEdit.addActionListener(e -> {
            String newName = nameField.getText().trim();
            String targetFolderId = folderIdOf(tree.getSelectionPath());
            if (newName.isEmpty() || targetFolderId == null) {
                return;
            }
            if (existing == null) {
                if (store.newBookmark(targetFolderId, newName, url) != null) {
                    store.save();
                    outcome[0] = Result.ADDED;
                }
            } else {
                boolean changed = store.rename(existing.getId(), newName);
                if (!existing.getId().equals(targetFolderId)) {
                    changed |= store.move(existing.getId(), targetFolderId);
                }
                if (changed) {
                    store.save();
                    outcome[0] = Result.EDITED;
                }
            }
            dialog.dispose();
        });
        if (remove != null) {
            remove.addActionListener(e -> {
                store.delete(existing.getId());
                store.save();
                outcome[0] = Result.REMOVED;
                dialog.dispose();
            });
        }
        cancel.addActionListener(e -> dialog.dispose());

        dialog.setVisible(true);
        return outcome[0];
    }

    /** @return the folder id encoded in the tree path's user object (or null). */
    private static String folderIdOf(TreePath path) {
        if (path == null) {
            return null;
        }
        // JDK 25: TreePath.getLastPathComponent() returns Object and the
        // TreeNode interfaces no longer carry getUserObject() — the concrete
        // DefaultMutableTreeNode (the only node type built here) still has it.
        Object lastComponent = path.getLastPathComponent();
        if (!(lastComponent instanceof DefaultMutableTreeNode node)) {
            return null;
        }
        Object userObject = node.getUserObject();
        return userObject instanceof String stringId ? stringId : null;
    }

    private static String shortenUrl(String url) {
        if (url == null) {
            return "";
        }
        return url.length() <= 48 ? url : url.substring(0, 45) + "...";
    }
}