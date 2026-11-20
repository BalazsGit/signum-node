package application.module.database.gui;

import application.utils.gui.ConfigurationUtils;
import application.utils.gui.FlatIconButton;
import application.utils.gui.FlatPlusButton;
import application.utils.gui.GuiColors;
import application.utils.gui.GuiConstants;
import application.utils.gui.HelpButton;
import application.utils.gui.IconFontCache;
import jiconfont.icons.font_awesome.FontAwesome;
import net.miginfocom.swing.MigLayout;

import javax.swing.*;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Component;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * A multi-row editor of a file-based SQLite profile's database files, modeled
 * directly on the node configuration panel's expandable list editors
 * ({@code Allowed IPs}, {@code Admin Password}): one editable combo per stored
 * database file (with a trash icon that removes that file) plus a trailing add
 * row — an editable combo and the flat, non-growing {@link FlatPlusButton}
 * (pressing Enter in the add row's combo adds it too).
 * <p>
 * The {@code .db} extension is NOT part of the editable text: it is displayed
 * as a static, non-interactive suffix label to the right of each combo. The
 * editor stores file names without the extension internally, but
 * {@link #getItems()} appends {@code .db} so the owning panel always receives
 * the full conventional name.
 * </p>
 * <p>
 * <b>Dropdown behavior:</b> every item row's dropdown lists the row's saved
 * (baseline) value plus its current (unsaved) editor value when it differs —
 * rebuilt on every edit so the saved value can always be picked back in one
 * click — and each entry is colored by the state of its OWN value (saved
 * yellow, unsaved the normal text color; the node configuration panel's
 * state-aware dropdown in its 2-state form). The add row's dropdown lists the
 * discovered {@code *.db} file names (without the extension) as selectable,
 * editable candidates, colored the same way (a discovered file is saved).
 * </p>
 * <p>
 * <b>Trash semantics:</b> the trash icon acts on the row's SAVED (on-disk)
 * file: a renamed row still confirms and deletes the file that exists, not
 * the not-yet-existing new name (the node configuration list editors'
 * saved-item deletion). A row WITHOUT a saved value is a not-yet-created
 * file and is removed immediately, without any confirmation.
 * </p>
 */
public final class DatabaseFileListEditor extends JPanel {

    private final JPanel itemsPanel;
    private final JComboBox<String> addField;
    private final FlatPlusButton addButton;
    private final HelpButton helpBtn;
    private final List<JComboBox<String>> itemFields = new ArrayList<>();
    private final int iconColumnWidth;

    private List<String> addRowCandidates = List.of();

    /** Maps each item combo to its saved (baseline) value for dropdown coloring. */
    private final java.util.Map<JComboBox<String>, String> savedValuesByCombo = new java.util.IdentityHashMap<>();

    public Runnable onChange;

    public java.util.function.Function<String, Boolean> removeConfirmation;

    public Runnable helpAction;

    private Set<String> protectedFiles = Set.of();

    public void setProtectedFiles(Set<String> files) {
        this.protectedFiles = files == null ? Set.of() : files;
        rebuildItems();
    }

    private boolean isProgrammaticChange;

    public DatabaseFileListEditor() {
        super(new BorderLayout());
        setOpaque(false);

        JButton trashProto = new FlatIconButton();
        trashProto.setIcon(IconFontCache.buildIcon(FontAwesome.TRASH,
                GuiConstants.getHelpIconSize(), GuiColors.getContrastRed()));
        FlatPlusButton plusProto = new FlatPlusButton();
        helpBtn = new HelpButton();
        iconColumnWidth = Math.max(trashProto.getPreferredSize().width,
                Math.max(plusProto.getPreferredSize().width, helpBtn.getPreferredSize().width));
        int actionColumnWidth = iconColumnWidth + ConfigurationUtils.INPUT_ACTION_GAP;

        itemsPanel = new JPanel(new MigLayout("insets 0, fillx",
                "[grow][pref, grow 0][" + actionColumnWidth + "px, grow 0]"
                        + "[" + actionColumnWidth + "px, grow 0]", ""));
        itemsPanel.setOpaque(false);
        add(itemsPanel, BorderLayout.CENTER);

        addField = new JComboBox<>(new String[0]);
        addField.setEditable(true);
        ConfigurationUtils.styleComboEditor(addField);
        addField.setPrototypeDisplayValue("signum.sqlite");
        addField.setToolTipText("Type a database file name (without .db), then press '+' (or Enter) to add it.");
        // Every add-row candidate is colored by the state of its OWN value: a
        // discovered file (it exists on disk) is saved (yellow), anything
        // else (e.g. the typed, not-yet-created name) unsaved — the node
        // configuration panel list editors' per-item dropdown coloring.
        addField.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof String s) {
                    c.setForeground(addRowCandidates.contains(s)
                            ? GuiColors.getSaved() : GuiColors.getUnsaved());
                }
                return c;
            }
        });
        installAddEnterAction();

        helpBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        helpBtn.setToolTipText("Click for more info");
        helpBtn.addActionListener(e -> {
            if (helpAction != null) {
                helpAction.run();
            }
        });

        addButton = new FlatPlusButton();
        addButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        addButton.setToolTipText("Add a database file to this profile");
        addButton.addActionListener(e -> addItem(comboText(addField)));

        rebuildItems();
    }

    public void setCandidates(List<String> candidateNames) {
        this.addRowCandidates = candidateNames == null ? List.of() : candidateNames;
        isProgrammaticChange = true;
        try {
            applyAddRowCandidateModel();
        } finally {
            isProgrammaticChange = false;
        }
    }

    /** @return the item's editable combo boxes (the panel's search highlights their editor fields). */
    List<JComboBox<String>> getItemFields() {
        return itemFields;
    }

    public List<String> getItems() {
        List<String> items = new ArrayList<>();
        for (JComboBox<String> combo : itemFields) {
            String text = comboText(combo).trim();
            if (!text.isEmpty()) {
                items.add(text + ".db");
            }
        }
        return items;
    }

    /**
     * The rows the user RENAMED: the (saved, current) file name pairs WITHOUT
     * the {@code .db} extension, for every item row that has a saved
     * (baseline) value and whose current editor text differs from it. The
     * owning panel uses this to RENAME the existing files on disk (with their
     * content) when the profile is saved.
     *
     * @return the rename pairs (possibly empty, never null)
     */
    public List<String[]> getRenamedFiles() {
        List<String[]> renames = new ArrayList<>();
        for (JComboBox<String> combo : itemFields) {
            String saved = savedValuesByCombo.get(combo);
            if (saved == null || saved.isEmpty()) {
                continue;
            }
            String current = comboText(combo).trim();
            if (!current.isEmpty() && !current.equals(saved)) {
                renames.add(new String[] { saved, current });
            }
        }
        return renames;
    }

    public void setItemsWithProtected(List<String> values, Set<String> protectedSet) {
        this.protectedFiles = protectedSet == null ? Set.of() : protectedSet;
        isProgrammaticChange = true;
        try {
            itemFields.clear();
            if (values != null) {
                for (String value : values) {
                    if (value != null && !value.trim().isEmpty()) {
                        String name = value.trim();
                        String editorText = name.endsWith(".db") ? name.substring(0, name.length() - 3) : name;
                        itemFields.add(makeItemCombo(editorText));
                    }
                }
            }
            rebuildItems();
        } finally {
            isProgrammaticChange = false;
        }
    }

    public void addItem(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) {
            return;
        }
        // Strip .db if the user typed it (the suffix is visual only)
        if (value.endsWith(".db")) {
            value = value.substring(0, value.length() - 3);
        }
        if (value.contains("/") || value.contains("\\")) {
            rejectInvalidName(value);
            return;
        }
        String fullName = value + ".db";
        String finalValue = value;
        if (itemFields.stream().anyMatch(f -> finalValue.equals(comboText(f).trim()))) {
            JOptionPane.showMessageDialog(this,
                    "The database file '" + fullName + "' is already in the list.",
                    "Duplicate file", JOptionPane.WARNING_MESSAGE);
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

    private void rejectInvalidName(String name) {
        JOptionPane.showMessageDialog(this,
                "The database file name must not contain path separators.\n\nName: " + name,
                "Invalid database file name", JOptionPane.ERROR_MESSAGE);
    }

    public void removeItem(JComboBox<String> field) {
        if (!itemFields.contains(field)) {
            return;
        }
        // The trash acts on the row's SAVED (on-disk) file: a renamed row still
        // deletes the file that exists, not the not-yet-existing new name (the
        // node configuration list editors' saved-item deletion). A row WITHOUT
        // a saved value is a not-yet-created file — removed immediately,
        // without any confirmation.
        String saved = savedValuesByCombo.get(field);
        if (saved != null && !saved.isEmpty()
                && removeConfirmation != null
                && !removeConfirmation.apply(saved + ".db")) {
            return;
        }
        itemFields.remove(field);
        isProgrammaticChange = true;
        try {
            rebuildItems();
        } finally {
            isProgrammaticChange = false;
        }
        fireChange();
    }

    private void rebuildItems() {
        itemsPanel.removeAll();
        for (int i = 0; i < itemFields.size(); i++) {
            JComboBox<String> combo = itemFields.get(i);
            itemsPanel.add(combo, "growx, gapright 0");
            itemsPanel.add(makeDbSuffixLabel(), "gapleft 2, gapright 0, align trailing");

            FlatIconButton removeBtn = new FlatIconButton();
            removeBtn.setIcon(IconFontCache.buildIcon(FontAwesome.TRASH,
                    GuiConstants.getHelpIconSize(),
                    GuiColors.getContrastRed()));
            removeBtn.setToolTipText("Remove this database file");
            removeBtn.addActionListener(e -> removeItem(combo));
            removeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            itemsPanel.add(removeBtn,
                    "gapleft " + ConfigurationUtils.INPUT_ACTION_GAP + ", w " + iconColumnWidth + "!, growx");
            if (i == 0) {
                itemsPanel.add(helpBtn, "gapleft " + ConfigurationUtils.INPUT_ACTION_GAP + ", wrap");
            } else {
                itemsPanel.add(rowColumnSpacer(), "gapleft " + ConfigurationUtils.INPUT_ACTION_GAP + ", wrap");
            }
        }
        itemsPanel.add(addField, "growx, gapright 0");
        itemsPanel.add(makeDbSuffixLabel(), "gapleft 2, gapright 0, align trailing");
        itemsPanel.add(addButton,
                "gapleft " + ConfigurationUtils.INPUT_ACTION_GAP + ", w " + iconColumnWidth + "!, growx");
        itemsPanel.add(rowColumnSpacer(), "gapleft " + ConfigurationUtils.INPUT_ACTION_GAP + ", wrap");
        itemsPanel.revalidate();
        itemsPanel.repaint();
    }

    private JLabel makeDbSuffixLabel() {
        JLabel label = new JLabel(".db");
        label.setOpaque(false);
        label.setForeground(GuiColors.getFaintText());
        return label;
    }

    private JComponent rowColumnSpacer() {
        JPanel spacer = new JPanel(null);
        spacer.setOpaque(false);
        spacer.setPreferredSize(new Dimension(0, 0));
        return spacer;
    }

    private void installAddEnterAction() {
        Component editorComp = addField.getEditor().getEditorComponent();
        if (editorComp instanceof JTextField field) {
            field.addActionListener(e -> {
                if (!isProgrammaticChange) {
                    addItem(comboText(addField));
                }
            });
        }
    }

    private void applyAddRowCandidateModel() {
        String current = comboText(addField);
        DefaultComboBoxModel<String> model = new DefaultComboBoxModel<>(addRowCandidates.toArray(new String[0]));
        addField.setModel(model);
        addField.getEditor().setItem(current);
    }

    private JComboBox<String> makeItemCombo(String value) {
        JComboBox<String> combo = new JComboBox<>(new String[0]);
        combo.setEditable(true);
        ConfigurationUtils.styleComboEditor(combo);
        combo.setPrototypeDisplayValue("signum.sqlite");
        combo.setToolTipText("Database file name (without .db)");
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                    boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof String s) {
                    String saved = savedValuesByCombo.get(combo);
                    c.setForeground(saved != null && s.equals(saved)
                            ? GuiColors.getSaved() : GuiColors.getUnsaved());
                }
                return c;
            }
        });
        isProgrammaticChange = true;
        try {
            combo.getEditor().setItem(value);
        } finally {
            isProgrammaticChange = false;
        }
        installItemChangeHook(combo);
        return combo;
    }

    /**
     * Sets the saved (baseline) values for each item row's dropdown. The dropdown
     * for each row will show the saved value (without .db) and the current editor
     * value if different. This allows the user to quickly revert to the saved
     * value via the dropdown. Must be called on the Swing EDT.
     *
     * @param savedValues the saved file names WITHOUT .db (null entries skip that row)
     */
    public void setItemSavedValues(List<String> savedValues) {
        if (savedValues == null) {
            return;
        }
        savedValuesByCombo.clear();
        for (int i = 0; i < itemFields.size() && i < savedValues.size(); i++) {
            String saved = savedValues.get(i);
            if (saved != null && !saved.isEmpty()) {
                savedValuesByCombo.put(itemFields.get(i), saved);
            }
            rebuildItemModel(itemFields.get(i), comboText(itemFields.get(i)).trim());
        }
    }

    private void installItemChangeHook(JComboBox<String> combo) {
        Component editorComp = combo.getEditor().getEditorComponent();
        if (!(editorComp instanceof JTextField field)) {
            return;
        }
        field.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            public void insertUpdate(javax.swing.event.DocumentEvent e) { fireIfChanged(combo); }
            public void removeUpdate(javax.swing.event.DocumentEvent e) { fireIfChanged(combo); }
            public void changedUpdate(javax.swing.event.DocumentEvent e) { }
        });
    }

    private void fireIfChanged(JComboBox<String> combo) {
        if (isProgrammaticChange) {
            return;
        }
        String text = comboText(combo).trim();
        if (text.isEmpty()) {
            removeItem(combo);
            return;
        }
        // DEFERRED: this runs inside a document-change notification (the
        // editor's document listener), and the model swap reconfigures the
        // editor (touches its document) — mutating a document while its own
        // notification is in flight is forbidden by Swing ("Attempt to mutate
        // in notification"). The node configuration panel defers its
        // state-aware refresh the same way.
        SwingUtilities.invokeLater(() -> rebuildItemModel(combo, text));
        fireChange();
    }

    /**
     * Rebuilds one item row's dropdown model — the saved (baseline) value
     * plus the current editor value when it differs (the node configuration
     * panel's state-aware rebuild in its 2-state form): the user can always
     * pick the saved value back in one click. The entries are colored per
     * item by the row's renderer (saved yellow, unsaved normal text color).
     */
    private void rebuildItemModel(JComboBox<String> combo, String current) {
        String saved = savedValuesByCombo.get(combo);
        LinkedHashSet<String> items = new LinkedHashSet<>();
        if (saved != null && !saved.isEmpty()) {
            items.add(saved);
        }
        if (!current.isEmpty() && !items.contains(current)) {
            items.add(current);
        }
        isProgrammaticChange = true;
        try {
            combo.setModel(new DefaultComboBoxModel<>(items.toArray(new String[0])));
            // Keep the (typed) current value in the editor after the swap.
            combo.getEditor().setItem(current);
        } finally {
            isProgrammaticChange = false;
        }
    }

    private static String comboText(JComboBox<String> combo) {
        Object item = combo.isEditable() ? combo.getEditor().getItem() : combo.getSelectedItem();
        return item == null ? "" : item.toString();
    }

    private void fireChange() {
        if (onChange != null) {
            onChange.run();
        }
    }

    public void applyItemForeground(Color color) {
        if (color == null) {
            return;
        }
        for (JComboBox<String> combo : itemFields) {
            applyComboForeground(combo, color);
        }
        applyComboForeground(addField, color);
    }

    public void applyPerItemForegrounds(List<Color> colors) {
        if (colors == null || colors.isEmpty()) {
            return;
        }
        for (int i = 0; i < itemFields.size() && i < colors.size(); i++) {
            applyComboForeground(itemFields.get(i), colors.get(i));
        }
    }

    private static void applyComboForeground(JComboBox<String> combo, Color color) {
        combo.setForeground(color);
        Component editorComponent = combo.getEditor().getEditorComponent();
        if (editorComponent instanceof JComponent editor) {
            editor.setForeground(color);
        }
    }

    public void setActiveFileText(String text) {
        if (itemFields.isEmpty()) {
            return;
        }
        JComboBox<String> combo = itemFields.get(0);
        String editorText = text == null ? "" : text.trim();
        if (editorText.endsWith(".db")) {
            editorText = editorText.substring(0, editorText.length() - 3);
        }
        isProgrammaticChange = true;
        try {
            combo.getEditor().setItem(editorText);
        } finally {
            isProgrammaticChange = false;
        }
        fireChange();
    }

    List<JComboBox<String>> itemCombos() {
        return itemFields;
    }
}