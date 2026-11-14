package application.module.database.gui;

import application.module.node.profile.SqliteDatabasePaths;
import application.utils.gui.ConfigurationUtils;
import application.utils.gui.FlatIconButton;
import application.utils.gui.FlatPlusButton;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.IconFontCache;
import jiconfont.icons.font_awesome.FontAwesome;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.BorderLayout;
import java.awt.event.FocusEvent;
import java.awt.event.FocusListener;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Component;
import java.util.ArrayList;
import java.util.List;

/**
 * A multi-row editor of a file-based SQLite profile's database files, modeled
 * directly on the node configuration panel's expandable list editors
 * ({@code Allowed IPs}, {@code Admin Password}): one editable combo per stored
 * database file (with a trash icon that removes that file) plus a trailing add
 * row — an editable combo and the flat, non-growing {@link FlatPlusButton}
 * (pressing Enter in the add row's combo adds it too).
 * <p>
 * The item rows and the add row share a single fixed-width icon column (the same
 * {@code [grow][action px, grow 0]} grid the node configuration list editors
 * use) so every trash icon and the plus line up in columns and the input fields
 * keep an identical width. Emptying an item input removes that file.
 * </p>
 * <p>
 * The editor reports the current file names ({@link #getItems()}) and fires
 * {@link #onChange} on every structural change (add/remove) and valid edit. It
 * enforces the {@code .db} convention itself (every added or edited name must be
 * a {@code *.db} file — {@link SqliteDatabasePaths#isDbFile(String)}) and rejects
 * invalid names with an error instead of firing the hook, so the owning panel
 * only ever sees valid {@code .db} names. The panel still decides how the files
 * map to the profile's {@code DB.Url} (the first file is the active one).
 * </p>
 */
public final class DatabaseFileListEditor extends JPanel {

    /**
     * The client-property key of an item combo's last valid (committed) file name.
     * The focus-loss validation reverts an invalid (non-{@code .db}) edit to this.
     */
    private static final String LAST_VALID_FILE = "databaseFileListEditor.lastValidFile";

    /** The shared grid: a growing input column plus one fixed icon column. */
    private final JPanel itemsPanel;
    private final JComboBox<String> addField;
    private final FlatPlusButton addButton;
    private final List<JComboBox<String>> itemFields = new ArrayList<>();
    private final int iconColumnWidth;

    /** The {@code *.db} candidate names offered by every row's dropdown (may be empty). */
    private List<String> candidates = List.of();

    /** Invoked on every structural change (add/remove) and item edit. */
    public Runnable onChange;

    /** Guards programmatic model swaps so the L&F's own events do not fire the hook. */
    private boolean isProgrammaticChange;

    public DatabaseFileListEditor() {
        super(new BorderLayout());
        setOpaque(false);

        // Measure the shared icon column from the REAL flat, borderless row
        // buttons (the trash glyph and the flat plus) so the column is exactly
        // as wide as the wider glyph — the inputs never over-size.
        JButton trashProto = new FlatIconButton();
        trashProto.setIcon(IconFontCache.buildIcon(FontAwesome.TRASH,
                GuiConstants.getHelpIconSize(), GuiColors.getContrastRed()));
        FlatPlusButton plusProto = new FlatPlusButton();
        iconColumnWidth = Math.max(trashProto.getPreferredSize().width,
                plusProto.getPreferredSize().width);
        int actionColumnWidth = iconColumnWidth + ConfigurationUtils.INPUT_ACTION_GAP;

        // All item rows and the add row share this one grid, so layout rounding
        // cannot move the plus away from the trash column.
        itemsPanel = new JPanel(new MigLayout("insets 0, fillx",
                "[grow][" + actionColumnWidth + "px, grow 0]", "[]"));
        itemsPanel.setOpaque(false);
        add(itemsPanel, BorderLayout.CENTER);

        // The add row: the same editable, styled input as the item rows and a
        // flat "+" that reads exactly like the node-profile "create profile"
        // button (thin plus glyph + rounded hover highlight).
        addField = new JComboBox<>(new String[0]);
        addField.setEditable(true);
        ConfigurationUtils.styleComboEditor(addField);
        addField.setPrototypeDisplayValue("signum.sqlite.db");
        addField.setToolTipText("Type a database file name (.db), then press '+' (or Enter) to add it");
        installAddEnterAction();

        addButton = new FlatPlusButton();
        addButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addButton.setToolTipText("Add a database file to this profile");
        addButton.addActionListener(e -> addItem(comboText(addField)));

        rebuildItems();
    }

    /**
     * The optional {@code *.db} candidate names offered by every row's dropdown
     * (typically the files discovered in the profile's database directory). The
     * current editor text of each row is preserved. An empty or {@code null}
     * list leaves the dropdowns empty. Must be called on the Swing EDT.
     *
     * @param candidateNames the candidate file names (may be empty or null)
     */
    public void setCandidates(List<String> candidateNames) {
        this.candidates = candidateNames == null ? List.of() : candidateNames;
        isProgrammaticChange = true;
        try {
            applyCandidateModel(addField);
            for (JComboBox<String> combo : itemFields) {
                applyCandidateModel(combo);
            }
        } finally {
            isProgrammaticChange = false;
        }
    }

    /** The current file names (trimmed, non-empty), in display order. */
    public List<String> getItems() {
        List<String> items = new ArrayList<>();
        for (JComboBox<String> combo : itemFields) {
            String text = comboText(combo).trim();
            if (!text.isEmpty()) {
                items.add(text);
            }
        }
        return items;
    }

    /** Bulk-loads the file names (e.g. on profile load) without firing the change hook. */
    public void setItems(List<String> values) {
        isProgrammaticChange = true;
        try {
            itemFields.clear();
            if (values != null) {
                for (String value : values) {
                    if (value != null && !value.trim().isEmpty()) {
                        itemFields.add(makeItemCombo(value.trim()));
                    }
                }
            }
            rebuildItems();
        } finally {
            isProgrammaticChange = false;
        }
    }

    /**
     * Appends a file from the add row. No-op when the text is blank; a name that
     * is not a {@code *.db} file ({@link SqliteDatabasePaths#isDbFile(String)})
     * is rejected with an error and not added.
     *
     * @param raw the file name typed in the add row (may be blank or null)
     */
    public void addItem(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return;
        }
        if (!SqliteDatabasePaths.isDbFile(value)) {
            rejectInvalidName(value);
            return;
        }
        isProgrammaticChange = true;
        try {
            itemFields.add(makeItemCombo(value));
            rebuildItems();
        } finally {
            isProgrammaticChange = false;
        }
        addField.getEditor().setItem("");
        fireChange();
    }

    /**
     * Shows the error for an invalid (non-{@code .db}) database file name.
     *
     * @param name the rejected file name
     */
    private void rejectInvalidName(String name) {
        JOptionPane.showMessageDialog(this,
                "The database file name must end with '.db' (no path separators).\n\nName: " + name,
                "Invalid database file name", JOptionPane.ERROR_MESSAGE);
    }

    /** Removes one file row. No-op when the combo is not in the list. */
    public void removeItem(JComboBox<String> field) {
        if (!itemFields.remove(field)) {
            return;
        }
        isProgrammaticChange = true;
        try {
            rebuildItems();
        } finally {
            isProgrammaticChange = false;
        }
        fireChange();
    }

    /**
     * The editor row grid: the item rows followed by the add row. Every row is
     * {@code [combo growx][icon w fixed]} — a combo and its action icon — and the
     * trailing {@code wrap} keeps the rows stacked (MigLayout's implicit flow is
     * horizontal).
     */
    private void rebuildItems() {
        itemsPanel.removeAll();
        for (JComboBox<String> combo : itemFields) {
            itemsPanel.add(combo, "growx");
            FlatIconButton removeBtn = new FlatIconButton();
            removeBtn.setIcon(IconFontCache.buildIcon(FontAwesome.TRASH,
                    GuiConstants.getHelpIconSize(), GuiColors.getContrastRed()));
            removeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            removeBtn.setToolTipText("Remove this database file");
            removeBtn.addActionListener(e -> removeItem(combo));
            itemsPanel.add(removeBtn,
                    "gapleft " + ConfigurationUtils.INPUT_ACTION_GAP + ", w " + iconColumnWidth + "!, wrap");
        }
        itemsPanel.add(addField, "growx");
        itemsPanel.add(addButton,
                "gapleft " + ConfigurationUtils.INPUT_ACTION_GAP + ", w " + iconColumnWidth + "!, wrap");
        itemsPanel.revalidate();
        itemsPanel.repaint();
    }

    /**
     * One file row's EDITABLE combo: the editor text is the file's current name
     * (styled exactly like every other value input), the dropdown offers the
     * shared {@code .db} candidates, and the editor's document feeds the change
     * hook (an emptied row removes itself).
     */
    private JComboBox<String> makeItemCombo(String value) {
        JComboBox<String> combo = new JComboBox<>(candidates.toArray(new String[0]));
        combo.setEditable(true);
        ConfigurationUtils.styleComboEditor(combo);
        combo.setPrototypeDisplayValue("signum.sqlite.db");
        combo.getEditor().setItem(value);
        // The last valid (committed) name — the focus-loss validation reverts an
        // invalid (non-.db) edit to it.
        combo.putClientProperty(LAST_VALID_FILE, value);
        combo.addActionListener(e -> {
            if (!isProgrammaticChange) {
                fireChange();
            }
        });
        Component editorComponent = combo.getEditor().getEditorComponent();
        if (editorComponent instanceof JTextField textField) {
            textField.getDocument().addDocumentListener(new DocumentListener() {
                @Override
                public void insertUpdate(DocumentEvent e) {
                    onItemComboChange(combo);
                }

                @Override
                public void removeUpdate(DocumentEvent e) {
                    onItemComboChange(combo);
                }

                @Override
                public void changedUpdate(DocumentEvent e) {
                    // Handled by the insert/remove updates.
                }
            });
            // Validate the .db convention on focus loss (not on every keystroke)
            // and revert an invalid edit to the last valid name.
            textField.addFocusListener(new FocusListener() {
                @Override
                public void focusGained(FocusEvent e) {
                    textField.selectAll();
                }

                @Override
                public void focusLost(FocusEvent e) {
                    validateItemCombo(combo);
                }
            });
        }
        return combo;
    }

    /**
     * Validates an item row's name on focus loss: a name that is not a {@code *.db}
     * file ({@link SqliteDatabasePaths#isDbFile(String)}) is rejected with an error
     * and reverted to the row's last valid name. An empty name is left to the
     * empty-removes-it behaviour ({@link #onItemComboChange(JComboBox)}).
     *
     * @param combo the item row's editable combo
     */
    private void validateItemCombo(JComboBox<String> combo) {
        if (isProgrammaticChange) {
            return;
        }
        String text = comboText(combo).trim();
        if (text.isEmpty() || SqliteDatabasePaths.isDbFile(text)) {
            return;
        }
        rejectInvalidName(text);
        String lastValid = (String) combo.getClientProperty(LAST_VALID_FILE);
        isProgrammaticChange = true;
        try {
            combo.getEditor().setItem(lastValid == null ? "" : lastValid);
        } finally {
            isProgrammaticChange = false;
        }
    }

    /**
     * An item edit: an emptied input removes that file ("clearing deletes it"),
     * any other edit fires the change hook. Deferred to the EDT because this runs
     * inside a document-change notification and the rebuild path reads the same
     * editors (no mutating a document in its own notification).
     */
    private void onItemComboChange(JComboBox<String> combo) {
        SwingUtilities.invokeLater(() -> {
            if (isProgrammaticChange) {
                return;
            }
            String text = comboText(combo).trim();
            if (text.isEmpty()) {
                removeItem(combo);
            } else if (SqliteDatabasePaths.isDbFile(text)) {
                combo.putClientProperty(LAST_VALID_FILE, text);
                fireChange();
            }
            // A non-.db edit is held (no hook) until focus loss validates it and
            // reverts an invalid name (see validateItemCombo).
        });
    }

    /** Installs the Enter action on the add row's editor: Enter adds the typed file. */
    private void installAddEnterAction() {
        Component editorComponent = addField.getEditor().getEditorComponent();
        if (editorComponent instanceof JTextField textField) {
            String enterAction = "addDatabaseFile";
            textField.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), enterAction);
            textField.getActionMap().put(enterAction, new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    addItem(comboText(addField));
                }
            });
        }
    }

    private void applyCandidateModel(JComboBox<String> combo) {
        String current = comboText(combo);
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(candidates.toArray(new String[0]));
        combo.setModel(model);
        combo.getEditor().setItem(current);
    }

    /** @return the combo's current editor text (or its selected item), never null */
    private static String comboText(JComboBox<String> combo) {
        Object item = combo.isEditable() ? combo.getEditor().getItem() : combo.getSelectedItem();
        return item == null ? "" : item.toString();
    }

    private void fireChange() {
        if (onChange != null) {
            onChange.run();
        }
    }
    /**
     * Colors every file row's editor (and the add row's editor) with the given
     * state color — the row-level analogue of the single-value inputs' state
     * coloring. A null color is a no-op (keeps the current foreground). Must be
     * called on the Swing EDT.
     *
     * @param color the foreground state color (null to leave the rows unchanged)
     */
    public void applyItemForeground(Color color) {
        if (color == null) {
            return;
        }
        for (JComboBox<String> combo : itemFields) {
            applyComboForeground(combo, color);
        }
        applyComboForeground(addField, color);
    }

    private static void applyComboForeground(JComboBox<String> combo, Color color) {
        combo.setForeground(color);
        Component editorComponent = combo.getEditor().getEditorComponent();
        if (editorComponent instanceof JComponent editor) {
            editor.setForeground(color);
        }
    }

    /**
     * Sets the first item row's text — the active database file — and fires the
     * change hook. No-op when the list is empty. Intended for programmatic edits
     * and tests (the add row appends; it never retargets the active file).
     *
     * @param text the new active file name (null becomes empty)
     */
    public void setActiveFileText(String text) {
        if (itemFields.isEmpty()) {
            return;
        }
        JComboBox<String> combo = itemFields.get(0);
        isProgrammaticChange = true;
        try {
            combo.getEditor().setItem(text == null ? "" : text);
        } finally {
            isProgrammaticChange = false;
        }
        fireChange();
    }

    /** The item rows' editable combos, in display order (package-private, for tests). */
    List<JComboBox<String>> itemCombos() {
        return itemFields;
    }
}


